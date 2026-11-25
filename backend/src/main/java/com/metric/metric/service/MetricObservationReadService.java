package com.metric.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** One read-only source for observation, dedicated screens and realtime metric values. */
@Service
@Transactional(readOnly = true)
public class MetricObservationReadService {
    private static final Set<String> DIRECT = Set.of("collected_value","resource_value","external_value","satisfaction_report");
    private static final Set<String> CONDITIONS = Set.of(">",">=","<","<=","=","==");
    private static final int TREND_LIMIT = 5000;
    private final MetricPeriodQueryService definitions;
    private final NamedParameterJdbcTemplate sql;

    public MetricObservationReadService(MetricPeriodQueryService definitions, JdbcTemplate jdbc) {
        this.definitions=definitions;
        this.sql=new NamedParameterJdbcTemplate(jdbc);
    }

    public List<Map<String,Object>> catalog() { return definitions.observationCatalog(null); }
    public boolean isDirect(Long metricId) {
        return definitions.observationCatalog(List.of(metricId)).stream().anyMatch(MetricObservationReadService::direct);
    }
    public List<Long> scope(String category,String subcategory) {
        var parameters=new HashMap<String,Object>();
        parameters.put("category",category);
        parameters.put("subcategory",subcategory==null || subcategory.isBlank()?null:subcategory);
        return sql.queryForList("""
            SELECT m.id FROM metric_item m JOIN metric_category c ON c.id=m.category_id
            JOIN system_info s ON s.id=c.system_id
            WHERE m.status=1 AND (:category IS NULL OR
              CASE WHEN c.category_code='system_run' OR c.category_name='系统运行' THEN '运行效率' ELSE c.category_name END=:category)
              AND (:subcategory IS NULL OR m.sub_category_name=:subcategory)
            """,parameters,Long.class);
    }

    // 分类已由本次请求的 catalog 判定，读取原始值时复用该结果，避免再次全表提取 JSON 编码。
    private static final String SOURCE_METRICS = """
        (SELECT i.*,CASE
          WHEN i.id IN (:satisfactionIds) THEN 'satisfaction_report'
          WHEN i.id IN (:resourceIds) THEN 'resource_value'
          WHEN i.id IN (:externalIds) THEN 'external_value'
          ELSE 'collected_value' END AS collection_mode
         FROM metric_item i WHERE i.id IN (:ids))
        """;
    private static final String RAW = "WITH observations AS ("+MetricPeriodQueryService.OBSERVATIONS
        .replace(CollectedMetricService.METRICS,SOURCE_METRICS)+"""
        ), selected AS (
          SELECT metricId,dataTime,rawValue,batchId,importedAt,sourceFile,sourceCode,sourceRow,conflict
          FROM observations WHERE metricId IN (:ids) AND dataTime>=:start AND dataTime<=:end
        ), revisions AS (
          SELECT *,ROW_NUMBER() OVER (PARTITION BY metricId,dataTime ORDER BY importedAt DESC,batchId DESC,sourceRow DESC) AS revision
          FROM selected
        ), observations_latest AS (
          SELECT *,ROW_NUMBER() OVER (PARTITION BY metricId ORDER BY dataTime DESC) AS latest
          FROM revisions WHERE revision=1
        )
        """;

    // Only the latest source revision at or after the candidate's snapshot can
    // match the report status. A later missing or conflicting observation still wins.
    private static final String STATUS_SOURCE = "WITH observations AS (" + MetricPeriodQueryService.OBSERVATIONS
        .replace(CollectedMetricService.METRICS, SOURCE_METRICS) + """
        ), ranked AS (
          SELECT o.metricId,o.dataTime,o.rawValue,o.conflict,
            ROW_NUMBER() OVER (PARTITION BY o.metricId
              ORDER BY o.dataTime DESC,o.importedAt DESC,o.batchId DESC,o.sourceRow DESC) AS revision
          FROM observations o JOIN metric_realtime rt ON rt.metric_id=o.metricId
          WHERE o.dataTime>=:start AND o.dataTime>=rt.data_time AND o.dataTime<=:end
        )
        SELECT metricId,dataTime,rawValue,conflict FROM ranked WHERE revision=1
        """;

