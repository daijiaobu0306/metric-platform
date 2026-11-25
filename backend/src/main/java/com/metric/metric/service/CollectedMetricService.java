package com.metric.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Stable metric-domain boundary: receives normalized observations, never files or scripts. */
@Service
public class CollectedMetricService {
    private final JdbcTemplate jdbc;
    public CollectedMetricService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Observation(BigDecimal value, LocalDateTime dataTime) {}
    // Execution source comes from declared sources and actual code-bound collection records,
    // not the retired editable calculation columns on metric_item.
    // 末尾的 LIMIT 是物化屏障（结果仍为全部指标行，语义不变）：这层派生表里的相关子查询一旦被合并
    // 进外层查询，就会按 collection_row 的每一行重复求值，单指标最新值查询实测从 0.08s 恶化到 10 分钟以上。
    static final String METRICS = """
        (SELECT i.*,CASE
          WHEN c0.category_code='metric_g2' OR (c0.category_code='system_run' AND i.sub_category_name IN ('系统性能','系统异常')) THEN 'collected_value'
          WHEN i.data_source='satisfaction_report' OR c0.category_code='satisfaction' THEN 'satisfaction_report'
          WHEN i.data_source='external_customer' OR c0.category_code='external_customer' THEN 'external_value'
          WHEN i.data_source='resource_input' OR c0.category_code='resourceInput' THEN 'resource_value'
          WHEN i.data_source IN ('business_value','running_efficiency') OR c0.category_code='businessValue' THEN 'collected_value'
          WHEN EXISTS (SELECT 1 FROM collection_source src WHERE src.enabled=1
            AND JSON_UNQUOTE(JSON_EXTRACT(src.config_json,'$.worker.categoryCode'))=c0.category_code) THEN 'collected_value'
          WHEN EXISTS (SELECT 1 FROM collection_row observed
            WHERE observed.metric_code_digest=UNHEX(SHA2(CONVERT(i.metric_code USING utf8mb4),256))
              AND BINARY JSON_UNQUOTE(JSON_EXTRACT(observed.data_json,'$.metric_code'))=BINARY i.metric_code
              AND JSON_UNQUOTE(JSON_EXTRACT(observed.data_json,'$._collection.category_code'))=c0.category_code) THEN 'collected_value'
          ELSE 'retained_snapshot' END AS collection_mode
        FROM metric_item i JOIN metric_category c0 ON c0.id=i.category_id LIMIT 100000000)
        """;
    // 读侧曾用 DISTINCT 子查询物化 collection_row 的指标编码，结果每次读取都要对整表做 JSON_EXTRACT，
    // 采集量上来后数据汇聚/运营报表直接 20s 超时（2026-11-17）。读侧改回与写侧同一份按 metric_code_digest
    // 走索引的 EXISTS 判定，语义完全一致。
    static final String READ_METRICS = METRICS;
    static final String RULE_JOINS = """
        LEFT JOIN metric_monitor_config c ON c.metric_id=m.id
        LEFT JOIN alarm_strategy alarm_rule ON alarm_rule.id=(SELECT s.id FROM alarm_strategy s
          WHERE s.target_type='metric' AND s.strategy_type='metric' AND s.target_id=m.id AND s.status=1
            AND s.alarm_threshold IS NOT NULL ORDER BY s.id LIMIT 1)
        LEFT JOIN alarm_strategy ticket_rule ON ticket_rule.id=(SELECT s.id FROM alarm_strategy s
          WHERE s.target_type='metric' AND s.strategy_type='metric' AND s.target_id=m.id AND s.status=1
            AND s.gongdan_threshold IS NOT NULL ORDER BY s.id LIMIT 1)
        """;
    static final String JOIN = """
        FROM collection_row r JOIN collection_file f ON f.id=r.batch_id
        CROSS JOIN %s m
        JOIN metric_category cat ON cat.id=m.category_id
        WHERE %s AND m.collection_mode<>'retained_snapshot' AND (
          JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$._collection.category_code'))=cat.category_code
          OR (JSON_EXTRACT(r.data_json,'$._collection') IS NULL AND (
            f.source_code=m.data_source
            OR EXISTS (SELECT 1 FROM collection_source src WHERE src.code=f.source_code
              AND JSON_UNQUOTE(JSON_EXTRACT(src.config_json,'$.worker.categoryCode'))=cat.category_code)
            OR (m.collection_mode='resource_value' AND f.source_code='resource_input')
            OR (m.collection_mode='external_value' AND f.source_code='external_customer'))))
        """.formatted(METRICS,MetricCodeIdentity.canonicalDigestMatchSql("m.metric_code","r.metric_code_canonical_digest"));
    List<Map<String,Object>> definitions(String category) { return definitions(category,null); }
    private List<Map<String,Object>> definitions(String category,Long metricId) {
        return jdbc.queryForList("""
            SELECT m.id AS metricId,m.metric_code AS metricCode,m.metric_name AS metricName,m.unit,
              m.sub_category_name AS subCategoryName,cat.category_name AS categoryName,cat.category_code AS categoryCode,
              m.collect_frequency AS collectFrequency,
              alarm_rule.alarm_threshold AS alarmThreshold,alarm_rule.alarm_condition AS alarmCondition,alarm_rule.alarm_enabled AS alarmStatus
            FROM %s m JOIN metric_category cat ON cat.id=m.category_id
            %s WHERE (? IS NULL OR cat.category_code=?) AND (? IS NULL OR m.id=?)
              AND m.status=1 AND m.collection_mode<>'retained_snapshot'
            ORDER BY m.id
            """.formatted(METRICS,RULE_JOINS),category,category,metricId,metricId);
    }
    public boolean supports(Long metricId) { return !definitions(null,metricId).isEmpty(); }
    public void validate(String category,List<Map<String,Object>> rows) {
        Map<String,Map<String,Object>> catalog=new HashMap<>();
        // Intake is already bound to the task's declared category. Allow its first
        // record even while a manual one-shot task is paused; publication becomes
        // source-backed after these validated records are inserted.
        var configured=jdbc.queryForList("""
            SELECT m.id AS metricId,m.metric_code AS metricCode,m.metric_name AS metricName,m.unit,
              m.sub_category_name AS subCategoryName,c.category_name AS categoryName
            FROM metric_item m JOIN metric_category c ON c.id=m.category_id
            WHERE c.category_code=? AND m.status=1
            """,category);
        for(var definition:configured)
            if(catalog.put(Objects.toString(definition.get("metricCode"),""),definition)!=null)
                throw new IllegalArgumentException("指标编码在当前大类不唯一");
        Set<String> seen=new HashSet<>();
        for(var row:rows) {
            String code=MetricCodeIdentity.canonical(category,Objects.toString(row.get("metric_code"),""));
            var definition=catalog.get(code);
            if(definition==null) throw new IllegalArgumentException("指标未启用、不属于任务大类或尚未配置直接取值规则（不会自动修改正式规则）："+code);
            for(String[] pair:new String[][]{{"metric_name","metricName"},{"unit","unit"},
                    {"sub_category_name","subCategoryName"},{"category_name","categoryName"}})
                if(row.get(pair[0])!=null && !Objects.equals(row.get(pair[0]),definition.get(pair[1])))
                    throw new IllegalArgumentException("名称、单位或分类与指标配置冲突："+code);
            var time=LocalDateTime.parse(Objects.toString(row.get("data_time"),"").replace(' ','T'));
            if(time.getNano()!=0) throw new IllegalArgumentException("当前指标存储仅支持秒级时间，不能静默截断："+code);
            row.put("data_time",time.toString());
            boolean dailyResource="resourceInput".equals(category)
                && code.matches("(requirementCount|totalRequirementDuration|avgRequirementDuration)(Boss|Bass)");
            if(!seen.add(code+"/"+(dailyResource?time.toLocalDate():time)))
                throw new IllegalArgumentException(dailyResource?"同一文件资源投入指标编码和数据日期重复："+code:"同一文件指标编码和时间重复："+code);
            Object value=row.get("metric_value");
            if(value==null) continue;
            if(!(value instanceof String)) throw new IllegalArgumentException("指标值须为十进制字符串或null："+code);
            BigDecimal decimal;
            try { decimal=new BigDecimal(value.toString()); }
            catch(NumberFormatException e) { throw new IllegalArgumentException("指标值不是有限十进制数："+code); }
            // Match DECIMAL(15,4), without inventing business limits or rounding source values.
            if(decimal.abs().compareTo(new BigDecimal("99999999999.9999"))>0 || decimal.stripTrailingZeros().scale()>4)
                throw new IllegalArgumentException("指标值超过当前存储范围或四位小数精度："+code);
        }
    }
    public Observation latest(Long metricId) {
        var identities=jdbc.queryForList("""
            SELECT i.metric_code AS metricCode,c.category_code AS categoryCode
            FROM metric_item i JOIN metric_category c ON c.id=i.category_id WHERE i.id=?
            """,metricId);
        return identities.isEmpty()?null:latest(metricId,identities.get(0));
    }

