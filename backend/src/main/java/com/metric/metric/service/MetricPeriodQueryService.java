package com.metric.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Read-only reporting boundary. Never scans, publishes, seeds, or changes metric rules. */
@Service
public class MetricPeriodQueryService {
    private final NamedParameterJdbcTemplate sql;

    public MetricPeriodQueryService(JdbcTemplate jdbc) {
        this.sql = new NamedParameterJdbcTemplate(jdbc);
    }

    private static final Set<String> DIRECT = Set.of("collected_value", "resource_value", "external_value", "satisfaction_report");
    private static final String NUMERIC = "^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$";
    /**
     * 采集行观测：环比分析与指标检索共用同一套口径（按 metricId+dataTime 去重修订、冲突校验、数值校验）。
     *
     * <p><b>指标范围在 CTE 内部下推</b>：原来指标范围只写在最外层 {@code period}，SQL 必须先把
     * <b>全历史</b> collection_row 连同 5 次 JSON_EXTRACT 完整物化，才轮到过滤指标；环比一次请求
     * 有两个周期，等于两次全量物化。把 {@code m.id IN (:ids)} 放进 observations 内部后，优化器可以
     * 反过来以 metric_item 为驱动表、按 metric_code 摘要索引回表，扫描量随选中指标数增长，
     * 而不是随采集历史总量增长。
     *
     * <p><b>时间条件不下推</b>：dataTime 是 JSON 里 {@code $.data_time} 的计算值，与
     * {@code collection_file.collected_at} 不等价——历史补采、回溯导入的文件 collected_at 很新，
     * data_time 却可能是几个月前。按文件时间剪枝会在无提示的情况下丢掉补采数据，所以时间过滤只
     * 保留最外层的 dataTime 条件；时间维度的正解是物化列 + 索引，须走备份/迁移流程。
     *
     * <p>{@code keepRawRecord} 控制是否物化整行原始 JSON：环比只要指标值序列，
     * 保留 rawRecord 等于每行多构造一份完整 JSON 字符串，属纯浪费。
     */
    private static String observations(String metricScope, boolean keepRawRecord) {
        String collectionRaw = keepRawRecord ? "CAST(r.data_json AS CHAR)" : "NULL";
        String reportRaw = keepRawRecord ? """
            CAST(JSON_OBJECT('metric_code',r.metric_code,'metric_name',r.metric_name,'metric_value',r.metric_value,
              'unit',r.unit,'source_cell',r.source_cell,'calculation_note',r.calculation_note,'value_status',r.value_status) AS CHAR)"""
            : "NULL";
        return """
          SELECT m.id AS metricId,
            CAST(REPLACE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.data_time')),'null'),'T',' ') AS DATETIME) AS dataTime,
            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.metric_value')),'null') AS rawValue,
            f.id AS batchId,f.collected_at AS importedAt,f.source_file AS sourceFile,
            f.source_code AS sourceCode,r.data_row_number AS sourceRow,
            %s AS rawRecord,
            /* 采集行缺字段（JSON null）视为“未提供”，不算冲突；只有提供了且与指标配置不一致才算冲突 */
            CASE WHEN
              (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.metric_name')),'null') IS NOT NULL
                AND BINARY NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.metric_name')),'null') <> BINARY m.metric_name)
              OR (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.unit')),'null') IS NOT NULL
                AND NOT (BINARY NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.unit')),'null') <=> BINARY m.unit))
              OR (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.category_name')),'null') IS NOT NULL
                AND BINARY NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.category_name')),'null') <> BINARY cat.category_name)
              OR (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.sub_category_name')),'null') IS NOT NULL
                AND NOT (BINARY NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.sub_category_name')),'null') <=> BINARY m.sub_category_name))
            THEN 1 ELSE 0 END AS conflict
            """.formatted(collectionRaw)
            + CollectedMetricService.JOIN
            + """
              AND m.status=1
            """
            + metricScope
            + """
              UNION ALL
              SELECT m.id,CAST(b.business_date AS DATETIME),CAST(r.metric_value AS CHAR),b.id,b.imported_at,b.source_file,
                'satisfaction_report_result',r.source_row,
                %s,
                CASE WHEN BINARY r.metric_name <> BINARY m.metric_name
                  OR (r.unit IS NOT NULL AND NOT (BINARY r.unit <=> BINARY m.unit)) THEN 1 ELSE 0 END
              FROM satisfaction_report_result r JOIN satisfaction_report_batch b ON b.id=r.batch_id
              JOIN %s m ON BINARY m.metric_code=BINARY r.metric_code AND m.collection_mode='satisfaction_report' AND m.status=1
            """.formatted(reportRaw, CollectedMetricService.METRICS)
            + metricScope;
    }
    /** 下推到 observations 内部的指标范围条件；只用于参数化 :ids 的批量取数，不含任何拼接值。 */
    private static final String METRIC_SCOPE = "\n  AND m.id IN (:ids)\n";

