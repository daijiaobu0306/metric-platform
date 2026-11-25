package com.metric.metric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Daily source values remain unchanged. Only reporting combines the confirmed daily flows. */
@Service
@Transactional(readOnly = true)
public class ResourceInputPeriodService {
    private final NamedParameterJdbcTemplate sql;
    private final MetricPeriodQueryService definitions;
    public ResourceInputPeriodService(JdbcTemplate jdbc, MetricPeriodQueryService definitions) {
        this.sql = new NamedParameterJdbcTemplate(jdbc);
        this.definitions = definitions;
    }

    public record Range(LocalDate start, LocalDate end) {}
    public static Range range(LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (end == null) end = today;
        if (start == null) start = end.minusMonths(1);
        if (start.isAfter(end)) throw new IllegalArgumentException("开始日期不能晚于结束日期");
        return new Range(start, end);
    }

    public List<Map<String,Object>> catalog() {
        return sql.queryForList("""
            SELECT m.id,m.metric_code AS code,m.metric_name AS name,m.sub_category_name AS subcategory,m.unit
            FROM metric_item m JOIN metric_category c ON c.id=m.category_id
            WHERE c.category_code='resourceInput' AND m.status=1 ORDER BY m.sub_category_name,m.id
            """,Map.of());
    }

    private List<Map<String,Object>> daily(List<Map<String,Object>> metrics, Range range) {
        if (metrics.isEmpty()) return List.of();
        var params = new HashMap<String,Object>();
        params.put("ids",metrics.stream().map(m -> m.get("id")).toList());
        params.put("start",range.start().atStartOfDay());
        params.put("end",range.end().plusDays(1).atStartOfDay());
        return sql.queryForList("""
            WITH incoming AS (
              SELECT m.id AS metricId,m.category_id AS categoryId,m.metric_code AS metricCode,m.metric_name AS metricName,m.unit,
                m.sub_category_name AS subCategoryName,f.source_file AS sourceFile,f.collected_at AS importedAt,
                f.id AS batchId,r.data_row_number AS sourceRow,
                CAST(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.data_time')),'T',' ') AS DATETIME) AS dataTime,
                NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.metric_value')),'null') AS rawValue,
                /* 采集行缺字段（JSON null）视为“未提供”，不算冲突 */
                CASE WHEN
                  (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.metric_name')),'null') IS NOT NULL
                    AND BINARY NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.metric_name')),'null')<>BINARY m.metric_name)
                  OR (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.unit')),'null') IS NOT NULL
                    AND NOT(BINARY NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.unit')),'null')<=>BINARY m.unit))
                  OR (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.sub_category_name')),'null') IS NOT NULL
                    AND NOT(BINARY NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.sub_category_name')),'null')<=>BINARY m.sub_category_name))
                  OR (NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.category_name')),'null') IS NOT NULL
                    AND BINARY NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.category_name')),'null')<>BINARY c.category_name)
                THEN 1 ELSE 0 END AS conflict
              FROM metric_item m JOIN metric_category c ON c.id=m.category_id
              JOIN collection_row r ON r.metric_code_digest=UNHEX(SHA2(CONVERT(m.metric_code USING utf8mb4),256))
                AND BINARY JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.metric_code'))=BINARY m.metric_code
              JOIN collection_file f ON f.id=r.batch_id
              WHERE m.id IN (:ids) AND c.category_code='resourceInput' AND m.status=1
                AND (JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$._collection.category_code'))='resourceInput'
                  OR (JSON_EXTRACT(r.data_json,'$._collection') IS NULL AND f.source_code='resource_input'))
            ), ranked AS (
              SELECT *,ROW_NUMBER() OVER(PARTITION BY metricId,DATE(dataTime)
                ORDER BY importedAt DESC,batchId DESC,sourceRow DESC) AS revision
              FROM incoming WHERE dataTime>=:start AND dataTime<:end
            ) SELECT * FROM ranked WHERE revision=1 ORDER BY dataTime DESC,metricId
            """,params);
    }