    private Observation latest(Long metricId,Map<String,Object> metric) {
        String code=(String)metric.get("metricCode");
        String category=(String)metric.get("categoryCode");
        var values=jdbc.query("""
            SELECT CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.metric_value')),'null') AS DECIMAL(15,4)) AS metric_value,
              CAST(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.data_time')),'T',' ') AS DATETIME) AS data_time
            """+JOIN+"""
              AND m.id=? AND r.metric_code_digest IN (UNHEX(SHA2(CONVERT(? USING utf8mb4),256)),
                                                     UNHEX(SHA2(CONVERT(? USING utf8mb4),256)))
            ORDER BY data_time DESC,f.collected_at DESC,f.id DESC,r.data_row_number DESC LIMIT 1
            """,(rs,n) -> new Observation(rs.getBigDecimal("metric_value"),rs.getObject("data_time",LocalDateTime.class)),
            metricId,code,MetricCodeIdentity.historicalCode(category,code));
        return values.isEmpty()?null:values.get(0);
    }
    public List<Map<String,Object>> raw(Long metricId,int page,int size) {
        int limit=Math.max(1,Math.min(size,200));
        return jdbc.queryForList("""
            SELECT r.data_json AS dataJson,f.id AS batchId,f.source_file AS sourceFile,f.collected_at AS collectedAt,
              JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.data_time')) AS dataTime
            """+JOIN+" AND m.id=? ORDER BY dataTime DESC,f.collected_at DESC,f.id DESC LIMIT ? OFFSET ?",
            metricId,limit,Math.max(0,page)*limit);
    }
    /** Latest observation per metric and source-data day, using the same code/category join as publishing. */
    public Map<String,Object> dailyOverview(String category,String subCategory) {
        return dailyOverview(category, subCategory, null, null);
    }