    public long countLatestAbnormal(Collection<Long> candidates, LocalDateTime start, LocalDateTime end) {
        if (candidates.isEmpty()) return 0;
        var catalog = definitions.observationCatalog(candidates);
        if (catalog.isEmpty()) return 0;
        List<Long> ids = catalog.stream().map(MetricObservationReadService::id).toList();
        var snapshots = index(sql.queryForList("""
            SELECT metric_id AS metricId,CAST(metric_value AS CHAR) AS rawValue,data_time AS dataTime,
              update_time AS updatedAt,status
            FROM metric_realtime
            WHERE metric_id IN (:ids) AND data_time>=:start AND data_time<=:end AND status IN ('告警','工单')
            """, params(ids, start, end)));
        var directCatalog = catalog.stream().filter(MetricObservationReadService::direct)
            .filter(row -> snapshots.containsKey(id(row)) && hasAlarmThreshold(row) && number(row,"codeMatches") == 1)
            .toList();
        var sources = directCatalog.isEmpty() ? Map.<Long, Map<String,Object>>of()
            : index(sql.queryForList(STATUS_SOURCE, sourceParameters(directCatalog, start, end)));
        return catalog.stream().filter(definition -> {
            long metricId = id(definition);
            var snapshot = snapshots.get(metricId);
            if (snapshot == null || !hasAlarmThreshold(definition) || number(definition,"codeMatches") != 1) return false;
            var source = direct(definition) ? sources.get(metricId) : snapshot;
            if (source == null || number(source,"conflict") > 0) return false;
            var value = decimal(source.get("rawValue"));
            return value != null && matchesSnapshot(definition, snapshot, value, time(source.get("dataTime")));
        }).count();
    }

    private static boolean hasAlarmThreshold(Map<String,Object> definition) {
        return definition.get("alarmThreshold") != null
            && CONDITIONS.contains(Objects.toString(definition.get("alarmCondition"), ""));
    }

    private static boolean matchesSnapshot(Map<String,Object> definition, Map<String,Object> cached,
                                           BigDecimal value, LocalDateTime dataTime) {
        if (cached == null) return false;
        LocalDateTime ruleTime = time(definition.get("ruleUpdatedAt"));
        LocalDateTime cacheTime = time(cached.get("updatedAt"));
        BigDecimal cachedValue = decimal(cached.get("rawValue"));
        return cachedValue != null && value.compareTo(cachedValue) == 0 && Objects.equals(dataTime, time(cached.get("dataTime")))
            && (ruleTime == null || cacheTime != null && !cacheTime.isBefore(ruleTime));
    }

    private Map<Long,Map<String,Object>> latestSourceRows(List<Map<String,Object>> catalog, LocalDateTime start, LocalDateTime end) {
        return index(sql.queryForList(RAW+"SELECT * FROM observations_latest WHERE latest=1", sourceParameters(catalog,start,end)));
    }

    public List<Map<String,Object>> latest(Collection<Long> requested,LocalDateTime rangeStart,LocalDateTime end) {
        return latest(requested,rangeStart,end,false,true);
    }

    /** Latest source rows plus period counts from the same ranked observation set. */
    public List<Map<String,Object>> latestWithPeriodStatistics(Collection<Long> requested,LocalDateTime rangeStart,LocalDateTime end) {
        return latest(requested,rangeStart,end,true,true);
    }

