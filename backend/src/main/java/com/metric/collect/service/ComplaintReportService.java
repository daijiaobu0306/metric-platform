package com.metric.collect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
@ConditionalOnProperty(name = "collection.satisfaction.enabled", havingValue = "true", matchIfMissing = true)
public class ComplaintReportService {
    private static final Logger log = LoggerFactory.getLogger(ComplaintReportService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final BasePathProvider paths;
    @Autowired private ObjectProvider<CollectionPipeline> pipeline;
    @Autowired private SatisfactionAlarmValueService alarmValues;
    private List<JsonNode> catalog;

    public ComplaintReportService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper, BasePathProvider paths) {
        this.jdbc = jdbc; this.transactions = transactions; this.mapper = mapper; this.paths = paths;
    }
    private Path inbox() { return Path.of(paths.getBasePath(), "raw_data/import/satisfaction_report"); }
    private Path archive() { return Path.of(paths.getBasePath(), "raw_data/archive/satisfaction_report"); }
    @PostConstruct
    void initialize() throws IOException {
        try (var input = new ClassPathResource("local/satisfaction-final-catalog.json").getInputStream()) {
            catalog = new ArrayList<>();
            mapper.readTree(input).path("metrics").forEach(catalog::add);
            if (catalog.size() != 17 || catalog.stream().map(m -> m.path("code").asText()).distinct().count() != 17)
                throw new IllegalStateException("最终满意度清单必须为17个唯一指标");
        }
        Files.createDirectories(inbox()); Files.createDirectories(archive());
    }

    static String mappedType(JsonNode metric) {
        String group = metric.path("groupName").asText();
        if (group.equals("客服投诉工单处理流程")) return "在线客服";
        if (group.equals("综调工单处理流程")) return "综调"; // Provider mapping overrides erroneous source-scope text in catalog.
        String scope = metric.path("sourceScope").asText();
        if (group.equals("倒三角工单处理流程")) {
            if (scope.contains("剔除个人业务未完工")) return ComplaintReportParser.TRIANGLE.get(1);
            if (scope.contains("业务类型=个人业务未完工")) return ComplaintReportParser.TRIANGLE.get(2);
            if (scope.contains("事件性质=服务咨询")) return ComplaintReportParser.TRIANGLE.get(0);
        }
        throw new IllegalArgumentException("清单来源口径没有明确映射");
    }

    public Map<String, Object> overview(String batchId) {
        var batches = jdbc.queryForList("SELECT id,source_file AS sourceFile,business_date AS businessDate,date_basis AS dateBasis,imported_at AS importedAt FROM satisfaction_report_batch ORDER BY business_date DESC,imported_at DESC,id DESC LIMIT 100");
        String selected = batchId == null && !batches.isEmpty() ? batches.get(0).get("id").toString() : batchId;
        List<Map<String, Object>> rows = selected == null ? List.of() : jdbc.queryForList("""
            SELECT r.metric_code AS metricCode,r.source_row AS sourceRow,r.group_name AS groupName,r.source_scope AS sourceScope,
              r.metric_name AS metricName,r.metric_value AS metricValue,r.unit,r.value_status AS valueStatus,
              r.calculation_note AS calculationNote,r.source_cell AS sourceCell,r.definition,
              r.statistical_rule AS statisticalRule,r.threshold_note AS thresholdNote,
              m.id AS metricId,m.metric_code AS configuredCode,p.config_value AS alarmPolicy,
              mc.alarm_status AS alarmEnabled,mc.alarm_condition AS alarmCondition,mc.alarm_threshold AS alarmThreshold,
              mc.gongdan_condition AS gongdanCondition,mc.gongdan_threshold AS gongdanThreshold
            FROM satisfaction_report_result r
            LEFT JOIN metric_item m ON m.metric_code=r.metric_code AND m.data_source='satisfaction_report'
              AND m.category_id=(SELECT id FROM metric_category WHERE category_code='satisfaction')
            LEFT JOIN system_config p ON p.config_key=CONCAT('satisfaction.alarm.policy.',m.metric_code)
            LEFT JOIN metric_monitor_config mc ON mc.metric_id=m.id
            WHERE r.batch_id=? ORDER BY (r.source_row=0),r.source_row,CAST(SUBSTRING_INDEX(r.metric_code,'_',-1) AS UNSIGNED)
            """, selected);
        if (!rows.isEmpty()) {
            var dates = jdbc.queryForList("SELECT business_date FROM satisfaction_report_batch WHERE id=?", selected);
            if (!dates.isEmpty()) {
                LocalDate date = ((java.sql.Date) dates.get(0).get("business_date")).toLocalDate();
                for (var row : rows) enrichAlarmState(row, selected, date);
            }
        }
        var raw = selected == null ? List.of() : jdbc.queryForList("SELECT source_type AS sourceType,source_row AS sourceRow,metric_name AS metricName,raw_text AS rawText,metric_value AS metricValue,unit,source_rule AS sourceRule FROM satisfaction_report_raw WHERE batch_id=? ORDER BY source_row,metric_name", selected);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", selected); result.put("batches", batches); result.put("rows", rows); result.put("raw", raw);
        var states = jdbc.queryForList("SELECT last_error FROM collection_source WHERE code='satisfaction_report'");
        Object error = states.isEmpty() ? null : states.get(0).get("last_error");
        result.put("errors", error == null || error.toString().isBlank() ? List.of() : List.of(error.toString().split("\n")));
        result.put("inbox", inbox().toString()); result.put("archive", archive().toString());
        result.put("catalogVersion", "satisfaction-final-v1"); result.put("catalogMetricCount", catalog.size());
        result.put("today", LocalDate.now(ZONE)); return result;
    }