    /**
     * 对外部客户按日/按批次明细。
     *
     * <p>采集频率由采集任务决定（对外部客户小时级），所以时间维度必须能下钻到具体批次：
     * 传 {@code dataTime} 取该批次各指标的原值；只传 {@code date} 取该日各指标最后一批（原行为）；
     * 都不传返回所有历史日期的日末值。</p>
     */
    public Map<String,Object> dailyOverview(String category,String subCategory,String date,String dataTime) {
        // 先用本小类指标编码的 digest 剪枝：metric_code 匹配走 JSON 表达式无法用索引，
        // 不剪枝会退化成 collection_file × metric_item 的笛卡尔积（实测约 135 万次行读取）。
        // digest 的编码映射与 MetricCodeIdentity.historicalCode 保持一致（仅 businessValue 有历史别名）。
        String time="CAST(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.data_time')),'T',' ') AS DATETIME)";
        if (dataTime!=null && !dataTime.isBlank()) java.time.LocalDateTime.parse(dataTime.trim().replace(' ','T'));
        if (date!=null && !date.isBlank()) java.time.LocalDate.parse(date);
        // 按批次查询时去重键换成 data_time 本身：同一天的多个批次各自一行，不再折叠成日末值。
        String partition = dataTime!=null && !dataTime.isBlank() ? time : "DATE("+time+")";
        // SQL 里时间条件紧跟 m.sub_category_name 之后、早于 digest 子查询的参数，
        // 所以日期参数必须插在第三位，不能追加到末尾（否则会被当成 category_code）。
        String filter="";
        Object filterArg=null;
        if(dataTime!=null && !dataTime.isBlank()) {
            filter=" AND "+time+"=?";
            filterArg=dataTime.trim().replace('T',' ');
        } else if(date!=null && !date.isBlank()) {
            filter=" AND DATE("+time+")=?";
            filterArg=date;
        }
        List<Object> args=new ArrayList<>(List.of(category,subCategory));
        if(filterArg!=null) args.add(filterArg);
        args.add(category); args.add(subCategory);
        var rows=jdbc.queryForList("""
            WITH ranked AS (
              SELECT DATE_FORMAT(%s,'%%Y-%%m-%%d') AS businessDate,
                DATE_FORMAT(%s,'%%Y-%%m-%%d %%H:%%i:%%s') AS dataTime,
                DATE_FORMAT(f.collected_at,'%%Y-%%m-%%d %%H:%%i:%%s') AS importedAt,
                m.metric_code AS metricCode,m.sub_category_name AS metricType,m.metric_name AS metricName,
                CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.metric_value')),'null') AS DECIMAL(15,4)) AS metricValue,
                m.unit,f.source_file AS sourceFile,
                ROW_NUMBER() OVER (PARTITION BY m.id,%s
                  ORDER BY %s DESC,f.collected_at DESC,f.id DESC,r.data_row_number DESC) AS revision
              %s AND cat.category_code=? AND m.sub_category_name=? AND m.status=1%s
                AND r.metric_code_digest IN (
                  SELECT UNHEX(SHA2(CONVERT(CASE WHEN c.category_code='businessValue' AND i.metric_code='importantAlarmRatio'
                      THEN 'importantAlarmRate' ELSE i.metric_code END USING utf8mb4),256))
                  FROM metric_item i JOIN metric_category c ON c.id=i.category_id
                  WHERE c.category_code=? AND i.sub_category_name=? AND i.status=1)
            )
            SELECT businessDate,dataTime,importedAt,metricCode,metricType,metricName,metricValue,unit,sourceFile
            FROM ranked WHERE revision=1 ORDER BY businessDate DESC,dataTime DESC,metricCode
            """.formatted(time,time,partition,time,JOIN,filter),args.toArray());
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("rows",rows);
        result.put("metrics",definitions(category).stream()
            .filter(item -> Objects.equals(subCategory,item.get("subCategoryName"))).toList());
        // 供前端时间筛选使用：该小类有数据的日期，以及所选日期（缺省取最新一天）内的批次时间点。
        result.put("availableDates",subCategoryDates(category,subCategory,time));
        String timeDay=date;
        if((timeDay==null || timeDay.isBlank()) && dataTime!=null && !dataTime.isBlank())
            timeDay=dataTime.trim().replace('T',' ').substring(0,10);
        if(timeDay==null || timeDay.isBlank()) timeDay=latestSubCategoryDate(category,subCategory,time);
        result.put("availableTimes",timeDay==null || timeDay.isBlank()?List.of():subCategoryTimes(category,subCategory,timeDay,time));
        return result;
    }