    private List<Map<String,Object>> latest(Collection<Long> requested,LocalDateTime rangeStart,LocalDateTime end,boolean includePeriodStatistics,boolean includeHistoricalFallback) {
        if(requested!=null && requested.isEmpty()) return List.of();
        var catalog=definitions.observationCatalog(requested);
        if(catalog.isEmpty()) return List.of();
        List<Long> ids=catalog.stream().map(MetricObservationReadService::id).toList();
        var params=params(ids,LocalDateTime.of(1970,1,1,0,0),end);
        List<Long> sourceIds = catalog.stream().filter(MetricObservationReadService::direct)
            .map(MetricObservationReadService::id).toList();
        Map<Long,Map<String,Object>> sourceRows;
        if(sourceIds.isEmpty()) sourceRows=Map.of();
        else if (!includePeriodStatistics && rangeStart != null && rangeStart.isAfter(LocalDateTime.of(1970,1,1,0,0))) {
            // A row in the selected period is always newer than an out-of-period fallback.
            // Only metrics without a period row need the historical scan.
            sourceRows = latestSourceRows(catalog,rangeStart,end);
            var missing = catalog.stream().filter(MetricObservationReadService::direct)
                .filter(row -> !sourceRows.containsKey(id(row))).toList();
            if (!missing.isEmpty()) {
                sourceRows.putAll(latestSourceRows(missing,LocalDateTime.of(1970,1,1,0,0),end));
            }
        }
        else {
            var sourceParams=sourceParameters(catalog,includeHistoricalFallback?LocalDateTime.of(1970,1,1,0,0):rangeStart,end);
            String sourceQuery=RAW+"SELECT * FROM observations_latest WHERE latest=1";
            if(includePeriodStatistics) {
                sourceParams.put("periodStart",rangeStart==null?LocalDateTime.of(1970,1,1,0,0):rangeStart);
                sourceQuery=RAW+", period_statistics AS ("+"""
                    SELECT metricId,COUNT(*) AS periodRecords,
                      SUM(CASE WHEN conflict=0 AND rawValue REGEXP '^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$' THEN 1 ELSE 0 END) AS periodValidRecords,
                      MAX(dataTime) AS periodLatestDataTime
                    FROM revisions WHERE revision=1 AND dataTime>=:periodStart AND dataTime<=:end GROUP BY metricId
                    ) SELECT latest_rows.*,period_statistics.periodRecords,period_statistics.periodValidRecords,period_statistics.periodLatestDataTime
                      FROM observations_latest latest_rows LEFT JOIN period_statistics ON period_statistics.metricId=latest_rows.metricId
                      WHERE latest_rows.latest=1
                    """;
            }
            sourceRows=index(sql.queryForList(sourceQuery,sourceParams));
        }
        var snapshots=index(sql.queryForList("""
            SELECT metric_id AS metricId,CAST(metric_value AS CHAR) AS rawValue,data_time AS dataTime,
              update_time AS updatedAt,status,is_exceed AS isExceed,exceed_count AS exceedCount,id AS realtimeId
            FROM metric_realtime WHERE metric_id IN (:ids) AND data_time<=:end
            """,params));
        // 2026-11-17 P4：健康度不再按“采集类/直取类”区分，统一以规则引擎是否产出分数为准
        List<Long> healthIds = catalog.stream().map(MetricObservationReadService::id).toList();
        Map<Long,Map<String,Object>> health = healthIds.isEmpty() ? Map.of() : index(sql.queryForList("""
            WITH ranked AS (
              SELECT metric_id AS metricId,health_score AS healthScore,record_time AS healthRecordTime,data_time AS healthDataTime,
                first_classify AS firstClassify,second_classify AS secondClassify,
                ROW_NUMBER() OVER (PARTITION BY metric_id ORDER BY record_time DESC,id DESC) AS revision
              FROM metric_health_history WHERE metric_id IN (:ids) AND record_time<=:end
            ) SELECT * FROM ranked WHERE revision=1
            """,params(healthIds,LocalDateTime.of(1970,1,1,0,0),end)));
        List<Map<String,Object>> result=new ArrayList<>();
        for(var definition:catalog) {
            var row=new LinkedHashMap<>(definition);
            long metricId=id(definition);
            boolean fileMetric=direct(definition);
            var source=fileMetric?sourceRows.get(metricId):snapshots.get(metricId);
            boolean ambiguous=MetricPeriodQueryService.number(definition,"codeMatches")!=1;
            BigDecimal value=source==null || ambiguous || number(source,"conflict")>0?null:decimal(source.get("rawValue"));
            LocalDateTime dataTime=source==null || ambiguous?null:time(source.get("dataTime"));
            String state=ambiguous?"编码冲突":source==null?"缺数":number(source,"conflict")>0?"来源冲突"
                :source.get("rawValue")!=null && value==null?"数值无效":value==null?"缺数":"已采集";
            // Never fall back to an older valid value after a newer revision reports null/conflict.
            String text=value==null?null:source.get("rawValue").toString();
            row.put("metricValue",text);row.put("currentValue",text);row.put("latestValue",text);
            row.put("metricDefinition",definition.get("definition"));
            row.put("dataTime",format(dataTime));row.put("latestDataTime",format(dataTime));
            row.put("recordTime",format(dataTime));row.put("dataStatus",state);
            row.put("sourceKind",fileMetric?"collection":"retained_snapshot");
            row.put("historySourceAvailable",fileMetric);
            row.put("sourceFile",source==null || ambiguous?null:source.get("sourceFile"));
            row.put("batchId",source==null || ambiguous?null:source.get("batchId"));
            row.put("configuredSourceTable",definition.get("sourceTable"));
            row.put("configuredSourceField",definition.get("sourceField"));
            row.put("sourceTable",fileMetric?source!=null && "satisfaction_report_result".equals(source.get("sourceCode"))?"satisfaction_report_result":"collection_row":"metric_realtime");
            row.put("sourceField","metric_value");
            row.put("importedAt",source==null?null:format(time(source.get("importedAt"))));
            row.put("sourceNotice",ambiguous?"指标编码缺失或不唯一，无法关联来源，请核对配置。":fileMetric?"指标值来自采集记录，按原始数据时间展示；同时间的修订取最近导入版本。"
                :"仅保留当前精确值快照，无法提供完整历史；未用整数健康度历史替代指标值。");
            if(includePeriodStatistics && fileMetric) {
                row.put("periodRecords",source==null?0:source.get("periodRecords"));
                row.put("periodValidRecords",source==null?0:source.get("periodValidRecords"));
                row.put("periodLatestDataTime",source==null?null:source.get("periodLatestDataTime"));
            }
            row.put("isStale",dataTime!=null && rangeStart!=null && dataTime.isBefore(rangeStart));
            // 2026-11-17 P4：健康度改为规则驱动后，采集类指标也会参与算分，
            // 是否展示分数只看有没有新算法记录，不再按“采集类/直取类”屏蔽；
            // 也不再要求指标值时间与算分时的数据时间完全相等（采集类两者来源不同，永远对不上）。
            row.put("healthScore",null);row.put("healthRecordTime",null);
            var h=health.get(metricId);
            if(h!=null) {
                row.put("firstClassify",h.get("firstClassify"));row.put("secondClassify",h.get("secondClassify"));
                row.put("healthScore",h.get("healthScore"));
                row.put("healthRecordTime",format(time(h.get("healthRecordTime"))));
            }
            row.put("latestCalculationTime",row.get("healthRecordTime"));
            row.put("healthStatus",row.get("healthScore")==null?"暂无健康度":"已有健康度记录");
            row.put("isExceed",null);row.put("exceedCount",null);
            row.put("alarmStatus",definition.get("alarmEnabled"));row.put("gongdanStatus",definition.get("gongdanEnabled"));
            String status=state.equals("已采集")?"待判定":state;
            if(value!=null) {
                if(!hasAlarmThreshold(definition)) status="待配置";
                else {
                    var cached=snapshots.get(metricId);
                    if(matchesSnapshot(definition,cached,value,dataTime)) {
                        status=Objects.toString(cached.get("status"),"待判定");
                        row.put("isExceed",cached.get("isExceed"));row.put("exceedCount",cached.get("exceedCount"));
                    }
                }
            }
            row.put("status",status);
            row.put("threshold",definition.get("alarmThreshold"));
            row.put("id",metricId);
            row.replaceAll((key,object) -> object instanceof BigDecimal decimal ? decimal.toPlainString()
                : object instanceof java.sql.Timestamp timestamp ? format(timestamp.toLocalDateTime()) : object);
            result.add(row);
        }
        List<Long> ticketMetrics=result.stream().filter(row -> "工单".equals(row.get("status"))).map(MetricObservationReadService::id).toList();
        if(!ticketMetrics.isEmpty()) {
            var tickets=index(sql.queryForList("""
                WITH ranked AS (
                  SELECT metric_id AS metricId,id AS gongdanId,ROW_NUMBER() OVER (PARTITION BY metric_id ORDER BY gongdan_time DESC,id DESC) AS revision
                  FROM gongdan WHERE metric_id IN (:ids)
                ) SELECT * FROM ranked WHERE revision=1
                """,Map.of("ids",ticketMetrics)));
            result.forEach(row -> { if(tickets.containsKey(id(row))) row.put("gongdanId",tickets.get(id(row)).get("gongdanId")); });
        }
        return result;
    }