    private void enrichAlarmState(Map<String, Object> row, String batchId, LocalDate date) {
        if (row.get("metricId") == null) { row.put("status", "待配置"); return; }
        var metric = new com.metric.metric.entity.MetricItem();
        metric.setMetricCode(row.get("metricCode").toString());
        try {
            JsonNode policy = mapper.readTree(row.get("alarmPolicy") == null ? "{}" : row.get("alarmPolicy").toString());
            row.put("edgeThreshold", policy.has("edgeThreshold") ? policy.get("edgeThreshold").decimalValue() : null);
            row.put("monthlyThreshold", policy.has("monthlyThreshold") ? policy.get("monthlyThreshold").decimalValue() : null);
            row.put("workdayOnly", policy.path("workdayOnly").asBoolean());
            row.put("structuredRule", policy.has("monthlyThreshold"));
            var observations = alarmValues.observations(metric, (java.math.BigDecimal) row.get("alarmThreshold"),
                    (String) row.get("alarmCondition"), batchId, date);
            var daily = observations.get(0);
            boolean breached = observations.stream().anyMatch(SatisfactionAlarmValueService.Observation::breached);
            String status = row.get("metricValue") == null ? "无数据"
                    : !Integer.valueOf(1).equals(row.get("alarmEnabled")) ? "告警停用"
                    : daily.threshold() == null && policy.path("workdayOnly").asBoolean() && alarmValues.workingDay(date) == null ? "待日历"
                    : row.get("alarmThreshold") == null ? "待配置" : breached ? "告警" : "正常";
            row.put("status", status);
            Integer exceed = null;
            if ("告警".equals(status)) exceed = 1;
            else if ("正常".equals(status)) exceed = 0;
            row.put("isExceed", exceed);
            row.put("effectiveAlarmThreshold", daily.threshold());
            row.put("monthlyEvaluated", observations.size() > 1);
            row.put("alarmRuleSummary", (policy.path("workdayOnly").asBoolean() ? "工作日；" : "")
                    + "日阈值" + row.get("alarmCondition") + row.get("alarmThreshold")
                    + (policy.has("edgeThreshold") ? "；1—3日、30—31日阈值>" + policy.get("edgeThreshold") : "")
                    + (policy.has("monthlyThreshold") ? "；月累计>" + policy.get("monthlyThreshold") : ""));
        } catch (IOException e) { throw new IllegalStateException("满意度告警规则无效", e); }
    }
}