    /** 某小类有采集数据的日期，倒序。 */
    private List<String> subCategoryDates(String category,String subCategory,String time) {
        return jdbc.queryForList("""
            SELECT DISTINCT DATE_FORMAT(%s,'%%Y-%%m-%%d') AS d %s AND cat.category_code=?
              AND m.sub_category_name=? AND m.status=1
              AND r.metric_code_digest IN (
                SELECT UNHEX(SHA2(CONVERT(CASE WHEN c.category_code='businessValue' AND i.metric_code='importantAlarmRatio'
                    THEN 'importantAlarmRate' ELSE i.metric_code END USING utf8mb4),256))
                FROM metric_item i JOIN metric_category c ON c.id=i.category_id
                WHERE c.category_code=? AND i.sub_category_name=? AND i.status=1)
            ORDER BY d DESC LIMIT 120
            """.formatted(time,JOIN),String.class,category,subCategory,category,subCategory);
    }

    /** 某小类最新有数据的日期。 */
    private String latestSubCategoryDate(String category,String subCategory,String time) {
        List<String> dates=subCategoryDates(category,subCategory,time);
        return dates.isEmpty()?null:dates.get(0);
    }

    /** 某小类在某一天内的批次时间点，倒序。 */
    private List<String> subCategoryTimes(String category,String subCategory,String day,String time) {
        return jdbc.queryForList("""
            SELECT DISTINCT DATE_FORMAT(%s,'%%Y-%%m-%%d %%H:%%i:%%s') AS t %s AND cat.category_code=?
              AND m.sub_category_name=? AND m.status=1 AND DATE(%s)=?
              AND r.metric_code_digest IN (
                SELECT UNHEX(SHA2(CONVERT(CASE WHEN c.category_code='businessValue' AND i.metric_code='importantAlarmRatio'
                    THEN 'importantAlarmRate' ELSE i.metric_code END USING utf8mb4),256))
                FROM metric_item i JOIN metric_category c ON c.id=i.category_id
                WHERE c.category_code=? AND i.sub_category_name=? AND i.status=1)
            ORDER BY t DESC LIMIT 200
            """.formatted(time,JOIN,time),String.class,category,subCategory,day,category,subCategory);
    }
    public long rawCount(Long metricId) { return jdbc.queryForObject("SELECT COUNT(*) "+JOIN+" AND m.id=?",Long.class,metricId); }
    public void publish(String category) {
        for(var metric:definitions(category)) publishOne(metric);
    }
    Optional<Boolean> publishIfSupported(Long metricId) {
        return definitions(null,metricId).stream().findFirst().map(this::publishOne);
    }
    private boolean publishOne(Map<String,Object> metric) {
        var observation=latest(((Number)metric.get("metricId")).longValue(),metric);
        if(observation==null) return false;
        BigDecimal threshold=(BigDecimal)metric.get("alarmThreshold");
        String condition=Objects.toString(metric.get("alarmCondition"),"");
        boolean known=Set.of(">",">=","<","<=","=","==").contains(condition);
        boolean enabled=observation.value()!=null && threshold!=null && known && Objects.equals(metric.get("alarmStatus"),1);
        int comparison=threshold==null || observation.value()==null?0:observation.value().compareTo(threshold);
        boolean exceeded=enabled && switch(condition) {
            case ">" -> comparison>0; case ">=" -> comparison>=0; case "<" -> comparison<0;
            case "<=" -> comparison<=0; case "=","==" -> comparison==0; default -> false;
        };
        String status=observation.value()==null?"缺数":threshold==null || !known?"待配置":!enabled?"告警停用":exceeded?"告警":"正常";
        jdbc.update("""
            INSERT INTO metric_realtime(metric_id,metric_value,data_time,is_exceed,exceed_count,status,create_time,update_time)
            VALUES (?,?,?,?,?,?,NOW(),NOW()) ON DUPLICATE KEY UPDATE
            metric_value=IF(data_time IS NULL OR data_time<=VALUES(data_time),VALUES(metric_value),metric_value),
            is_exceed=IF(data_time IS NULL OR data_time<=VALUES(data_time),VALUES(is_exceed),is_exceed),
            exceed_count=IF(data_time IS NULL OR data_time<=VALUES(data_time),VALUES(exceed_count),exceed_count),
            status=IF(data_time IS NULL OR data_time<=VALUES(data_time),VALUES(status),status),
            update_time=IF(data_time IS NULL OR data_time<=VALUES(data_time),NOW(),update_time),
            data_time=IF(data_time IS NULL OR data_time<=VALUES(data_time),VALUES(data_time),data_time)
            """,metric.get("metricId"),observation.value(),observation.dataTime(),exceeded?1:0,exceeded?1:0,status);
        return observation.value()!=null;
    }
}