    public Map<String,Object> trend(Long metricId,LocalDateTime start,LocalDateTime end) {
        var current=latest(List.of(metricId),start,end);
        if(current.isEmpty()) return Map.of("data",List.of(),"notice","指标不存在或未启用","truncated",false);
        var metric=current.get(0);
        List<Map<String,Object>> points=new ArrayList<>();
        boolean truncated=false;
        boolean fileMetric=direct(metric);
        if(fileMetric && number(metric,"codeMatches")==1) {
            var params=sourceParameters(List.of(metric),start,end);
            params.put("limit",TREND_LIMIT+1);
            var rows=sql.queryForList(RAW+"SELECT * FROM observations_latest ORDER BY dataTime DESC LIMIT :limit",params);
            truncated=rows.size()>TREND_LIMIT;
            for(var source:rows.subList(0,Math.min(rows.size(),TREND_LIMIT))) {
                var point=point(metric,source);
                points.add(point);
            }
            Collections.reverse(points);
            if(!truncated && Set.of("日","每日","每天","1d").contains(Objects.toString(metric.get("collectFrequency"),""))) {
                Set<LocalDate> days=new HashSet<>();
                points.forEach(p -> days.add(time(p.get("time")).toLocalDate()));
                for(LocalDate day=start.toLocalDate();!day.isAfter(end.toLocalDate());day=day.plusDays(1)) {
                    LocalDateTime at=day.atStartOfDay();
                    if(!days.contains(day) && !at.isBefore(start) && !at.isAfter(end)) {
                        var missing=new LinkedHashMap<String,Object>();
                        missing.put("time",format(at));missing.put("recordTime",format(at));missing.put("dataTime",format(at));
                        missing.put("metricValue",null);missing.put("value",null);missing.put("healthScore",null);
                        missing.put("unit",metric.get("unit"));missing.put("status","缺数");missing.put("missing",true);
                        points.add(missing);
                    }
                }
                points.sort(Comparator.comparing(p -> p.get("time").toString()));
            }
        } else if(!fileMetric && metric.get("dataTime")!=null && !time(metric.get("dataTime")).isBefore(start)) {
            var p=new LinkedHashMap<String,Object>();
            p.put("time",metric.get("dataTime"));p.put("dataTime",metric.get("dataTime"));p.put("recordTime",metric.get("dataTime"));
            p.put("metricValue",metric.get("metricValue"));p.put("value",metric.get("metricValue"));
            p.put("healthScore",metric.get("healthScore"));p.put("status",metric.get("status"));p.put("unit",metric.get("unit"));
            points.add(p);
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("data",points);result.put("metric",metric);result.put("truncated",truncated);
        result.put("notice",truncated?"数据较多，仅展示最近5000条原始观测，请缩短时间范围。"
            :fileMetric?"按原始数据时间展示，不求平均；空值保留断点。":"历史不足，仅展示所选范围内已有的当前留存快照。");
        return result;
    }

    public List<Map<String,Object>> healthTrend(Long metricId,LocalDateTime start,LocalDateTime end) {
        // 2026-11-17 P4：健康度改为规则驱动后，采集类指标也可能参与算分（例如动态基线），
        // 不能再按“是否采集类”直接屏蔽健康度历史；有没有分数以 metric_health_history 实际记录为准。
        return sql.queryForList("""
            SELECT DATE_FORMAT(record_time,'%Y-%m-%d %H:%i:%s') AS recordTime,health_score AS healthScore,status
            FROM metric_health_history WHERE metric_id=:metricId AND record_time>=:start AND record_time<=:end
            ORDER BY record_time LIMIT 5000
            """,Map.of("metricId",metricId,"start",start,"end",end));
    }

    public List<Map<String,Object>> periodData(Collection<Long> requested,LocalDateTime start,LocalDateTime end) {
        return periodDataFromLatest(latestWithPeriodStatistics(requested,start,end),start,end);
    }

    /** Period counts and timestamps do not require source values before the requested window. */
    public List<Map<String,Object>> periodDataInRange(Collection<Long> requested,LocalDateTime start,LocalDateTime end) {
        return periodDataFromLatest(latest(requested,start,end,true,false),start,end);
    }

    public List<Map<String,Object>> periodDataFromLatest(List<Map<String,Object>> latestRows,LocalDateTime start,LocalDateTime end) {
        List<Map<String,Object>> current = latestRows.stream()
            .map(row -> (Map<String,Object>)new LinkedHashMap<>(row)).toList();
        if(current.isEmpty()) return List.of();
        boolean hasCollectedMetrics=current.stream().anyMatch(MetricObservationReadService::direct);
        boolean hasPeriodStatistics=hasCollectedMetrics && current.stream().filter(MetricObservationReadService::direct)
            .allMatch(row -> row.containsKey("periodRecords"));
        var params=sourceParameters(current,start,end);
        Map<Long,Map<String,Object>> statistics=hasCollectedMetrics && !hasPeriodStatistics ? index(sql.queryForList(RAW+"""
            SELECT metricId,COUNT(*) AS records,
              SUM(CASE WHEN conflict=0 AND rawValue REGEXP '^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$' THEN 1 ELSE 0 END) AS validRecords,
              MAX(dataTime) AS latestDataTime
            FROM revisions WHERE revision=1 GROUP BY metricId
            """,params)) : Map.of();
        for(var row:current) {
            var stat=direct(row) && number(row,"codeMatches")==1
                ?hasPeriodStatistics?row:statistics.get(id(row)):null;
            boolean eligible=row.get("dataTime")!=null && !Boolean.TRUE.equals(row.get("isStale")) && number(row,"codeMatches")==1;
            long records=direct(row)?number(row,"codeMatches")==1 && stat!=null?number(stat,hasPeriodStatistics?"periodRecords":"records"):0:eligible?1:0;
            long valid=direct(row)?number(row,"codeMatches")==1 && stat!=null?number(stat,hasPeriodStatistics?"periodValidRecords":"validRecords"):0:eligible && row.get("metricValue")!=null?1:0;
            row.put("records",records);row.put("validRecords",valid);
            Object latest=stat==null?null:stat.get(hasPeriodStatistics?"periodLatestDataTime":"latestDataTime");
            row.put("latestDataTime",direct(row)?latest==null?null:format(time(latest)):eligible?row.get("dataTime"):null);
        }
        return current;
    }

    private static Map<String,Object> point(Map<String,Object> metric,Map<String,Object> source) {
        var p=new LinkedHashMap<String,Object>();
        BigDecimal value=number(source,"conflict")==0?decimal(source.get("rawValue")):null;
        Object text=value==null?null:source.get("rawValue");
        String stamp=format(time(source.get("dataTime")));
        p.put("time",stamp);p.put("recordTime",stamp);p.put("dataTime",stamp);
        p.put("metricValue",text);p.put("value",text);p.put("unit",metric.get("unit"));p.put("healthScore",null);
        p.put("metricId",metric.get("metricId"));p.put("metricName",metric.get("metricName"));
        p.put("status",number(source,"conflict")>0?"来源冲突":value==null?"缺数":"已采集");
        return p;
    }
    private static Map<String,Object> sourceParameters(List<Map<String,Object>> catalog,LocalDateTime start,LocalDateTime end) {
        List<Long> ids=catalog.stream().filter(MetricObservationReadService::direct)
            .map(MetricObservationReadService::id).toList();
        var result=params(ids.isEmpty()?List.of(-1L):ids,start,end);
        for(var entry:Map.of("satisfactionIds","satisfaction_report","resourceIds","resource_value","externalIds","external_value").entrySet()) {
            List<Long> matching=catalog.stream().filter(row -> entry.getValue().equals(row.get("ruleType")))
                .map(MetricObservationReadService::id).toList();
            result.put(entry.getKey(),matching.isEmpty()?List.of(-1L):matching);
        }
        return result;
    }

    private static Map<String,Object> params(List<Long> ids,LocalDateTime start,LocalDateTime end) { return new HashMap<>(Map.of("ids",ids,"start",start,"end",end)); }
    private static Map<Long,Map<String,Object>> index(List<Map<String,Object>> rows) { var result=new HashMap<Long,Map<String,Object>>();rows.forEach(row -> result.put(id(row),row));return result; }
    private static long id(Map<String,Object> row) { return number(row,"metricId"); }
    private static long number(Map<String,Object> row,String key) { return MetricPeriodQueryService.number(row,key); }
    private static boolean direct(Map<String,Object> row) { return DIRECT.contains(Objects.toString(row.get("ruleType"),"")); }
    private static BigDecimal decimal(Object value) { try { return value==null?null:new BigDecimal(value.toString()); } catch(NumberFormatException e) { return null; } }
    private static String format(LocalDateTime value) { return value==null?null:value.toString().replace('T',' '); }
    private static LocalDateTime time(Object value) {
        if(value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        if(value instanceof LocalDateTime timestamp) return timestamp;
        return value==null?null:LocalDateTime.parse(value.toString().replace(' ','T'));
    }
}