    static final String OBSERVATIONS = observations("", true);

    static final String REVISIONS = """
        ), revisions AS (
          SELECT *,ROW_NUMBER() OVER (PARTITION BY metricId,dataTime ORDER BY importedAt DESC,batchId DESC,sourceRow DESC) AS revision
          FROM observations WHERE dataTime>=:start AND dataTime<:end
        ), period AS (
          SELECT *,CASE WHEN conflict=0 AND rawValue REGEXP '%s' THEN 1 ELSE 0 END AS validValue,
            ROW_NUMBER() OVER (PARTITION BY metricId ORDER BY dataTime DESC) AS latest
          FROM revisions WHERE revision=1
        )
        """.formatted(NUMERIC);

    static final String PERIOD = "WITH observations AS (" + OBSERVATIONS + REVISIONS;

    /**
     * 与 {@link #PERIOD} 同口径，但把指标范围下推进 observations、并跳过用不到的原始 JSON 列。
     * 环比批量取序列走这一条，避免为了几个指标把全部采集历史物化两遍。
     */
    static final String PERIOD_BY_METRICS = "WITH observations AS (" + observations(METRIC_SCOPE, false) + REVISIONS;

    public List<Map<String,Object>> catalog(Long systemId) {
        return catalog(systemId,null);
    }
    List<Map<String,Object>> catalog(Long systemId,Collection<Long> ids) {
        return catalog(systemId,ids,CollectedMetricService.METRICS);
    }
    List<Map<String,Object>> observationCatalog(Collection<Long> ids) {
        return catalog(null,ids,CollectedMetricService.READ_METRICS);
    }
    private List<Map<String,Object>> catalog(Long systemId,Collection<Long> ids,String metricSource) {
        if(ids!=null && ids.isEmpty()) return List.of();
        var parameters=new HashMap<String,Object>();
        parameters.put("systemId",systemId);
        if(ids!=null) parameters.put("ids",ids);
        return sql.queryForList("""
            SELECT m.id AS metricId,m.metric_code AS metricCode,m.metric_name AS metricName,m.metric_definition AS definition,
              m.unit,m.sub_category_name AS subCategoryName,m.sub_category_code AS subCategoryCode,m.metric_type AS metricType,
              m.collection_mode AS calcRuleType,'metric_value' AS calcField,
              m.collect_frequency AS collectFrequency,m.collection_mode AS ruleType,m.data_source AS dataSource,
              (SELECT config_value FROM system_config WHERE config_key=CONCAT('satisfaction.alarm.policy.',m.metric_code) LIMIT 1) AS ruleExtra,
              CASE WHEN m.collection_mode='retained_snapshot' THEN 'metric_realtime' ELSE 'collection_row' END AS sourceTable,
              'metric_value' AS sourceField,c0.id AS categoryId,c0.category_code AS categoryCode,
              CASE WHEN c0.category_code='system_run' OR c0.category_name='系统运行' THEN '运行效率' ELSE c0.category_name END AS categoryName,
              sys.id AS systemId,sys.system_name AS systemName,
              alarm_rule.alarm_threshold AS alarmThreshold,alarm_rule.alarm_condition AS alarmCondition,c.alarm_window_minutes AS alarmWindowMinutes,
              alarm_rule.alarm_enabled AS alarmEnabled,alarm_rule.update_time AS ruleUpdatedAt,
              ticket_rule.gongdan_enabled AS gongdanEnabled,ticket_rule.gongdan_consecutive_times AS consecutiveCount,
              ticket_rule.gongdan_threshold AS gongdanThreshold,ticket_rule.gongdan_condition AS gongdanCondition,c.gongdan_window_minutes AS gongdanWindowMinutes,
              (SELECT COUNT(*) FROM metric_item other JOIN metric_category oc ON oc.id=other.category_id
                WHERE other.status=1 AND BINARY other.metric_code=BINARY m.metric_code
                  AND (BINARY oc.category_code=BINARY c0.category_code OR m.collection_mode='satisfaction_report')) AS codeMatches
            FROM %s m JOIN metric_category c0 ON c0.id=m.category_id JOIN system_info sys ON sys.id=c0.system_id
            %s WHERE m.status=1 AND (:systemId IS NULL OR sys.id=:systemId)
            """.formatted(metricSource,CollectedMetricService.RULE_JOINS)
            +(ids==null?"":" AND m.id IN (:ids)")+" ORDER BY sys.id,c0.id,m.sub_category_name,m.id",parameters);
    }

