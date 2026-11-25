package com.metric.metric.service;

import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricHealthHistory;
import com.metric.metric.entity.MetricHealthRule;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.MetricHealthHistoryRepository;
import com.metric.metric.repository.MetricHealthRuleRepository;
import com.metric.metric.repository.MetricItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康度算分调度服务：取数 → 逐指标打分 → 落库 → 聚合。
 *
 * <p>关键口径（替代改造前三段硬编码）：
 * <ul>
 *   <li><b>谁参与</b>：所有启用指标中，规则未白名单、且最新指标值未超过 stale-days 的；</li>
 *   <li><b>当前值来源</b>：metric_value_history 的最新一行（同时带出 data_origin，供页面标注样例数据）；</li>
 *   <li><b>大类/小类归属</b>：一律取 metric_item 的真实归属，不再按“小类名”反查大类，
 *       从根上避免 46 个指标被记错大类的问题；</li>
 *   <li><b>不出分不留脏数据</b>：“基线学习中 / 缺数”不写健康度行，避免 0 分冒充异常。</li>
 * </ul>
 */
@Service
public class HealthScoringService {

    /** 一次运行的结果摘要 */
    public record RunSummary(int scored, int exempt, int learning, int noData, int failed,
                             int categories, LocalDateTime recordTime) {}

    @Autowired private MetricItemRepository itemRepository;
    @Autowired private MetricCategoryRepository categoryRepository;
    @Autowired private MetricHealthRuleRepository ruleRepository;
    @Autowired private MetricHealthHistoryRepository healthRepository;
    @Autowired private HealthScoreEngine engine;
    @Autowired private HealthAggregationService aggregationService;
    @Autowired private JdbcTemplate jdbc;

    public record ValuePoint(BigDecimal value, LocalDateTime recordTime, LocalDateTime dataTime, String origin) {}

    /** 供规则配置页/试算器取数：每个指标最新一行指标值 */
    public Map<Long, ValuePoint> latestValueIndex() {
        return latestValues();
    }

    /** 供规则配置页/试算器取数：近 N 天样本，按指标分组 */
    public Map<Long, List<HealthScoreEngine.Sample>> sampleIndex(int days) {
        return samples(LocalDateTime.now().minusDays(Math.max(1, days)));
    }    // ==================================================================
    // 主流程
    // ==================================================================

    /**
     * 全量重算所有指标健康度并刷新聚合。定时任务和“规则保存后立即重算”都走这里。
     */
    @Transactional
    public RunSummary runAll() {
        return run(item -> true);
    }

    /** 只重算某个大类（便于页面保存后局部生效） */
    @Transactional
    public RunSummary runCategory(String categoryName) {
        List<Long> categoryIds = new ArrayList<>();
        for (MetricCategory category : categoryRepository.findAll()) {
            if (categoryName == null || categoryName.equals(category.getCategoryName())) {
                categoryIds.add(category.getId());
            }
        }
        return run(item -> categoryIds.contains(item.getCategoryId()));
    }

    private RunSummary run(java.util.function.Predicate<MetricItem> filter) {
        HealthScoreEngine.Settings settings = engine.settings();
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);

        List<MetricItem> items = activeItems().stream().filter(filter).toList();
        Map<Long, MetricCategory> categories = new HashMap<>();
        for (MetricCategory category : categoryRepository.findAll()) {
            categories.put(category.getId(), category);
        }
        Map<Long, MetricHealthRule> rules = new HashMap<>();
        for (MetricHealthRule rule : ruleRepository.findAll()) {
            rules.put(rule.getMetricId(), rule);
        }
        Map<Long, ValuePoint> latestValues = latestValues();
        Map<Long, List<HealthScoreEngine.Sample>> samples = samples(now.minusDays(90));

        int scored = 0, exempt = 0, learning = 0, noData = 0, failed = 0;
        Map<Long, HealthAggregationService.MetricScore> scores = new HashMap<>();
        LocalDateTime staleBefore = now.minusDays(settings.staleDays());

        for (MetricItem item : items) {
            MetricHealthRule rule = rules.get(item.getId());
            if (rule != null && rule.isExempt()) {
                exempt++;
                continue;
            }
            ValuePoint latest = latestValues.get(item.getId());
            if (latest == null || latest.recordTime() == null || latest.recordTime().isBefore(staleBefore)) {
                noData++;
                continue;
            }
            try {
                HealthScoreEngine.Outcome outcome = engine.evaluate(rule, latest.value(),
                        latest.origin(), samples.getOrDefault(item.getId(), List.of()), now, settings);
                if (!outcome.scored()) {
                    learning++;
                    continue;
                }
                MetricCategory category = categories.get(item.getCategoryId());
                String categoryName = category != null ? category.getCategoryName() : "未分类大类";
                save(item, categoryName, rule, latest, outcome, now);
                scores.put(item.getId(), new HealthAggregationService.MetricScore(item.getId(),
                        item.getMetricCode(), item.getMetricName(), categoryName,
                        subNameOf(item), outcome.score(), outcome.status(), outcome.mode(), latest.origin()));
                scored++;
            } catch (RuntimeException e) {
                failed++;
                System.err.println("[健康度] 指标算分失败: " + item.getMetricName() + " - " + e.getMessage());
            }
        }

