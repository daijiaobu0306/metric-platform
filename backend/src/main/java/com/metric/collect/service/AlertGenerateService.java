package com.metric.collect.service;

import com.metric.collect.entity.*;
import com.metric.collect.repository.*;
import com.metric.alarm.entity.Alarm;
import com.metric.alarm.entity.AlarmStrategy;
import com.metric.alarm.entity.StrategyRule;
import com.metric.alarm.service.StrategyRuleService;
import com.metric.alarm.entity.AlarmTriggerHistory;
import com.metric.alarm.entity.RawDataAlarmConfig;
import com.metric.alarm.repository.AlarmRepository;
import com.metric.alarm.repository.AlarmStrategyRepository;
import com.metric.alarm.repository.RawDataAlarmConfigRepository;
import com.metric.alarm.repository.TriggerHistoryRepository;
import com.metric.alarm.service.AlarmService;
import com.metric.alarm.service.ShieldRuleService;
import com.metric.gongdan.entity.Gongdan;
import com.metric.gongdan.repository.GongdanRepository;
import com.metric.gongdan.service.GongdanService;
import com.metric.metric.entity.CategoryHealthHistory;
import com.metric.metric.entity.MetricHealthHistory;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.repository.CategoryHealthHistoryRepository;
import com.metric.config.service.ConfigService;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.MetricHealthHistoryRepository;
import com.metric.metric.repository.MetricItemRepository;
import com.metric.metric.repository.MetricRealtimeRepository;
import com.metric.metric.repository.SubCategoryHealthHistoryRepository;
import com.metric.metric.repository.SystemInfoRepository;
import com.metric.metric.service.WeightConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AlertGenerateService {

    @Autowired
    private DataSource dataSource;

    // 扫描错误收集（最近一次扫描的错误，供前端展示）
    private volatile List<ScanError> lastScanErrors = Collections.emptyList();
    private volatile LocalDateTime lastScanTime = null;
    private final List<ScanError> currentScanErrors = new ArrayList<>();

    public static class ScanError {
        private final String phase;
        private final String message;
        private final LocalDateTime time;
        public ScanError(String phase, String message, LocalDateTime time) {
            this.phase = phase; this.message = message; this.time = time;
        }
        public String getPhase() { return phase; }
        public String getMessage() { return message; }
        public LocalDateTime getTime() { return time; }
    }

    public List<ScanError> getLastScanErrors() { return lastScanErrors; }
    public LocalDateTime getLastScanTime() { return lastScanTime; }

    @Autowired
    private com.metric.metric.service.MetricSubCategoryService metricSubCategoryService;

    @Autowired
    private MetricItemRepository metricItemRepository;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private MetricRealtimeRepository metricRealtimeRepository;

    @Autowired
    private GongdanRepository gongdanRepository;

    @Autowired
    private GongdanService gongdanService;

    @Autowired
    private com.metric.gongdan.service.GongdanIntegrationService gongdanIntegrationService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private SatisfactionAlarmValueService satisfactionAlarmValues;

    /** system_config key: 健康度/权重默认 systemId（V20260914110001 新增，修复前硬编码 1L fallback）*/
    private static final String CONFIG_KEY_DEFAULT_SYSTEM_ID = "metric.health.default-system-id";

    @Autowired
    private AlarmStrategyRepository strategyRepository;

    @Autowired
    private RawDataAlarmConfigRepository rawDataAlarmConfigRepository;

    @Autowired
    private TriggerHistoryRepository triggerHistoryRepository;

    @Autowired
    private MetricHealthHistoryRepository metricHealthHistoryRepository;

    @Autowired
    private SubCategoryHealthHistoryRepository subCategoryHealthHistoryRepository;

    @Autowired
    private CategoryHealthHistoryRepository categoryHealthHistoryRepository;

    @Autowired
    private MetricCategoryRepository metricCategoryRepository;

    @Autowired
    private SystemInfoRepository systemInfoRepository;

    @Autowired
    private WeightConfigService weightConfigService;

    @Autowired
    private ShieldRuleService shieldRuleService;

    /**
     * 检查目标是否被屏蔽规则覆盖
     * 优先级：metricId > categoryId > systemId
     */
    private boolean isTargetShielded(Long systemId, Long categoryId, Long metricId) {
        try {
            return shieldRuleService.isAlarmShielded(systemId, categoryId, metricId);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查策略目标是否被屏蔽
     */
    private boolean isStrategyTargetShielded(AlarmStrategy strategy) {
        if (strategy.getTargetType() == null || strategy.getTargetId() == null) return false;
        try {
            switch (strategy.getTargetType()) {
                case "metric":
                    MetricItem metric = metricItemRepository.findById(strategy.getTargetId()).orElse(null);
                    if (metric == null) return false;
                    Long sysId = null;
                    if (metric.getCategoryId() != null) {
                        sysId = metricCategoryRepository.findById(metric.getCategoryId())
                            .map(cat -> cat.getSystemId()).orElse(null);
                    }
                    return isTargetShielded(sysId, metric.getCategoryId(), metric.getId());
                case "category":
                    return isTargetShielded(null, strategy.getTargetId(), null);
                case "system":
                    return isTargetShielded(strategy.getTargetId(), null, null);
                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 统一扫描入口（不用大事务，每个子扫描独立，互不影响）
     */
    public int scanAndGenerateAlerts() {
        int totalGenerated = 0;
        currentScanErrors.clear();

        System.out.println("=== 开始告警扫描 ===");

        // 1. 原始数据告警扫描
        try {
            totalGenerated += scanRawDataAlarms();
        } catch (Exception e) {
            System.err.println("原始数据告警扫描出错: " + e.getMessage());
            currentScanErrors.add(new ScanError("原始数据告警", e.getMessage(), LocalDateTime.now()));
        }

        // 2. 指标值告警扫描
        try {
            totalGenerated += scanMetricValueAlarms();
        } catch (Exception e) {
            System.err.println("指标值告警扫描出错: " + e.getMessage());
            currentScanErrors.add(new ScanError("指标值告警", e.getMessage(), LocalDateTime.now()));
        }

        // 3. 健康度告警扫描
        try {
            totalGenerated += scanHealthScoreAlarms();
        } catch (Exception e) {
            System.err.println("健康度告警扫描出错: " + e.getMessage());
            currentScanErrors.add(new ScanError("健康度告警", e.getMessage(), LocalDateTime.now()));
        }

        lastScanErrors = new ArrayList<>(currentScanErrors);
        lastScanTime = LocalDateTime.now();
        System.out.println("=== 告警扫描完成，共生成 " + totalGenerated + " 条 ===");
        return totalGenerated;
    }

    // ==================== 原始数据告警处理 ====================

    @Autowired
    private com.metric.alarm.service.RawStrategyService rawStrategies;

    private int scanRawDataAlarms() {
        int generated = scanRawDataAlarms(null);
        for (AlarmStrategy strategy : strategyRepository.findByStatusAndStrategyType(1, "raw")) {
            try { generated += rawStrategies.scan(strategy.getId()); }
            catch (Exception error) {
                currentScanErrors.add(new ScanError("原始数据策略-" + strategy.getId(), error.getMessage(), LocalDateTime.now()));
            }
        }
        return generated;
    }

    @org.springframework.transaction.annotation.Transactional
    public int checkRawDataConfig(Long configId) { return scanRawDataAlarms(configId); }

    private int scanRawDataAlarms(Long configId) {
        int count = 0;

        // 获取所有启用的原始数据告警配置
        List<RawDataAlarmConfig> configs = configId == null ? rawDataAlarmConfigRepository.findByAlarmStatus(1)
                : rawDataAlarmConfigRepository.findById(configId).map(List::of).orElse(List.of());
        if (configs == null || configs.isEmpty()) {
            return 0;
        }

        System.out.println("开始扫描原始数据告警，共 " + configs.size() + " 个配置");

        for (RawDataAlarmConfig config : configs) {
            if (rawStrategies.unified(config)) continue;
            try {
                List<Map<String, Object>> rawDataList = queryRawData(config);

                for (Map<String, Object> row : rawDataList) {
                    // 获取数据源ID
                    Long sourceId = getSourceId(row, config);
                    if (sourceId == null) continue;

                    // 获取维度标识
                    String dimensionKey = getDimensionKey(row, config);

                    // 匹配阈值
                    ThresholdMatch match = matchThreshold(config, dimensionKey, sourceId);
                    if (match == null) continue;

                    // 获取指标值
                    BigDecimal value = getMetricValue(row, config);
                    if (value == null) continue;

                    // 条件判断
                    if (!checkCondition(value, match.condition, match.threshold)) {
                        continue;
                    }

                    // 检查连续触发
                    if (config.getStrategy() != null && config.getStrategy().getConsecutiveTimes() != null && config.getStrategy().getConsecutiveTimes() > 1) {
                        if (!checkConsecutiveTriggerForRawData(config, sourceId, value, match.threshold)) {
                            continue;
                        }
                    }

                    // 去重检查
                    if (isRawDataAlarmExists(config, sourceId)) continue;

                    // 屏蔽检查：检查策略目标是否被屏蔽
                    if (config.getStrategy() != null && isStrategyTargetShielded(config.getStrategy())) {
                        continue;
                    }

                    // 创建告警
                    Alarm alarm = createRawDataAlarm(config, row, value, match.threshold, match.level);
                    alarmService.save(alarm);
                    count++;
                }
            } catch (Exception e) {
                System.err.println("处理原始数据告警配置 " + config.getId() + " 出错: " + e.getMessage());
                currentScanErrors.add(new ScanError("原始数据告警-配置" + config.getId(), e.getMessage(), LocalDateTime.now()));
                e.printStackTrace();
            }
        }

        if (count > 0) {
            System.out.println("原始数据告警扫描完成，生成 " + count + " 条告警");
        }
        return count;
    }

    private List<Map<String, Object>> queryRawData(RawDataAlarmConfig config) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();

        String tableName = config.getTableName();
        String metricField = config.getMetricField();
        String filterField = config.getFilterField();
        String filterValue = config.getFilterValue();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(tableName).append(" WHERE 1=1");

        if (filterField != null && !filterField.isEmpty() && filterValue != null && !filterValue.isEmpty()) {
            sql.append(" AND ").append(filterField).append(" = ?");
        }

        // 只查询最近24小时的数据
        sql.append(" AND insert_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR)");

        sql.append(" ORDER BY insert_time DESC LIMIT 1000");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            if (filterField != null && !filterField.isEmpty() && filterValue != null && !filterValue.isEmpty()) {
                ps.setString(1, filterValue);
            }

            ResultSet rs = ps.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();

            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                result.add(row);
            }
        }

        return result;
    }

    private Long getSourceId(Map<String, Object> row, RawDataAlarmConfig config) {
        String idField = config.getSourceIdField();
        if (idField == null || idField.isEmpty()) {
            idField = "id";
        }
        Object value = row.get(idField);
        if (value == null) return null;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return ((Integer) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getDimensionKey(Map<String, Object> row, RawDataAlarmConfig config) {
        String filterField = config.getFilterField();
        if (filterField == null || filterField.isEmpty()) {
            return null;
        }
        Object value = row.get(filterField);
        return value != null ? value.toString() : null;
    }

    private BigDecimal getMetricValue(Map<String, Object> row, RawDataAlarmConfig config) {
        String metricField = config.getMetricField();
        Object value = row.get(metricField);
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return BigDecimal.valueOf(((Number) value).doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 阈值匹配（对象级 > 维度级 > 全局）====================

    private ThresholdMatch matchThreshold(RawDataAlarmConfig config, String dimensionKey, Long sourceId) {
        ThresholdMatch match = new ThresholdMatch();

        // 1. 优先：对象级阈值
        String objectThresholds = config.getObjectThresholds();
        if (objectThresholds != null && !objectThresholds.isEmpty() && sourceId != null) {
            // 解析 JSON 查找对象级阈值
            ThresholdMatch objectMatch = parseObjectThreshold(objectThresholds, sourceId);
            if (objectMatch != null) {
                return objectMatch;
            }
        }

        // 2. 其次：维度级阈值
        String dimensionThresholds = config.getDimensionThresholds();
        if (dimensionThresholds != null && !dimensionThresholds.isEmpty() && dimensionKey != null) {
            ThresholdMatch dimMatch = parseDimensionThreshold(dimensionThresholds, dimensionKey);
            if (dimMatch != null) {
                return dimMatch;
            }
        }

        // 3. 最后：全局阈值
        if (config.getGlobalThreshold() != null) {
            match.threshold = config.getGlobalThreshold();
            match.condition = config.getGlobalCondition() != null ? config.getGlobalCondition() : ">";
            match.level = "global";
            return match;
        }

        return null;
    }

    private ThresholdMatch parseObjectThreshold(String json, Long sourceId) {
        try {
            // 简单的 JSON 解析
            if (json.contains("\"sourceId\":" + sourceId)) {
                ThresholdMatch match = new ThresholdMatch();
                match.level = "object";

                // 提取 threshold
                int thresholdIdx = json.indexOf("\"threshold\"");
                if (thresholdIdx >= 0) {
                    int start = json.indexOf(":", thresholdIdx) + 1;
                    int end = json.indexOf(",", start);
                    if (end < 0) end = json.indexOf("}", start);
                    String thresholdStr = json.substring(start, end).trim();
                    match.threshold = new BigDecimal(thresholdStr);
                }

                // 提取 condition
                String condition = extractJsonString(json, "condition");
                match.condition = condition != null ? condition : ">";

                return match;
            }
        } catch (Exception e) {
            System.err.println("解析对象阈值JSON失败: " + e.getMessage());
        }
        return null;
    }

    private ThresholdMatch parseDimensionThreshold(String json, String dimensionKey) {
        try {
            if (json.contains("\"filterValue\":\"" + dimensionKey + "\"")) {
                ThresholdMatch match = new ThresholdMatch();
                match.level = "dimension";

                int thresholdIdx = json.indexOf("\"threshold\"");
                if (thresholdIdx >= 0) {
                    int start = json.indexOf(":", thresholdIdx) + 1;
                    int end = json.indexOf(",", start);
                    if (end < 0) end = json.indexOf("}", start);
                    String thresholdStr = json.substring(start, end).trim();
                    match.threshold = new BigDecimal(thresholdStr);
                }

                String condition = extractJsonString(json, "condition");
                match.condition = condition != null ? condition : ">";

                return match;
            }
        } catch (Exception e) {
            System.err.println("解析维度阈值JSON失败: " + e.getMessage());
        }
        return null;
    }

    private String extractJsonString(String json, String key) {
        try {
            String searchKey = "\"" + key + "\"";
            int idx = json.indexOf(searchKey);
            if (idx >= 0) {
                int start = json.indexOf("\"", idx + searchKey.length()) + 1;
                int end = json.indexOf("\"", start);
                return json.substring(start, end);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean checkCondition(BigDecimal value, String condition, BigDecimal threshold) {
        if (value == null || threshold == null) return false;
        int cmp = value.compareTo(threshold);
        switch (condition) {
            case ">":  return cmp > 0;
            case "<":  return cmp < 0;
            case "=":  return cmp == 0;
            case ">=": return cmp >= 0;
            case "<=": return cmp <= 0;
            default:   return cmp > 0;
        }
    }

    // ==================== 连续触发检查 ====================

    private boolean checkConsecutiveTriggerForRawData(RawDataAlarmConfig config, Long sourceId,
                                                       BigDecimal currentValue, BigDecimal threshold) {
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(30);

        List<AlarmTriggerHistory> recentHistory = triggerHistoryRepository
                .findByStrategyAndSource(
                        config.getStrategyId(), config.getTableName(), sourceId, windowStart);

        // 记录当前触发
        saveTriggerHistory(config.getStrategyId(), null, config.getTableName(), sourceId, currentValue, threshold);

        // 判断连续超过阈值的次数
        int consecutiveCount = 1;
        for (AlarmTriggerHistory history : recentHistory) {
            if (history.getTriggerValue().compareTo(threshold) > 0) {
                consecutiveCount++;
            } else {
                break;
            }
        }

        return consecutiveCount >= config.getStrategy().getConsecutiveTimes();
    }

    private void saveTriggerHistory(Long strategyId, Long metricId, String sourceTable,
                                     Long sourceId, BigDecimal triggerValue, BigDecimal thresholdValue) {
        AlarmTriggerHistory history = new AlarmTriggerHistory();
        history.setStrategyId(strategyId);
        history.setMetricId(metricId);
        history.setSourceTable(sourceTable);
        history.setSourceId(sourceId);
        history.setTriggerValue(triggerValue);
        history.setThresholdValue(thresholdValue);
        history.setTriggerTime(LocalDateTime.now());
        triggerHistoryRepository.save(history);
    }

    @Autowired private com.metric.alarm.service.EffectiveObservationService effectiveObservations;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate observationJdbc;

    /** 用底层输入时间标识健康度更新，不把每5分钟重算的record_time当作新增数据。 */
    private Map<String, com.metric.alarm.service.EffectiveObservationService.Source> healthSources(AlarmStrategy strategy, LocalDateTime cutoff) {
        String where;
        List<Object> parameters = new ArrayList<>();
        switch (strategy.getTargetType()) {
            case "metric" -> { where = "m.id=?"; parameters.add(strategy.getTargetId()); }
            case "category" -> { where = "c.id=?"; parameters.add(strategy.getTargetId()); }
            case "system" -> {
                where = "c.system_id=? AND c.category_name IN ('对内部人员','对外部客户','运行效率','计划执行')";
                parameters.add(strategy.getTargetId());
            }
            case "subCategory" -> {
                var sub = metricSubCategoryService.getById(strategy.getTargetId()).orElse(null);
                if (sub == null) return Map.of();
                where = "m.category_id=? AND m.sub_category_name=?";
                parameters.add(sub.getCategoryId()); parameters.add(sub.getSubCategoryName());
            }
            default -> { return Map.of(); }
        }
        Map<String, com.metric.alarm.service.EffectiveObservationService.Source> sources = new TreeMap<>();
        observationJdbc.query("SELECT m.id,m.collect_frequency,r.data_time,r.metric_value FROM metric_item m JOIN metric_category c ON c.id=m.category_id"
                + " LEFT JOIN metric_realtime r ON r.metric_id=m.id WHERE m.status=1 AND " + where, rs -> {
                    var timestamp = rs.getTimestamp("data_time");
                    LocalDateTime time = timestamp == null ? null : timestamp.toLocalDateTime();
                    boolean valid = time != null && cutoff != null && !time.isAfter(cutoff) && rs.getBigDecimal("metric_value") != null;
                    sources.put(rs.getString("id"), new com.metric.alarm.service.EffectiveObservationService.Source(valid ? time : null, rs.getString("collect_frequency"), valid));
                }, parameters.toArray());
        return sources;
    }

    // ==================== 去重检查 ====================

    private boolean isRawDataAlarmExists(RawDataAlarmConfig config, Long sourceId) {
        return alarmRepository.findBySourceTableAndSourceIdAndStrategyIdAndProcessStatus(
                config.getTableName(), sourceId, config.getStrategyId(), 0).isPresent();
    }

    // ==================== 创建告警 ====================

    private Alarm createRawDataAlarm(RawDataAlarmConfig config, Map<String, Object> row,
                                     BigDecimal value, BigDecimal threshold, String level) {
        Alarm alarm = new Alarm();
        alarm.setStrategyId(config.getStrategyId());
        alarm.setSourceTable(config.getTableName());
        alarm.setSourceId(getSourceId(row, config));
        alarm.setMetricValue(value);
        alarm.setThresholdValue(threshold);
        alarm.setAlarmType("原始数据告警");

        // 构建告警内容
        String content = buildRawDataAlarmContent(config, row, value, threshold, level);
        alarm.setAlarmContent(content);

        // 设置维度信息
        alarm.setCategoryName(config.getFilterField());
        alarm.setBusinessName(config.getFilterValue());
        if (config.getSourceIdField() != null) {
            alarm.setMetricName(row.get(config.getSourceIdField()) != null ?
                    row.get(config.getSourceIdField()).toString() : null);
        }

        alarm.setAlarmTime(LocalDateTime.now());
        alarm.setProcessStatus(0);
        alarm.setShieldStatus(0);
        alarm.setTagStatus(0);

        return alarm;
    }

    private String buildRawDataAlarmContent(RawDataAlarmConfig config, Map<String, Object> row,
                                            BigDecimal value, BigDecimal threshold, String level) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(config.getTableName()).append("] ");
        sb.append("[").append(level).append("阈值] ");

        // 添加维度信息
        if (config.getFilterField() != null && !config.getFilterField().isEmpty()) {
            Object filterValue = row.get(config.getFilterField());
            if (filterValue != null) {
                sb.append(config.getFilterField()).append("=").append(filterValue).append(" ");
            }
        }

        // 添加对象标识
        if (config.getSourceIdField() != null) {
            Object idValue = row.get(config.getSourceIdField());
            if (idValue != null) {
                sb.append("(id=").append(idValue).append(") ");
            }
        }

sb.append(config.getMetricField()).append("=").append(value != null ? value.stripTrailingZeros().toPlainString() : "null");
        sb.append(" ").append(config.getGlobalCondition() != null ? config.getGlobalCondition() : ">");
        sb.append(" 阈值=").append(threshold != null ? threshold.stripTrailingZeros().toPlainString() : "null");

        return sb.toString();
    }

    // ==================== 指标值告警处理 ====================

    private int scanMetricValueAlarms() {
        int count = 0;

        // 获取所有启用的指标值策略
        List<AlarmStrategy> strategies = strategyRepository.findByStatusAndStrategyType(1, "metric");
        if (strategies == null || strategies.isEmpty()) {
            return 0;
        }

        System.out.println("开始扫描指标值告警，共 " + strategies.size() + " 个策略");

        for (AlarmStrategy strategy : strategies) {
            try {
                List<MetricItem> metrics = getMetricsByTarget(strategy);

                for (MetricItem metric : metrics) {
                    count += scanLevelMetric(strategy, metric);
                }
            } catch (Exception e) {
                System.err.println("处理指标值策略 " + strategy.getId() + " 出错: " + e.getMessage());
                currentScanErrors.add(new ScanError("指标值告警-策略" + strategy.getId(), e.getMessage(), LocalDateTime.now()));
            }
        }

        if (count > 0) {
            System.out.println("指标值告警扫描完成，生成 " + count + " 条告警/工单");
        }
        return count;
    }

    private boolean isAlarmEnabled(AlarmStrategy strategy) {
        return strategy.getAlarmEnabled() != null && strategy.getAlarmEnabled() == 1;
    }

    private boolean isGongdanEnabled(AlarmStrategy strategy) {
        return strategy.getGongdanEnabled() != null && strategy.getGongdanEnabled() == 1;
    }

    /*
     * 告警阈值来源（2026-10-28 指标与告警解耦后）：
     * 唯一来源是 alarm_strategy，指标表不再承载任何阈值。
     * 以下方法保留 metric 参数仅为兼容既有调用签名，不再读取指标表字段。
     */
    private BigDecimal alarmThresholdOf(AlarmStrategy strategy, MetricItem metric) {
        return strategy.getAlarmThreshold();
    }

    private String alarmConditionOf(AlarmStrategy strategy, MetricItem metric) {
        return strategy.getAlarmCondition() != null ? strategy.getAlarmCondition() : ">";
    }

    private BigDecimal gongdanThresholdOf(AlarmStrategy strategy, MetricItem metric) {
        return strategy.getGongdanThreshold();
    }

    private String gongdanConditionOf(AlarmStrategy strategy, MetricItem metric) {
        return strategy.getGongdanCondition() != null ? strategy.getGongdanCondition() : ">";
    }

    private int processAlarm(AlarmStrategy strategy, MetricItem metric, BigDecimal currentValue,
                             BigDecimal threshold, String condition, String sourceNote) {
        if (threshold == null) return 0;

        if (!checkCondition(currentValue, condition, threshold)) {
            return 0;
        }

        if (isMetricAlarmExists(strategy, metric.getId())) {
            return 0;
        }

        // 屏蔽检查：如果该目标已被屏蔽规则覆盖，不生成新告警
        Long systemId = null;
        if (metric.getCategoryId() != null) {
            systemId = metricCategoryRepository.findById(metric.getCategoryId())
                .map(cat -> cat.getSystemId()).orElse(null);
        }
        if (isTargetShielded(systemId, metric.getCategoryId(), metric.getId())) {
            System.out.println("指标 " + metric.getMetricName() + " 被屏蔽规则覆盖，跳过告警生成");
            return 0;
        }

        Alarm alarm = createMetricAlarm(strategy, metric, currentValue, threshold, "指标值告警");
        alarm.setAlarmContent(alarm.getAlarmContent() + sourceNote);
        alarmService.save(alarm);
        return 1;
    }

    private int processGongdan(AlarmStrategy strategy, MetricItem metric, BigDecimal currentValue, String observationNote) {
        BigDecimal threshold = gongdanThresholdOf(strategy, metric);
        if (threshold == null) return 0;

        String condition = gongdanConditionOf(strategy, metric);
        if (!checkCondition(currentValue, condition, threshold)) {
            return 0;
        }

        if (isMetricGongdanExists(strategy, metric.getId())) {
            return 0;
        }

        // 屏蔽检查
        Long systemId = null;
        if (metric.getCategoryId() != null) {
            systemId = metricCategoryRepository.findById(metric.getCategoryId())
                .map(cat -> cat.getSystemId()).orElse(null);
        }
        if (isTargetShielded(systemId, metric.getCategoryId(), metric.getId())) {
            System.out.println("指标 " + metric.getMetricName() + " 被屏蔽规则覆盖，跳过工单生成");
            return 0;
        }

        // 创建告警记录（processStatus=3 表示工单类型）
        Alarm alarm = createMetricAlarm(strategy, metric, currentValue, threshold, "指标值工单");
        alarm.setProcessStatus(3);
        alarm.setAlarmContent(alarm.getAlarmContent() + observationNote);
        alarmService.save(alarm);

        // 创建工单实体
        try {
            Gongdan gongdan = buildGongdanForAlarm(strategy, metric, currentValue, threshold, alarm, systemId);
            // V46: 改走 gongdanService.save() 以触发 GongdanEvent → 全局 Toast
            gongdanService.save(gongdan);
            // 推送省 BOMC 创建工单（接口规则写死在 BomcGongdanClient，不依赖插件配置）。
            // 推送失败不影响本地建单，失败原因记录在 external_sync_msg，后续由定时任务/手动补推重试。
            gongdanIntegrationService.pushToBomc(gongdan.getId());
            System.out.println("已生成工单: " + gongdan.getGongdanId() + " 指标: " + metric.getMetricName());
        } catch (Exception e) {
            System.err.println("生成工单失败: " + e.getMessage());
            e.printStackTrace();
        }

        return 1;
    }

    /**
     * 组装自动生成的工单。
     *
     * <p>字段口径对齐《省BOMC-端到端-告警分析工单接口规范 V1.0》的 createTask 入参：
     * 标题统一以"【指标体系平台】"开头，告警对象 / 告警级别 / 发生时间 / 告警次数等
     * 由本方法落库，推送时再由 BomcGongdanClient 翻译成 BOMC 参数。
     */
    private Gongdan buildGongdanForAlarm(AlarmStrategy strategy, MetricItem metric, BigDecimal currentValue,
                                         BigDecimal threshold, Alarm alarm, Long systemId) {
        String systemName = alarm.getSystemName();
        String categoryName = alarm.getCategoryName();
        String metricName = metric.getMetricName() != null ? metric.getMetricName() : "";
        String unit = metric.getUnit() != null ? metric.getUnit() : "";

        // 告警对象名称：系统 - 指标大类 - 具体指标（BOMC mo_name）
        StringBuilder objectName = new StringBuilder();
        if (systemName != null && !systemName.isEmpty()) {
            objectName.append(systemName).append(" - ");
        }
        if (categoryName != null && !categoryName.isEmpty()) {
            objectName.append(categoryName).append(" - ");
        }
        objectName.append(metricName);

        String condition = gongdanConditionOf(strategy, metric);
        String valueText = currentValue != null ? currentValue.stripTrailingZeros().toPlainString() : "null";
        String thresholdText = threshold != null ? threshold.stripTrailingZeros().toPlainString() : "null";
        LocalDateTime now = LocalDateTime.now();

        Gongdan gongdan = new Gongdan();
        gongdan.setGongdanId("GD" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", metric.getId() % 1000)
                + (strategy.getSelectedRuleLevel() == null ? "" : "-" + java.util.UUID.randomUUID().toString().substring(0, 8)));
        gongdan.setSystemId(systemId);
        gongdan.setCategoryId(metric.getCategoryId());
        gongdan.setMetricId(metric.getId());
        // 标题：工单前缀统一为 【指标体系平台】+ 具体内容
        gongdan.setGongdanTitle(truncateText("【指标体系平台】" + objectName + " 指标值超阈值告警", 200));
        // 事件描述 / 告警内容：多行明细，便于在 BOMC 工单里直接看清告警要素
        // 说明：这里用字符串拼接而非 String.format，避免中文字面量里误写占位符导致建单失败
        String unitSuffix = (unit == null || unit.isEmpty()) ? "" : " " + unit;
        gongdan.setGongdanContent(
                "告警对象：" + objectName + "\n"
                + "指标：" + metricName + "\n"
                + "当前值：" + valueText + unitSuffix + "\n"
                + "判定条件：" + condition + " " + thresholdText + unitSuffix + "\n"
                + "告警时间：" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n"
                + "触发策略：" + (strategy.getStrategyName() != null ? strategy.getStrategyName() : "-"));
        gongdan.setGongdanType("自动");
        gongdan.setCurrentStep("待处理");
        gongdan.setCurrentHandler("");
        gongdan.setClosedStatus(0);
        gongdan.setStrategyId(strategy.getId());

        // 告警业务字段（推 BOMC 用）
        gongdan.setAlarmObject(truncateText(objectName.toString(), 200));
        gongdan.setAlarmLevel(strategy.getSelectedRuleLevel() != null ? strategy.getSelectedRuleLevel() : resolveAlarmLevel(currentValue, threshold));
        gongdan.setAlarmFirstTime(alarm.getAlarmTime() != null ? alarm.getAlarmTime() : now);
        gongdan.setAlarmLastTime(now);
        gongdan.setAlarmCount(1);
        return gongdan;
    }

    /**
     * 按"超出阈值的幅度"自动定级（BOMC mo_alarm_currentgrade）：
     * 偏差 &lt;10% → 1 警告；&lt;30% → 2 一般；&lt;60% → 3 重要；≥60% → 4 严重。
     */
    private int resolveAlarmLevel(BigDecimal currentValue, BigDecimal threshold) {
        if (currentValue == null || threshold == null || threshold.compareTo(BigDecimal.ZERO) == 0) {
            return 2;
        }
        BigDecimal deviation = currentValue.subtract(threshold).abs()
                .divide(threshold.abs(), 4, java.math.RoundingMode.HALF_UP);
        if (deviation.compareTo(new BigDecimal("0.1")) < 0) {
            return 1;
        }
        if (deviation.compareTo(new BigDecimal("0.3")) < 0) {
            return 2;
        }
        if (deviation.compareTo(new BigDecimal("0.6")) < 0) {
            return 3;
        }
        return 4;
    }

    /** 按字段长度截断，避免超出数据库列长度 */
    private static String truncateText(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private List<MetricItem> getMetricsByTarget(AlarmStrategy strategy) {
        if (strategy.getTargetType() == null) {
            return Collections.emptyList();
        }

        switch (strategy.getTargetType()) {
            case "metric":
                // 2026-10-28 指标状态=总电闸：已禁用的指标不参与任何告警判定，
                // 否则会出现"页面已停用、告警还在报"的情况。
                return metricItemRepository.findById(strategy.getTargetId())
                        .filter(m -> m.getStatus() == null || m.getStatus() == 1)
                        .map(List::of)
                        .orElse(Collections.emptyList());
            case "category":
                return metricItemRepository.findByCategoryIdAndStatus(strategy.getTargetId(), 1);
            default:
                return Collections.emptyList();
        }
    }

    private boolean isMetricAlarmExists(AlarmStrategy strategy, Long metricId) {
        if (strategy.getSelectedRuleLevel() != null) return hasOpenLevel(strategy, metricId, null, false);
        return alarmRepository.existsByStrategyIdAndMetricIdAndProcessStatus(
                strategy.getId(), metricId, 0);
    }

    private boolean isMetricGongdanExists(AlarmStrategy strategy, Long metricId) {
        if (strategy.getSelectedRuleLevel() != null) return hasOpenLevel(strategy, metricId, null, true);
        // 工单状态为 3
        return alarmRepository.existsByStrategyIdAndMetricIdAndProcessStatusIn(
                strategy.getId(), metricId, List.of(3));
    }

    private Alarm createMetricAlarm(AlarmStrategy strategy, MetricItem metric,
                                     BigDecimal value, BigDecimal threshold, String alarmType) {
        Alarm alarm = new Alarm();
        alarm.setStrategyId(strategy.getId());
        alarm.setAlarmLevel(strategy.getSelectedRuleLevel());
        alarm.setMetricId(metric.getId());
        alarm.setCategoryId(metric.getCategoryId());
        alarm.setMetricValue(value);
        alarm.setThresholdValue(threshold);
        alarm.setAlarmType(alarmType);
        alarm.setMetricName(metric.getMetricName());
        alarm.setSubCategoryName(metric.getSubCategoryName());
        alarm.setUnit(metric.getUnit());

        // 获取大类名称：从 metricItem 的 categoryId 查出真实大类名
        // 注意：strategy.getTargetName() 对 target_type=metric 返回的是指标名，不能用作大类名
        if (metric.getCategoryId() != null) {
            metricCategoryRepository.findById(metric.getCategoryId())
                .ifPresentOrElse(
                    category -> alarm.setCategoryName(category.getCategoryName()),
                    () -> alarm.setCategoryName("未知大类")
                );
        }

        String condition = "指标值工单".equals(alarmType)
                ? gongdanConditionOf(strategy, metric)
                : alarmConditionOf(strategy, metric);

String content = String.format("[%s] %s 指标值=%s %s，%s 阈值=%s %s",
                strategy.getStrategyName(), metric.getMetricName(),
                value != null ? value.stripTrailingZeros().toPlainString() : "null", metric.getUnit() != null ? metric.getUnit() : "",
                condition,
                threshold != null ? threshold.stripTrailingZeros().toPlainString() : "null", metric.getUnit() != null ? metric.getUnit() : "");
        alarm.setAlarmContent(content);

        // 设置 systemId 和 systemName
        if (metric.getCategoryId() != null) {
            metricCategoryRepository.findById(metric.getCategoryId())
                .ifPresent(category -> {
                    alarm.setSystemId(category.getSystemId());
                    if (category.getSystemId() != null) {
                        systemInfoRepository.findById(category.getSystemId())
                            .ifPresent(system -> alarm.setSystemName(system.getSystemName()));
                    }
                });
        }

        alarm.setAlarmTime(LocalDateTime.now());
        alarm.setProcessStatus("指标值工单".equals(alarmType) ? 3 : 0);
        alarm.setShieldStatus(0);
        alarm.setTagStatus(0);

        return alarm;
    }

    // ==================== 健康度告警处理 ====================

    private int scanHealthScoreAlarms() {
        int count = 0;

        List<AlarmStrategy> strategies = strategyRepository.findByStatusAndStrategyType(1, "health");
        if (strategies == null || strategies.isEmpty()) {
            return 0;
        }

        System.out.println("开始扫描健康度告警，共 " + strategies.size() + " 个策略");

        for (AlarmStrategy strategy : strategies) {
            try {
                HealthScoreData healthData = getLevelHealthScoreByTarget(strategy);
                count += scanLevelHealth(strategy, healthData);
            } catch (Exception e) {
                System.err.println("处理健康度策略 " + strategy.getId() + " 出错: " + e.getMessage());
                currentScanErrors.add(new ScanError("健康度告警-策略" + strategy.getId(), e.getMessage(), LocalDateTime.now()));
            }
        }

        if (count > 0) {
            System.out.println("健康度告警扫描完成，生成 " + count + " 条告警");
        }
        return count;
    }

    private HealthScoreData getHealthScoreByTarget(AlarmStrategy strategy) {
        HealthScoreData data = new HealthScoreData();

        switch (strategy.getTargetType()) {
            case "metric":
                // 2026-10-28 指标状态=总电闸：指标已禁用则不再产出健康度告警。
                // 这里返回 score 为 null 的对象，调用方会按"无数据"跳过（历史健康度不受影响）。
                MetricItem targetMetric = metricItemRepository.findById(strategy.getTargetId()).orElse(null);
                if (targetMetric == null
                        || (targetMetric.getStatus() != null && targetMetric.getStatus() == 0)) {
                    return data;
                }
                // 指标健康度 - 从 MetricHealthHistory
                List<MetricHealthHistory> metricHistory =
                        metricHealthHistoryRepository.findByCategoryNameAndSubCategoryNameOrderByRecordTimeDesc(
                                strategy.getTargetName(), null);
                if (!metricHistory.isEmpty()) {
                    MetricHealthHistory latest = metricHistory.get(0);
                    data.healthTime = latest.getRecordTime();
                    data.setScore(BigDecimal.valueOf(latest.getHealthScore()));
                    data.setMetricId(strategy.getTargetId());
                }
                // 填充层级信息：metric -> category -> system
                metricItemRepository.findById(strategy.getTargetId())
                        .ifPresent(metric -> {
                            data.setSubCategoryName(metric.getSubCategoryName());
                            // 通过 categoryId 查询 categoryName 和 systemId
                            metricCategoryRepository.findById(metric.getCategoryId())
                                    .ifPresent(category -> {
                                        data.setCategoryId(category.getId());
                                        data.setCategoryName(category.getCategoryName());
                                        data.setSystemId(category.getSystemId());
                                    });
                        });
                break;

            case "subCategory":
                // 小类健康度 - 从 SubCategoryHealthHistory
                subCategoryHealthHistoryRepository.findLatestByCategoryName(strategy.getTargetName())
                        .ifPresent(h -> {
                            data.healthTime = h.getRecordTime();
                            data.setScore(BigDecimal.valueOf(h.getHealthScore()));
                            data.setSubCategoryName(h.getSubCategoryName());
                            data.setCategoryName(h.getCategoryName());
                            // 通过 categoryName 查询 categoryId 和 systemId
                            metricCategoryRepository.findByCategoryName(h.getCategoryName())
                                    .ifPresent(category -> {
                                        data.setCategoryId(category.getId());
                                        data.setSystemId(category.getSystemId());
                                    });
                        });
                break;

            case "category":
                // 大类健康度 - 从 CategoryHealthHistory
                categoryHealthHistoryRepository.findLatestByCategoryName(strategy.getTargetName())
                        .ifPresent(h -> {
                            data.healthTime = h.getRecordTime();
                            data.setScore(BigDecimal.valueOf(h.getHealthScore()));
                            data.setCategoryName(h.getCategoryName());
                            // 通过 categoryName 查询 categoryId 和 systemId
                            metricCategoryRepository.findByCategoryName(h.getCategoryName())
                                    .ifPresent(category -> {
                                        data.setCategoryId(category.getId());
                                        data.setSystemId(category.getSystemId());
                                    });
                        });
                break;

case "system":
                // 系统健康度 - 按各大类健康度加权计算
                List<String> categoryNames = List.of("对内部人员", "对外部客户", "运行效率", "计划执行");
                List<CategoryHealthHistory> categoryHealthList = categoryHealthHistoryRepository.findLatestByCategoryNames(categoryNames);

                System.out.println("=== 系统健康度计算 ===");
                System.out.println("categoryHealthList size: " + categoryHealthList.size());
                for (CategoryHealthHistory h : categoryHealthList) {
                    System.out.println("  " + h.getCategoryName() + ": " + h.getHealthScore() + " at " + h.getRecordTime());
                }

                if (!categoryHealthList.isEmpty()) {
                    // 获取大类权重配置
                    // systemId 优先取 strategy.targetId，fallback 从 system_config 读取（修复前硬编码 1L）
                    Long systemId = strategy.getTargetId() != null
                            ? strategy.getTargetId()
                            : configService.getConfigLong(CONFIG_KEY_DEFAULT_SYSTEM_ID, 1L);
                    Map<Long, BigDecimal> categoryWeights = weightConfigService.getCategoryWeights(systemId);
                    System.out.println("categoryWeights: " + categoryWeights);

                    // 构建 categoryName -> weight 的映射
                    Map<String, BigDecimal> categoryNameToWeight = new HashMap<>();
                    for (CategoryHealthHistory h : categoryHealthList) {
                        metricCategoryRepository.findByCategoryName(h.getCategoryName())
                                .ifPresent(cat -> {
                                    BigDecimal weight = categoryWeights.getOrDefault(cat.getId(), new BigDecimal("0.25"));
                                    categoryNameToWeight.put(h.getCategoryName(), weight);
                                    System.out.println("  映射: " + h.getCategoryName() + " -> categoryId:" + cat.getId() + " weight:" + weight);
                                });
                    }

                    double weightedSum = 0;
                    double totalWeight = 0;

                    for (CategoryHealthHistory h : categoryHealthList) {
                        int healthScore = h.getHealthScore();
                        BigDecimal weight = categoryNameToWeight.getOrDefault(h.getCategoryName(), new BigDecimal("0.25"));
                        System.out.println("  计算: " + h.getCategoryName() + " score=" + healthScore + " weight=" + weight);
                        weightedSum += healthScore * weight.doubleValue();
                        totalWeight += weight.doubleValue();
                    }

                    int finalScore = totalWeight > 0 ? (int) Math.round(weightedSum / totalWeight) : 100;
                    System.out.println("  weightedSum=" + weightedSum + " totalWeight=" + totalWeight + " finalScore=" + finalScore);
                    System.out.println("=====================");

                    if (totalWeight > 0) {
                        data.setScore(BigDecimal.valueOf(finalScore));
                    }
                    data.healthTime = categoryHealthList.stream().map(CategoryHealthHistory::getRecordTime).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
                    data.setSystemId(systemId);
                    data.setCategoryName("系统健康度");
                }
                break;
        }

        return data;
    }

    private Alarm createHealthAlarm(AlarmStrategy strategy, HealthScoreData healthData, BigDecimal threshold) {
        Alarm alarm = new Alarm();
        alarm.setStrategyId(strategy.getId());
        alarm.setAlarmLevel(strategy.getSelectedRuleLevel());
        alarm.setHealthScore(healthData.getScore() != null ? healthData.getScore().intValue() : null);
        alarm.setThresholdValue(threshold);
        alarm.setAlarmType("健康度告警");

        // 设置告警对象相关字段
        if (healthData.getSystemId() != null) {
            alarm.setSystemId(healthData.getSystemId());
            // 查询系统名称
            systemInfoRepository.findById(healthData.getSystemId())
                .ifPresent(system -> alarm.setSystemName(system.getSystemName()));
        }
        if (healthData.getCategoryId() != null) {
            alarm.setCategoryId(healthData.getCategoryId());
        }
        if (healthData.getCategoryName() != null) {
            alarm.setCategoryName(healthData.getCategoryName());
        }
        if (healthData.getSubCategoryName() != null) {
            alarm.setSubCategoryName(healthData.getSubCategoryName());
        }
        if (healthData.getMetricId() != null) {
            alarm.setMetricId(healthData.getMetricId());
            // 查询指标名称
            metricItemRepository.findById(healthData.getMetricId())
                .ifPresent(metric -> alarm.setMetricName(metric.getMetricName()));
        }

String targetDesc = getTargetDescription(strategy);
        String alarmCond = strategy.getAlarmCondition() != null ? strategy.getAlarmCondition() : ">";
        String content = String.format("[%s] %s 健康度=%s，%s 阈值=%s",
                strategy.getStrategyName(), targetDesc,
                healthData.getScore(),
                alarmCond,
                threshold != null ? threshold.stripTrailingZeros().toPlainString() : "null");
        alarm.setAlarmContent(content);

        alarm.setAlarmTime(LocalDateTime.now());
        alarm.setProcessStatus(0);
        alarm.setShieldStatus(0);
        alarm.setTagStatus(0);

        return alarm;
    }

    private String getTargetDescription(AlarmStrategy strategy) {
        switch (strategy.getTargetType()) {
            case "metric": return "指标ID=" + strategy.getTargetId();
            case "subCategory": return "小类=" + strategy.getTargetName();
            case "category": return "大类=" + strategy.getTargetName();
            case "system": return "系统ID=" + strategy.getTargetId();
            default: return "未知";
        }
    }

    private AlarmStrategy selectedStrategy(AlarmStrategy source, StrategyRule rule, boolean gongdan) {
        AlarmStrategy selected = new AlarmStrategy();
        org.springframework.beans.BeanUtils.copyProperties(source, selected);
        selected.setSelectedRuleLevel(rule.getLevel());
        if (gongdan) {
            selected.setGongdanThreshold(rule.getThreshold());
            selected.setGongdanCondition(rule.getCondition());
            selected.setGongdanConsecutiveTimes(rule.getConsecutiveTimes());
        } else {
            selected.setAlarmThreshold(rule.getThreshold());
            selected.setAlarmCondition(rule.getCondition());
            selected.setAlarmConsecutiveTimes(rule.getConsecutiveTimes());
        }
        return selected;
    }

    private int scanLevelMetric(AlarmStrategy strategy, MetricItem metric) {
        if (SatisfactionAlarmValueService.supports(metric)) return scanLevelSatisfaction(strategy, metric);
        var realtime = metricRealtimeRepository.findByMetricId(metric.getId()).orElse(null);
        BigDecimal value = realtime == null ? null : realtime.getMetricValue();
        var source = new com.metric.alarm.service.EffectiveObservationService.Source(
                realtime == null ? null : realtime.getDataTime(), metric.getCollectFrequency(), value != null);
        return effectiveObservations.evaluate(strategy, "metric:" + metric.getId(), value, Map.of(metric.getId().toString(), source),
                isAlarmEnabled(strategy) ? StrategyRuleService.effectiveRules(strategy, false) : List.of(),
                isGongdanEnabled(strategy) ? StrategyRuleService.effectiveRules(strategy, true) : List.of(), decision -> {
                    int count = 0;
                    if (decision.alarm() != null) {
                        StrategyRule rule = decision.alarm();
                        count += processAlarm(selectedStrategy(strategy, rule, false), metric, value, rule.getThreshold(), rule.getCondition(), decision.note(false));
                    }
                    if (decision.gongdan() != null) count += processGongdan(selectedStrategy(strategy, decision.gongdan(), true), metric, value, decision.note(true));
                    return count;
                });
    }

    /** 保留满意度工作日、边界日与月累计的已配置口径，各统计周期独立计数。 */
    private int scanLevelSatisfaction(AlarmStrategy strategy, MetricItem metric) {
        return effectiveObservations.inTransaction(() -> scanEffectiveSatisfaction(strategy, metric));
    }

    private int scanEffectiveSatisfaction(AlarmStrategy strategy, MetricItem metric) {
        Map<String, List<StrategyRule>> alarmCandidates = new LinkedHashMap<>();
        Map<String, BigDecimal> values = new HashMap<>();
        Map<String, String> notes = new HashMap<>();
        Map<String, LocalDateTime> observationTimes = new HashMap<>();
        for (StrategyRule rule : isAlarmEnabled(strategy) ? StrategyRuleService.effectiveRules(strategy, false) : List.<StrategyRule>of()) {
            for (var observation : satisfactionAlarmValues.observations(metric, rule.getThreshold(), rule.getCondition())) {
                if (observation.value() == null || observation.threshold() == null) continue;
                String key = observation.period();
                StrategyRule candidate = new StrategyRule();
                org.springframework.beans.BeanUtils.copyProperties(rule, candidate);
                candidate.setThreshold(observation.threshold());
                candidate.setCondition(observation.condition());
                alarmCandidates.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
                values.put(key, observation.value());
                observationTimes.put(key, observation.businessDate().atStartOfDay());
                notes.put(key, "；统计日期=" + observation.businessDate() + "，口径=" + key);
            }
        }
        // 工单不从告警的边界日/月累计阈值派生；只按显式工单规则判断当日采集值。
        if (isGongdanEnabled(strategy)) {
            for (var observation : satisfactionAlarmValues.observations(metric, null, null)) {
                if ("日".equals(observation.period()) && observation.value() != null) {
                    values.put("日", observation.value());
                    observationTimes.put("日", observation.businessDate().atStartOfDay());
                    notes.put("日", "；统计日期=" + observation.businessDate() + "，口径=日");
                }
            }
        }
        int count = 0;
        StrategyRule[] bestRule = { null };
        BigDecimal[] bestValue = { null };
        String[] bestNote = { null };
        for (var entry : values.entrySet()) {
            String period = entry.getKey();
            BigDecimal value = entry.getValue();
            // 工作日与月累计保持已有统计口径；没有确认的日历频率不推断缺失。
            var source = new com.metric.alarm.service.EffectiveObservationService.Source(observationTimes.get(period), null, value != null);
            count += effectiveObservations.evaluate(strategy, "satisfaction:" + metric.getId() + ":" + period, value,
                    Map.of(metric.getId().toString(), source),
                    isAlarmEnabled(strategy) ? alarmCandidates.getOrDefault(period, List.of()) : List.of(),
                    "日".equals(period) && isGongdanEnabled(strategy) ? StrategyRuleService.effectiveRules(strategy, true) : List.of(), decision -> {
                        int generated = 0;
                        if (decision.alarm() != null) {
                            StrategyRule rule = decision.alarm();
                            if (bestRule[0] == null || rule.getLevel() > bestRule[0].getLevel()) {
                                bestRule[0] = rule; bestValue[0] = value; bestNote[0] = notes.get(period) + decision.note(false);
                            }
                        }
                        if (decision.gongdan() != null) generated += processGongdan(selectedStrategy(strategy, decision.gongdan(), true), metric, value, notes.get(period) + decision.note(true));
                        return generated;
                    });
        }
        if (bestRule[0] != null) count += processAlarm(selectedStrategy(strategy, bestRule[0], false), metric, bestValue[0], bestRule[0].getThreshold(), bestRule[0].getCondition(), bestNote[0]);
        return count;
    }

    private boolean hasOpenLevel(AlarmStrategy strategy, Long metricId, HealthScoreData health, boolean gongdan) {
        return alarmRepository.findByStrategyIdAndProcessStatus(strategy.getId(), gongdan ? 3 : 0).stream()
                .filter(a -> health != null || Objects.equals(a.getMetricId(), metricId))
                .anyMatch(a -> (a.getAlarmLevel() == null ? 2 : a.getAlarmLevel()) >= strategy.getSelectedRuleLevel());
    }

    /** 分级策略按目标ID定位历史，避免同名指标/小类串用分值。 */
    private HealthScoreData getLevelHealthScoreByTarget(AlarmStrategy strategy) {
        if (!"metric".equals(strategy.getTargetType()) && !"subCategory".equals(strategy.getTargetType()))
            return getHealthScoreByTarget(strategy);
        HealthScoreData data = new HealthScoreData();
        Long categoryId;
        if ("metric".equals(strategy.getTargetType())) {
            MetricItem metric = metricItemRepository.findById(strategy.getTargetId()).orElse(null);
            if (metric == null || Integer.valueOf(0).equals(metric.getStatus())) return data;
            categoryId = metric.getCategoryId();
            data.setMetricId(metric.getId());
            data.setSubCategoryName(metric.getSubCategoryName());
            metricHealthHistoryRepository.findFirstByMetricIdOrderByRecordTimeDescIdDesc(metric.getId())
                    .ifPresent(h -> { data.healthTime = h.getRecordTime(); data.setScore(h.getHealthScore() == null ? null : BigDecimal.valueOf(h.getHealthScore())); });
        } else {
            var sub = metricSubCategoryService.getById(strategy.getTargetId()).orElse(null);
            if (sub == null) return data;
            categoryId = sub.getCategoryId();
            data.setSubCategoryName(sub.getSubCategoryName());
        }
        if (categoryId == null) return data;
        metricCategoryRepository.findById(categoryId).ifPresent(category -> {
            data.setCategoryId(category.getId());
            data.setCategoryName(category.getCategoryName());
            data.setSystemId(category.getSystemId());
            if ("subCategory".equals(strategy.getTargetType())) {
                var history = subCategoryHealthHistoryRepository.findByCategoryNameAndSubCategoryNameOrderByRecordTimeDesc(
                        category.getCategoryName(), data.getSubCategoryName());
                if (!history.isEmpty() && history.get(0).getHealthScore() != null) {
                    data.healthTime = history.get(0).getRecordTime();
                    data.setScore(BigDecimal.valueOf(history.get(0).getHealthScore()));
                }
            }
        });
        return data;
    }

    private int scanLevelHealth(AlarmStrategy strategy, HealthScoreData data) {
        if (isTargetShielded(data.getSystemId(), data.getCategoryId(), data.getMetricId())) return 0;
        return effectiveObservations.evaluate(strategy, "health:" + strategy.getTargetType() + ":" + strategy.getTargetId(),
                data.getScore(), healthSources(strategy, data.healthTime),
                isAlarmEnabled(strategy) ? StrategyRuleService.effectiveRules(strategy, false) : List.of(),
                isGongdanEnabled(strategy) ? StrategyRuleService.effectiveRules(strategy, true) : List.of(),
                decision -> generateEffectiveHealth(strategy, data, decision));
    }

    private int generateEffectiveHealth(AlarmStrategy strategy, HealthScoreData data,
            com.metric.alarm.service.EffectiveObservationService.Decision decision) {
        int count = 0;
        for (boolean gongdan : List.of(false, true)) {
            if (gongdan ? !isGongdanEnabled(strategy) : !isAlarmEnabled(strategy)) continue;
            StrategyRule rule = gongdan ? decision.gongdan() : decision.alarm();
            if (rule == null) continue;
            AlarmStrategy selected = selectedStrategy(strategy, rule, gongdan);
            if (hasOpenLevel(selected, null, data, gongdan)) continue;
            // createHealthAlarm uses alarmCondition in its description.
            selected.setAlarmCondition(rule.getCondition());
            Alarm alarm = createHealthAlarm(selected, data, rule.getThreshold());
            alarm.setAlarmContent(alarm.getAlarmContent() + decision.note(gongdan));
            alarm.setAlarmType(gongdan ? "健康度工单" : "健康度告警");
            alarm.setProcessStatus(gongdan ? 3 : 0);
            alarmService.save(alarm);
            if (gongdan) {
                Gongdan ticket = new Gongdan();
                ticket.setGongdanId("GD" + java.util.UUID.randomUUID().toString().replace("-", ""));
                ticket.setSystemId(data.getSystemId());
                ticket.setCategoryId(data.getCategoryId());
                ticket.setMetricId(data.getMetricId());
                ticket.setStrategyId(strategy.getId());
                ticket.setGongdanTitle(truncateText("【指标体系平台】" + strategy.getTargetName() + " 健康度告警", 200));
                ticket.setGongdanContent(alarm.getAlarmContent());
                ticket.setGongdanType("自动");
                ticket.setCurrentStep("待处理");
                ticket.setClosedStatus(0);
                ticket.setAlarmObject(strategy.getTargetName());
                ticket.setAlarmLevel(rule.getLevel());
                ticket.setAlarmFirstTime(alarm.getAlarmTime());
                ticket.setAlarmLastTime(alarm.getAlarmTime());
                ticket.setAlarmCount(1);
                gongdanService.save(ticket);
                gongdanIntegrationService.pushToBomc(ticket.getId());
            }
            count++;
        }
        return count;
    }

    // ==================== 内部类 ====================


    private static class ThresholdMatch {
        BigDecimal threshold;
        String condition;
        String level;  // object / dimension / global
    }

    private static class HealthScoreData {
        BigDecimal score;
        LocalDateTime healthTime;
        Long metricId;
        Long categoryId;
        String categoryName;
        String subCategoryName;
        Long systemId;

        public BigDecimal getScore() { return score; }
        public void setScore(BigDecimal score) { this.score = score; }
        public Long getMetricId() { return metricId; }
        public void setMetricId(Long metricId) { this.metricId = metricId; }
        public Long getCategoryId() { return categoryId; }
        public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
        public String getSubCategoryName() { return subCategoryName; }
        public void setSubCategoryName(String subCategoryName) { this.subCategoryName = subCategoryName; }
        public Long getSystemId() { return systemId; }
        public void setSystemId(Long systemId) { this.systemId = systemId; }
    }
}