    public Map<String,Object> records(long metricId, LocalDate start, LocalDate end, String keyword, int page, int size) {
        var metric = catalog().stream().filter(m -> ((Number)m.get("id")).longValue()==metricId).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("指标不属于资源投入或未启用"));
        var range = range(start,end);
        var rows = new ArrayList<Map<String,Object>>();
        for (var source : daily(List.of(metric),range)) {
            var row = new LinkedHashMap<String,Object>();
            row.put("数据日期",day(source)); row.put("指标编码",source.get("metricCode"));
            row.put("指标名称",source.get("metricName")); row.put("指标值",source.get("rawValue"));
            row.put("单位",source.get("unit")); row.put("指标小类",source.get("subCategoryName"));
            row.put("入库时间",Objects.toString(source.get("importedAt"),"")); row.put("来源文件",source.get("sourceFile"));
            row.put("数据状态",number(source,"conflict")>0?"配置冲突":value(source)==null?"缺数或数值无效":"已采集");
            if (keyword==null || keyword.isBlank() || row.values().stream().anyMatch(v -> Objects.toString(v,"").contains(keyword.trim()))) rows.add(row);
        }
        int limit=Math.max(1,Math.min(size,200));
        int from=(int)Math.min((long)(Math.max(1,page)-1)*limit,rows.size());
        return Map.of("rows",rows.subList(from,Math.min(from+limit,rows.size())),"total",rows.size(),
            "columns",List.of("数据日期","指标编码","指标名称","指标值","单位","指标小类","入库时间","来源文件","数据状态"),
            "startDate",range.start().toString(),"endDate",range.end().toString(),
            "note","按原始数据日期查询；同指标同一天取最近导入版本，未投递日期不补零。");
    }

    public List<Map<String,Object>> report(LocalDate start, LocalDate end, String subcategory) {
        var range=range(start,end);
        var catalog=catalog().stream().filter(m -> subcategory==null || subcategory.isBlank() || subcategory.equals(m.get("subcategory"))).toList();
        if(catalog.isEmpty()) return List.of();
        var daily=daily(catalog,range);
        var ids=catalog.stream().map(m -> ((Number)m.get("id")).longValue()).toList();
        var result=definitions.observationCatalog(ids);
        for(var metric:result) {
            String code=Objects.toString(metric.get("metricCode"),"");
            String suffix=code.endsWith("Boss")?"Boss":code.endsWith("Bass")?"Bass":null;
            var own=daily.stream().filter(r -> number(r,"metricId")==number(metric,"metricId")).toList();
            BigDecimal total=null;
            String state="周期内无数据";
            String formula="统计口径未配置";
            if(suffix!=null && (code.equals("requirementCount"+suffix) || code.equals("totalRequirementDuration"+suffix))) {
                total=sum(own); formula="区间内每日指标值之和";
                state=own.isEmpty()?"周期内无数据":total==null?"存在缺数或冲突，无法汇总":"已计算";
            } else if(suffix!=null && code.equals("avgRequirementDuration"+suffix)) {
                final String group=suffix;
                var counts=daily.stream().filter(r -> number(r,"categoryId")==number(metric,"categoryId") && Objects.equals(r.get("metricCode"),"requirementCount"+group)).toList();
                var hours=daily.stream().filter(r -> number(r,"categoryId")==number(metric,"categoryId") && Objects.equals(r.get("metricCode"),"totalRequirementDuration"+group)).toList();
                BigDecimal n=sum(counts), h=sum(hours);
                formula="区间总工时 ÷ 区间需求数量（四位小数）";
                if(counts.isEmpty() && hours.isEmpty()) state="周期内无数据";
                else if(n==null || h==null || !days(counts).equals(days(hours))) state="数量与工时日期不齐或存在缺数，无法计算";
                else if(n.signum()==0) state="需求数量为零，平均工时不适用";
                else { total=h.divide(n,4,RoundingMode.HALF_UP); state="已计算"; }
            }
            String text=total==null?null:total.stripTrailingZeros().toPlainString();
            metric.put("id",metric.get("metricId")); metric.put("metricDefinition",metric.get("definition"));
            metric.put("metricValue",text); metric.put("currentValue",text);
            metric.put("dataTime",own.isEmpty()?null:day(own.get(0)));
            metric.put("dataStatus",state); metric.put("status",total==null?state:metric.get("alarmThreshold")==null?"待配置":"待判定");
            metric.put("isExceed",null); metric.put("exceedCount",null); metric.put("healthScore",null);
            metric.put("sourceNotice",formula+"；未投递日期不补零，不回写每日采集值。");
            metric.put("periodStart",range.start().toString()); metric.put("periodEnd",range.end().toString());
            metric.put("periodRecords",own.size()); metric.put("calculationNote",formula);
        }
        return result;
    }
    private static long number(Map<String,Object> row,String key) { return row.get(key) instanceof Number n?n.longValue():0; }
    private static String day(Map<String,Object> row) { return row.get("dataTime").toString().substring(0,10); }
    private static Set<String> days(List<Map<String,Object>> rows) {
        var days=new HashSet<String>(); rows.forEach(row -> days.add(day(row))); return days;
    }
    private static BigDecimal value(Map<String,Object> row) {
        if(number(row,"conflict")>0 || row.get("rawValue")==null) return null;
        try { return new BigDecimal(row.get("rawValue").toString()); } catch(NumberFormatException e) { return null; }
    }
    private static BigDecimal sum(List<Map<String,Object>> rows) {
        if(rows.isEmpty()) return null;
        BigDecimal result=BigDecimal.ZERO;
        for(var row:rows) { var n=value(row); if(n==null) return null; result=result.add(n); }
        return result;
    }
}
