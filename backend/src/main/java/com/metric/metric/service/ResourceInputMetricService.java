package com.metric.metric.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Values are supplied by the source file. No demand counting, summing or averaging occurs here. */
@Service
public class ResourceInputMetricService {
    public static final Set<String> CODES = Set.of("requirementCount", "avgRequirementDuration", "totalRequirementDuration");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ResourceInputMetricService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }
    public record Observation(BigDecimal value, LocalDateTime dataTime) {}
    public static Map<String,Observation> readValues(List<Map<String,Object>> rows) {
        Map<String,Observation> result = new LinkedHashMap<>();
        for (var row : rows) {
            String code = Objects.toString(row.get("metric_code"), "");
            if (!CODES.contains(code) || result.containsKey(code))
                throw new IllegalArgumentException("指标编码不属于资源投入或重复：" + code);
            if (row.get("metric_value") == null || row.get("data_time") == null)
                throw new IllegalArgumentException("指标值和数据时间不能为空：" + code);
            BigDecimal value = new BigDecimal(row.get("metric_value").toString());
            if (value.signum() < 0 || value.stripTrailingZeros().scale() > 4 || value.compareTo(new BigDecimal("99999999999.9999")) > 0)
                throw new IllegalArgumentException("指标值须为非负数、最多4位小数且不超出存储范围：" + code);
            if ("requirementCount".equals(code) && value.stripTrailingZeros().scale() > 0)
                throw new IllegalArgumentException("需求数量须为整数");
            result.put(code, new Observation(value, LocalDateTime.parse(row.get("data_time").toString().replace(' ', 'T'))));
        }
        if (!result.keySet().equals(CODES)) throw new IllegalArgumentException("文件须完整提供资源投入的3项指标值");
        return result;
    }
    private List<Map<String,Object>> rows(String batch) {
        return jdbc.queryForList("SELECT data_json FROM collection_row WHERE batch_id=? ORDER BY data_row_number", batch)
            .stream().map(row -> {
                try { return mapper.readValue(row.get("data_json").toString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {}); }
                catch (Exception e) { throw new IllegalStateException("资源投入采集数据损坏", e); }
            }).toList();
    }
    public List<Map<String,Object>> batches() {
        // Old demand-detail batches remain traceable in collection records, but are not metric-value batches.
        return jdbc.queryForList("SELECT f.id,f.source_file AS sourceFile,f.business_date AS businessDate,f.row_count AS rowCount,f.collected_at AS collectedAt,f.archived FROM collection_file f WHERE f.source_code='resource_input' AND EXISTS (SELECT 1 FROM collection_row r WHERE r.batch_id=f.id AND JSON_CONTAINS_PATH(r.data_json,'one','$.metric_code')) ORDER BY f.business_date DESC,f.collected_at DESC,f.id DESC LIMIT 100");
    }
    private List<Map<String,Object>> definitions() {
        return jdbc.queryForList("SELECT m.id AS metricId,cat.category_name AS categoryName,"
            + "m.sub_category_name AS subCategoryName,m.metric_code AS metricCode,m.metric_name AS metricName,"
            + "m.metric_definition AS metricDefinition,m.unit,m.collect_frequency AS collectFrequency,"
            + "c.alarm_threshold AS alarmThreshold,c.alarm_condition AS alarmCondition,c.alarm_status AS alarmStatus "
            + "FROM metric_item m JOIN metric_category cat ON cat.id=m.category_id "
            + "LEFT JOIN metric_monitor_config c ON c.metric_id=m.id "
            + "WHERE cat.category_code='resourceInput' AND m.metric_code IN ('requirementCount','avgRequirementDuration','totalRequirementDuration') AND m.status=1 "
            + "ORDER BY FIELD(m.metric_code,'requirementCount','avgRequirementDuration','totalRequirementDuration'),m.id");
    }
    public void validateValues(List<Map<String,Object>> data) {
        readValues(data);
        var definitions = definitions();
        if (definitions.size() != 3) throw new IllegalArgumentException("资源投入指标配置不完整");
        for (var row : data) {
            var definition = definitions.stream().filter(item -> Objects.equals(item.get("metricCode"), row.get("metric_code"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("资源投入指标配置不匹配"));
            if (!Objects.equals(definition.get("metricName"), row.get("metric_name")) || !Objects.equals(definition.get("unit"), row.get("unit")))
                throw new IllegalArgumentException("指标名称或单位与统一配置不符：" + row.get("metric_code"));
        }
    }
    public Map<String,Object> overview(String batch) {
        var batches = batches();
        if (batch == null || batch.isBlank()) batch = batches.isEmpty() ? null : batches.get(0).get("id").toString();
        if (batch != null && jdbc.queryForObject("SELECT COUNT(*) FROM collection_file f WHERE f.id=? AND f.source_code='resource_input' AND EXISTS (SELECT 1 FROM collection_row r WHERE r.batch_id=f.id AND JSON_CONTAINS_PATH(r.data_json,'one','$.metric_code'))", Integer.class, batch) != 1)
            throw new IllegalArgumentException("资源投入指标值批次不存在");
        var result = new LinkedHashMap<String,Object>();
        result.put("batchId", batch); result.put("batches", batches);
        result.put("rows", batch == null ? List.of() : metricRows(rows(batch)));
        return result;
    }
    private List<Map<String,Object>> metricRows(List<Map<String,Object>> data) {
        var values = readValues(data);
        var metrics = definitions();
        for (var metric : metrics) {
            var value = values.get(metric.get("metricCode").toString());
            if (value == null) throw new IllegalStateException("未知资源投入指标编码");
            metric.put("metricValue", value.value()); metric.put("dataTime", value.dataTime());
        }
        return metrics;
    }
    @Transactional
    public boolean refresh() {
        var batches = batches();
        if (batches.isEmpty()) return false;
        for (var item : metricRows(rows(batches.get(0).get("id").toString()))) {
            BigDecimal value = (BigDecimal) item.get("metricValue");
            BigDecimal threshold = (BigDecimal) item.get("alarmThreshold");
            boolean enabled = threshold != null && Objects.equals(item.get("alarmStatus"), 1);
            boolean exceeded = enabled && breached(value, threshold, Objects.toString(item.get("alarmCondition"), ""));
            String state = threshold == null ? "待配置" : !enabled ? "告警停用" : exceeded ? "告警" : "正常";
            jdbc.update("INSERT INTO metric_realtime(metric_id,metric_value,data_time,is_exceed,exceed_count,status,create_time,update_time) VALUES (?,?,?,?,?,?,NOW(),NOW()) ON DUPLICATE KEY UPDATE metric_value=VALUES(metric_value),data_time=VALUES(data_time),is_exceed=VALUES(is_exceed),exceed_count=VALUES(exceed_count),status=VALUES(status),update_time=NOW()",
                item.get("metricId"), value, item.get("dataTime"), exceeded ? 1 : 0, exceeded ? 1 : 0, state);
        }
        return true;
    }
    private static boolean breached(BigDecimal value, BigDecimal threshold, String condition) {
        int n = value.compareTo(threshold);
        return switch (condition) { case ">" -> n > 0; case ">=" -> n >= 0; case "<" -> n < 0; case "<=" -> n <= 0; case "=" -> n == 0; default -> false; };
    }
}