    public List<Map<String,Object>> periodMetrics(LocalDate start, LocalDate end, Long systemId) {
        var metrics = catalog(systemId);
        var summaries = new HashMap<Long,Map<String,Object>>();
        for (var row : sql.queryForList(PERIOD + """
            SELECT metricId,COUNT(*) AS observationCount,SUM(validValue) AS validCount,
              SUM(conflict) AS conflictCount,SUM(CASE WHEN rawValue IS NULL THEN 1 ELSE 0 END) AS missingValueCount,
              SUM(CASE WHEN rawValue IS NOT NULL AND NOT (rawValue REGEXP '%s') THEN 1 ELSE 0 END) AS invalidValueCount,
              MAX(CASE WHEN latest=1 THEN dataTime END) AS latestDataTime,
              MAX(CASE WHEN latest=1 AND validValue=1 THEN rawValue END) AS latestValue,
              MAX(CASE WHEN latest=1 THEN sourceFile END) AS sourceFile,
              MAX(CASE WHEN latest=1 THEN batchId END) AS batchId
            FROM period GROUP BY metricId
            """.formatted(NUMERIC), params(start,end))) {
            summaries.put(number(row,"metricId"),row);
        }
        var snapshots = new HashMap<Long,Map<String,Object>>();
        for (var row : sql.queryForList("""
            SELECT metric_id AS metricId,metric_value AS value,data_time AS dataTime,status
            FROM metric_realtime WHERE data_time>=:start AND data_time<:end
            """,params(start,end))) snapshots.put(number(row,"metricId"),row);

        for (var metric : metrics) {
            boolean direct = DIRECT.contains(Objects.toString(metric.get("ruleType"),""));
            boolean ambiguous = number(metric,"codeMatches") != 1 || Objects.toString(metric.get("metricCode"),"").isBlank();
            metric.put("sourceKind", direct ? "collection" : "retained_snapshot");
            metric.put("weeklyValue", null);
            metric.put("weeklyStatus", "周汇总口径待确认");
            metric.put("observationCount",0);
            metric.put("validCount",0);
            metric.put("conflictCount",0);
            metric.put("missingValueCount",0);
            metric.put("invalidValueCount",0);
            metric.put("latestValue",null);
            metric.put("latestDataTime",null);
            metric.put("sourceFile",null);
            metric.put("dataStatus", ambiguous ? "指标编码缺失或不唯一" : "周期内无数据");
            metric.put("sourceNote", "指标编码缺失或在来源范围内不唯一，不能自动关联采集记录；请先核对指标体系配置。");
            metric.put("ruleStatus",metric.get("alarmThreshold")==null ? "告警阈值未配置"
                : !Set.of(">",">=","<","<=","=","!=").contains(Objects.toString(metric.get("alarmCondition"),"")) ? "告警比较条件未配置" : "当前规则供核对，统计窗口待确认");
            if (ambiguous) continue;
            if (direct) {
                var summary = summaries.get(number(metric,"metricId"));
                if (summary != null) {
                    metric.putAll(summary);
                    metric.put("dataStatus",number(summary,"conflictCount")>0 || number(summary,"invalidValueCount")>0 ? "来源数据冲突，待核对"
                        : number(summary,"validCount")>0 ? summary.get("latestValue")==null ? "有历史记录，最新采集值缺失" : "有周期内采集记录" : "采集值缺失");
                }
                metric.put("sourceNote","按指标编码与采集来源关联；同编码、同数据时间保留最近导入的修订。最新值仅作明细预览，不代表周值。");
            } else {
                var snapshot = snapshots.get(number(metric,"metricId"));
                if (snapshot != null) {
                    metric.put("latestValue",decimal(snapshot.get("value")));
                    metric.put("latestDataTime",snapshot.get("dataTime"));
                    metric.put("observationCount",1);
                    metric.put("validCount",snapshot.get("value")==null?0:1);
                    metric.put("dataStatus",snapshot.get("value")==null?"留存快照值缺失":"仅有期内留存快照");
                }
                metric.put("sourceNote","当前只读取时间落在本期的留存快照；不是完整历史，无法据此判断整周正常或异常。未使用会截断小数的旧历史表。");
            }
        }
        metrics.forEach(MetricPeriodQueryService::normalize);
        return metrics;
    }