        List<HealthAggregationService.Node> categoryNodes = aggregationService.aggregateAndPersist(scores, now);
        System.out.println("[健康度] 算分完成 scored=" + scored + " learning=" + learning + " noData=" + noData
                + " exempt=" + exempt + " failed=" + failed + " categories=" + categoryNodes.size());
        return new RunSummary(scored, exempt, learning, noData, failed, categoryNodes.size(), now);
    }

    // ==================================================================
    // 落库
    // ==================================================================

    private void save(MetricItem item, String categoryName, MetricHealthRule rule, ValuePoint latest,
                      HealthScoreEngine.Outcome outcome, LocalDateTime now) {
        MetricHealthHistory row = healthRepository.findByMetricIdAndRecordTime(item.getId(), now)
                .orElseGet(MetricHealthHistory::new);
        row.setMetricId(item.getId());
        row.setMetricCode(item.getMetricCode());
        row.setMetricName(item.getMetricName());
        row.setCategoryName(categoryName);
        row.setSubCategoryName(subNameOf(item));
        row.setHealthScore(outcome.score());
        row.setStatus(outcome.status());
        row.setMode(outcome.mode());
        row.setRuleVersion(rule != null && rule.getVersion() != null ? rule.getVersion() : 1);
        row.setBaselineUsed(outcome.baselineUsed());
        row.setDeviation(outcome.deviation());
        row.setSampleCount(outcome.sampleCount());
        row.setScoreTrace(outcome.traceJson());
        row.setAlgorithmVersion(HealthScoreEngine.ALGORITHM_VERSION);
        row.setDataOrigin(outcome.dataOrigin());
        row.setCurrentValue(outcome.currentValue() == null ? null : outcome.currentValue().intValue());
        row.setBaseline(outcome.baselineUsed());
        row.setThreshold(outcome.baselineUsed());
        row.setDataTime(latest.dataTime() != null ? latest.dataTime() : latest.recordTime());
        row.setRecordTime(now);
        healthRepository.save(row);
    }

    // ==================================================================
    // 取数
    // ==================================================================

    private List<MetricItem> activeItems() {
        return itemRepository.findAll().stream()
                .filter(item -> item.getStatus() == null || item.getStatus() == 1)
                .toList();
    }

    /** 每个指标最新一行指标值（用 MAX(id) 定位，避免同 record_time 多行） */
    private Map<Long, ValuePoint> latestValues() {
        Map<Long, ValuePoint> result = new HashMap<>();
        String sql = """
                SELECT v.metric_id, v.metric_value, v.record_time, v.data_time, v.data_origin
                FROM metric_value_history v
                JOIN (SELECT metric_id, MAX(id) AS mid FROM metric_value_history GROUP BY metric_id) x
                  ON x.mid = v.id
                """;
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            Long metricId = ((Number) row.get("metric_id")).longValue();
            Object value = row.get("metric_value");
            if (value == null) continue;
            result.put(metricId, new ValuePoint(new BigDecimal(value.toString()),
                    toLocalDateTime(row.get("record_time")),
                    toLocalDateTime(row.get("data_time")),
                    originOf(row.get("data_origin"))));
        }
        return result;
    }

    /** 近 N 天全部指标值样本，一次查出后按指标分组，避免逐指标查询造成 N+1 */
    private Map<Long, List<HealthScoreEngine.Sample>> samples(LocalDateTime from) {
        Map<Long, List<HealthScoreEngine.Sample>> result = new HashMap<>();
        String sql = """
                SELECT metric_id, metric_value, record_time, data_origin
                FROM metric_value_history
                WHERE record_time >= ?
                """;
        jdbc.query(sql, (rs, rowNum) -> {
            long metricId = rs.getLong("metric_id");
            BigDecimal value = rs.getBigDecimal("metric_value");
            LocalDateTime time = rs.getTimestamp("record_time") == null ? null
                    : rs.getTimestamp("record_time").toLocalDateTime();
            if (value == null || time == null) return null;
            result.computeIfAbsent(metricId, key -> new ArrayList<>())
                    .add(new HealthScoreEngine.Sample(time, value, originOf(rs.getString("data_origin"))));
            return null;
        }, from);
        return result;
    }

    private static String originOf(Object raw) {
        String origin = raw == null ? null : raw.toString();
        return origin == null || origin.isBlank() ? "collected" : origin;
    }

    private static LocalDateTime toLocalDateTime(Object raw) {
        if (raw == null) return null;
        if (raw instanceof LocalDateTime time) return time;
        if (raw instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        return LocalDateTime.parse(raw.toString().replace(' ', 'T'));
    }

    private static String subNameOf(MetricItem item) {
        return item.getSubCategoryName() == null || item.getSubCategoryName().isBlank()
                ? "未分类" : item.getSubCategoryName();
    }

    // ==================================================================
    // 供页面查询
    // ==================================================================

    /** 某指标的算分明细（解释弹窗直接用，前端不再复刻公式） */
    public Map<String, Object> latestTrace(Long metricId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT metric_id, metric_name, category_name, sub_category_name, health_score, status,
                       mode, rule_version, baseline_used, deviation, sample_count, score_trace,
                       algorithm_version, data_origin, current_value, record_time
                FROM metric_health_history
                WHERE metric_id = ? AND mode IS NOT NULL
                ORDER BY record_time DESC LIMIT 1
                """, metricId);
        if (rows.isEmpty()) {
            result.put("metricId", metricId);
            result.put("found", false);
            return result;
        }
        result.put("found", true);
        result.putAll(rows.get(0));
        return result;
    }
}