    /** Diagnose normalized source rows that cannot be assigned without guessing. */
    public Map<String,Object> mappingIssues(LocalDate start, LocalDate end, Long systemId) {
        var params=params(start,end);
        params.put("systemId",systemId);
        var rows=sql.queryForList("""
            WITH incoming AS (
              SELECT f.source_file AS sourceFile,f.source_code AS sourceCode,f.business_date AS businessDate,
                r.data_row_number AS sourceRow,
                NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.metric_code')),'null'),'') AS metricCode,
                NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$._collection.category_code')),'null') AS categoryCode,
                CAST(REPLACE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.data_time')),'null'),'T',' ') AS DATETIME) AS dataTime
              FROM collection_row r JOIN collection_file f ON f.id=r.batch_id
              WHERE JSON_CONTAINS_PATH(r.data_json,'one','$._collection','$.metric_code')=1
            ), scoped AS (
              SELECT i.*,(SELECT COUNT(*) FROM %s m JOIN metric_category c ON c.id=m.category_id
                WHERE m.status=1 AND m.collection_mode IN ('collected_value','resource_value','external_value','satisfaction_report')
                  AND %s AND (
                    BINARY i.categoryCode=BINARY c.category_code OR (i.categoryCode IS NULL AND (
                    i.sourceCode=m.data_source
                    OR (m.collection_mode='resource_value' AND i.sourceCode='resource_input')
                    OR (m.collection_mode='external_value' AND i.sourceCode='external_customer'))))) AS matches
              FROM incoming i WHERE
                (i.dataTime>=:start AND i.dataTime<:end OR (i.dataTime IS NULL AND i.businessDate>=DATE(:start) AND i.businessDate<DATE(:end)))
                AND (:systemId IS NULL OR EXISTS (SELECT 1 FROM %s m JOIN metric_category c ON c.id=m.category_id
                  WHERE c.system_id=:systemId AND m.status=1 AND (BINARY c.category_code=BINARY i.categoryCode
                    OR i.sourceCode=m.data_source)))
            )
            SELECT *,COUNT(*) OVER () AS issueTotal,
              CASE WHEN metricCode IS NULL THEN '指标编码缺失'
                   WHEN dataTime IS NULL THEN '原始数据时间缺失或无效；仅按文件业务日期提示，不计入指标值'
                   WHEN matches=0 THEN '指标编码或采集来源无法匹配当前启用配置'
                   ELSE '同一编码与采集来源关联到多个指标，待核对' END AS reason
            FROM scoped WHERE metricCode IS NULL OR dataTime IS NULL OR matches<>1
            ORDER BY businessDate DESC,sourceFile,sourceRow LIMIT 50
            """.formatted(CollectedMetricService.METRICS,MetricCodeIdentity.matchesSql("m.metric_code","i.metricCode","c.category_code"),CollectedMetricService.METRICS),params);
        rows.forEach(MetricPeriodQueryService::normalize);
        return Map.of("total",rows.isEmpty()?0:number(rows.get(0),"issueTotal"),"rows",rows);
    }

    public Map<String,Object> observations(Long metricId, LocalDate start, LocalDate end, int page, int size) {
        return observations(metricId, start.atStartOfDay(), end.plusDays(1).atStartOfDay(), page, size);
    }
    public Map<String,Object> observations(Long metricId, LocalDateTime start, LocalDateTime end, int page, int size) {
        var metric = catalog(null).stream().filter(m -> number(m,"metricId")==metricId).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("指标不存在或未启用"));
        if (number(metric,"codeMatches")!=1) throw new IllegalArgumentException("指标编码不唯一，不能自动关联采集数据");
        var params = params(start,end);
        params.put("metricId",metricId);
        params.put("limit",size);
        params.put("offset",(long)page*size);
        List<Map<String,Object>> rows;
        long total;
        if (DIRECT.contains(Objects.toString(metric.get("ruleType"),""))) {
            total = sql.queryForObject(PERIOD+"SELECT COUNT(*) FROM period WHERE metricId=:metricId",params,Long.class);
            rows = sql.queryForList(PERIOD+"""
                SELECT dataTime,rawValue AS metricValue,validValue,conflict,sourceFile,sourceCode,batchId,sourceRow,importedAt,rawRecord
                FROM period WHERE metricId=:metricId ORDER BY dataTime DESC LIMIT :limit OFFSET :offset
                """,params);
        } else {
            rows = sql.queryForList("""
                SELECT data_time AS dataTime,CAST(metric_value AS CHAR) AS metricValue,
                  CASE WHEN metric_value IS NULL THEN 0 ELSE 1 END AS validValue,
                  0 AS conflict,'metric_realtime' AS sourceCode,status AS rawRecord
                FROM metric_realtime WHERE metric_id=:metricId AND data_time>=:start AND data_time<:end
                """,params);
            total=rows.size();
            if (page>0) rows=List.of();
        }
        normalize(metric);
        rows.forEach(MetricPeriodQueryService::normalize);
        return Map.of("metric",metric,"rows",rows,"total",total,"page",page,"size",size);
    }

    /**
     * 环比分析用：批量取指定指标在 [start,end) 内的有效指标值序列。
     * 复用与「指标检索」同一套 PERIOD 口径（去重修订、冲突校验、数值校验），
     * 覆盖 collection_row + satisfaction_report_result 两类直取来源；留存快照类指标无历史，不在此列。
     * 返回每行 {metricId, dataTime, metricValue(字符串)}，由调用方做 avg/median 聚合。
     */
    public List<Map<String,Object>> metricValueSeries(Collection<Long> metricIds, LocalDateTime start, LocalDateTime end) {
        if (metricIds == null || metricIds.isEmpty()) return List.of();
        var params = params(start, end);
        params.put("ids", metricIds);
        List<Map<String,Object>> rows = sql.queryForList(
            PERIOD_BY_METRICS + " SELECT metricId, dataTime, rawValue AS metricValue FROM period WHERE validValue=1 AND metricId IN (:ids) ORDER BY metricId, dataTime",
            params);
        rows.forEach(MetricPeriodQueryService::normalize);
        return rows;
    }

    private static Map<String,Object> params(LocalDate start, LocalDate end) {
        return params(start.atStartOfDay(),end.plusDays(1).atStartOfDay());
    }
    private static Map<String,Object> params(LocalDateTime start, LocalDateTime end) {
        var result = new HashMap<String,Object>();
        result.put("start",start);
        result.put("end",end);
        return result;
    }
    public static long number(Map<String,Object> row,String key) { return row.get(key) instanceof Number n ? n.longValue():0; }
    private static String decimal(Object value) { return value instanceof BigDecimal d ? d.toPlainString():value==null?null:value.toString(); }
    private static void normalize(Map<String,Object> row) {
        row.replaceAll((key,value) -> value instanceof java.sql.Timestamp time ? time.toLocalDateTime().toString()
            : value instanceof java.sql.Date date ? date.toLocalDate().toString()
            : value instanceof BigDecimal decimal && key.endsWith("Threshold") ? decimal.toPlainString() : value);
    }
}
