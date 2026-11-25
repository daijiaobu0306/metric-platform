package com.metric.metric.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricHealthRule;
import com.metric.metric.entity.MetricHealthRuleVersion;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.entity.SystemInfo;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.MetricHealthRuleRepository;
import com.metric.metric.repository.MetricHealthRuleVersionRepository;
import com.metric.metric.repository.MetricItemRepository;
import com.metric.metric.repository.SystemInfoRepository;
import com.metric.security.service.CurrentUserProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 健康度规则配置服务：树形读取、建议基线、试算、批量保存、变更审计、新旧对比。
 *
 * <p>界面形态（见设计文档 6.2）：一个全屏抽屉里左侧是三态级联树，右侧是规则面板。
 * 后端按“一次返回整棵树”设计，避免页面拼几十个请求；保存只提交差异，带原因与乐观锁。
 *
 * <p><b>建议值不参与算分</b>：{@code baseline_status='suggested'} 只在页面提示，
 * 人工点「采纳」后置 confirmed 才生效。这是“未确认的阈值不得当正式规则”的落地方式。
 */
@Service
public class HealthRuleService {

    @Autowired private MetricItemRepository itemRepository;
    @Autowired private MetricCategoryRepository categoryRepository;
    @Autowired private SystemInfoRepository systemInfoRepository;
    @Autowired private MetricHealthRuleRepository ruleRepository;
    @Autowired private MetricHealthRuleVersionRepository versionRepository;
    @Autowired private HealthScoringService scoringService;
    @Autowired private HealthAggregationService aggregationService;
    @Autowired private HealthScoreEngine engine;
    @Autowired private WeightResolver weightResolver;
    @Autowired private CurrentUserProvider users;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    // ==================================================================
    // 1. 树形数据
    // ==================================================================

    /**
     * 系统 → 大类 → 小类 → 指标 的层级 + 规则状态 + 计数，一次返回。
     */
    public Map<String, Object> tree(Long systemId) {
        HealthScoreEngine.Settings settings = engine.settings();
        List<MetricItem> items = activeItems();
        Map<Long, MetricCategory> categories = new HashMap<>();
        for (MetricCategory category : categoryRepository.findAll()) {
            categories.put(category.getId(), category);
        }
        Map<Long, MetricHealthRule> rules = new HashMap<>();
        for (MetricHealthRule rule : ruleRepository.findAll()) {
            rules.put(rule.getMetricId(), rule);
        }
        Map<Long, HealthScoringService.ValuePoint> latest = scoringService.latestValueIndex();
        Map<Long, List<HealthScoreEngine.Sample>> samples = scoringService.sampleIndex(90);
        Map<Long, HealthAggregationService.MetricScore> scores = aggregationService.latestScores();
        long staleBefore = java.time.Instant.now().toEpochMilli() - settings.staleDays() * 86_400_000L;

        List<SystemInfo> systems = systemInfoRepository.findAll().stream()
                .filter(system -> system.getStatus() == null || system.getStatus() == 1)
                .sorted(Comparator.comparing(SystemInfo::getId))
                .toList();

        int total = 0, exempt = 0, scored = 0, learning = 0, missing = 0;
        List<Map<String, Object>> systemNodes = new ArrayList<>();

        for (SystemInfo system : systems) {
            if (systemId != null && !systemId.equals(system.getId())) continue;
            List<MetricCategory> systemCategories = categoryRepository.findBySystemId(system.getId()).stream()
                    .filter(category -> category.getStatus() == null || category.getStatus() == 1)
                    .sorted(Comparator.comparing(MetricCategory::getId))
                    .toList();

            List<Map<String, Object>> categoryNodes = new ArrayList<>();
            for (MetricCategory category : systemCategories) {
                Map<String, List<MetricItem>> bySub = new LinkedHashMap<>();
                for (MetricItem item : items) {
                    if (!Objects.equals(item.getCategoryId(), category.getId())) continue;
                    bySub.computeIfAbsent(subNameOf(item), key -> new ArrayList<>()).add(item);
                }
                if (bySub.isEmpty()) continue;

                List<Map<String, Object>> subNodes = new ArrayList<>();
                int categoryExempt = 0;
                for (var entry : bySub.entrySet()) {
                    List<Map<String, Object>> metricNodes = new ArrayList<>();
                    int subExempt = 0;
                    for (MetricItem item : entry.getValue()) {
                        MetricHealthRule rule = rules.get(item.getId());
                        boolean isExempt = rule != null && rule.isExempt();
                        HealthAggregationService.MetricScore score = scores.get(item.getId());
                        HealthScoringService.ValuePoint value = latest.get(item.getId());
                        String state;
                        if (isExempt) {
                            state = HealthScoreEngine.STATE_EXEMPT;
                            exempt++;
                            subExempt++;
                        } else if (score != null && score.score() != null) {
                            state = HealthScoreEngine.STATE_SCORED;
                            scored++;
                        } else if (value != null && value.recordTime() != null
                                && value.recordTime().toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli() >= staleBefore) {
                            state = HealthScoreEngine.STATE_LEARNING;
                            learning++;
                        } else {
                            state = "missing";
                            missing++;
                        }
                        total++;
                        metricNodes.add(metricNode(item, rule, score, value, samples.get(item.getId()), state, settings));
                    }
                    categoryExempt += subExempt;
                    Map<String, Object> subNode = new LinkedHashMap<>();
                    subNode.put("name", entry.getKey());
                    subNode.put("metricCount", entry.getValue().size());
                    subNode.put("exemptCount", subExempt);
                    subNode.put("metrics", metricNodes);
                    subNodes.add(subNode);
                }
                Map<String, Object> categoryNode = new LinkedHashMap<>();
                categoryNode.put("categoryId", category.getId());
                categoryNode.put("name", category.getCategoryName());
                categoryNode.put("code", category.getCategoryCode());
                categoryNode.put("metricCount", bySub.values().stream().mapToInt(List::size).sum());
                categoryNode.put("exemptCount", categoryExempt);
                categoryNode.put("subcategories", subNodes);
                categoryNodes.add(categoryNode);
            }

            Map<String, Object> systemNode = new LinkedHashMap<>();
            systemNode.put("systemId", system.getId());
            systemNode.put("systemName", system.getSystemName());
            systemNode.put("categories", categoryNodes);
            systemNodes.add(systemNode);
        }

        int eligible = total - exempt;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("exempt", exempt);
        summary.put("eligible", eligible);
        summary.put("scored", scored);
        summary.put("learning", learning);
        summary.put("missing", missing);
        summary.put("coverageRate", eligible <= 0 ? 0 : Math.round(scored * 10000.0 / eligible) / 100.0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("systems", systemNodes);
        result.put("summary", summary);
        result.put("thresholds", Map.of("alarm", settings.alarmThreshold(), "warning", settings.warningThreshold()));
        result.put("config", config());
        return result;
    }

    private Map<String, Object> metricNode(MetricItem item, MetricHealthRule rule,
                                           HealthAggregationService.MetricScore score,
                                           HealthScoringService.ValuePoint value,
                                           List<HealthScoreEngine.Sample> samples,
                                           String state, HealthScoreEngine.Settings settings) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("metricId", item.getId());
        node.put("metricCode", item.getMetricCode());
        node.put("metricName", item.getMetricName());
        node.put("unit", item.getUnit());
        node.put("metricType", item.getMetricType());
        node.put("currentValue", value == null ? null : value.value());
        node.put("currentValueTime", value == null ? null : value.recordTime());
        node.put("dataOrigin", value == null ? null : value.origin());
        node.put("state", state);
        node.put("score", score == null ? null : score.score());
        node.put("status", score == null ? null : score.status());
        node.put("mode", score == null || score.mode() == null
                ? (rule != null && rule.isExempt() ? HealthScoreEngine.MODE_EXEMPT
                   : (rule != null && rule.hasConfirmedBaseline() ? HealthScoreEngine.MODE_MANUAL : HealthScoreEngine.MODE_DYNAMIC))
                : score.mode());
        node.put("exempt", rule != null && rule.isExempt());
        node.put("exemptReason", rule == null ? null : rule.getExemptReason());
        node.put("direction", rule != null ? rule.getDirection() : "lower_better");
        node.put("directionSource", rule != null ? rule.getDirectionSource() : "auto");
        node.put("baselineValue", rule == null ? null : rule.getBaselineValue());
        node.put("baselineStatus", rule == null ? "none" : rule.getBaselineStatus());
        node.put("tolerance", rule == null ? BigDecimal.ZERO : rule.getTolerance());
        node.put("penaltyValue", rule == null ? null : rule.getPenaltyValue());
        node.put("rangeMin", rule == null ? null : rule.getRangeMin());
        node.put("rangeMax", rule == null ? null : rule.getRangeMax());
        node.put("windowDays", rule == null ? 90 : rule.getWindowDays());
        node.put("minSamples", rule == null ? 30 : rule.getMinSamples());
        node.put("seasonality", rule == null ? "none" : rule.getSeasonality());
        node.put("version", rule == null ? 0 : rule.getVersion());
        node.put("ruleId", rule == null ? null : rule.getId());
        node.put("tiers", rule == null ? null : rule.getTiers());
        Map<String, Object> stats = stats(samples);
        node.put("stats", stats);
        node.put("suggestedBaseline", stats.get("median"));
        node.put("thresholds", Map.of("alarm", settings.alarmThreshold(), "warning", settings.warningThreshold()));
        return node;
    }

    /** 近 90 天样本统计（建议基线用）：中位数、MAD、四分位、样本数、时间跨度 */
    private Map<String, Object> stats(List<HealthScoreEngine.Sample> samples) {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("sampleCount", 0);
        if (samples == null || samples.isEmpty()) return empty;
        List<HealthScoreEngine.Sample> usable = samples.stream()
                .filter(sample -> sample.value() != null)
                .sorted(Comparator.comparing(HealthScoreEngine.Sample::recordTime))
                .toList();
        List<Double> values = usable.stream().map(s -> s.value().doubleValue()).sorted().toList();
        double median = median(values);
        double mad = median(values.stream().map(v -> Math.abs(v - median)).sorted().toList());
        long spanDays = Duration.between(usable.get(0).recordTime(),
                usable.get(usable.size() - 1).recordTime()).toDays();
        long collected = usable.stream().filter(s -> "collected".equals(s.origin())).count();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sampleCount", values.size());
        map.put("collectedCount", collected);
        map.put("median", BigDecimal.valueOf(median).setScale(4, RoundingMode.HALF_UP));
        map.put("mad", BigDecimal.valueOf(mad).setScale(4, RoundingMode.HALF_UP));
        map.put("p25", BigDecimal.valueOf(percentile(values, 0.25)).setScale(4, RoundingMode.HALF_UP));
        map.put("p75", BigDecimal.valueOf(percentile(values, 0.75)).setScale(4, RoundingMode.HALF_UP));
        map.put("min", BigDecimal.valueOf(values.get(0)).setScale(4, RoundingMode.HALF_UP));
        map.put("max", BigDecimal.valueOf(values.get(values.size() - 1)).setScale(4, RoundingMode.HALF_UP));
        map.put("spanDays", spanDays);
        map.put("sampleBasedOnMock", collected == 0);
        return map;
    }

    private static double percentile(List<Double> sorted, double ratio) {
        if (sorted.isEmpty()) return 0;
        double index = ratio * (sorted.size() - 1);
        int low = (int) Math.floor(index);
        int high = (int) Math.ceil(index);
        if (low == high) return sorted.get(low);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (index - low);
    }

    private static double median(List<Double> sorted) {
        if (sorted.isEmpty()) return 0;
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    // ==================================================================
    // 2. 建议基线
    // ==================================================================

    public Map<String, Object> suggest(Long metricId) {
        MetricItem item = itemRepository.findById(metricId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "指标不存在"));
        Map<Long, List<HealthScoreEngine.Sample>> samples = scoringService.sampleIndex(90);
        HealthScoringService.ValuePoint value = scoringService.latestValueIndex().get(metricId);
        MetricHealthRule rule = ruleRepository.findByMetricId(metricId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metricId", metricId);
        result.put("metricName", item.getMetricName());
        result.put("unit", item.getUnit());
        result.put("currentValue", value == null ? null : value.value());
        result.put("currentValueTime", value == null ? null : value.recordTime());
        result.put("stats", stats(samples.get(metricId)));
        result.put("rule", rule);
        result.put("suggestedDirection", rule != null ? rule.getDirection() : suggestDirection(item));
        return result;
    }

    private String suggestDirection(MetricItem item) {
        String name = item.getMetricName() == null ? "" : item.getMetricName();
        if (name.contains("率") || "%".equals(item.getUnit())) return HealthScoreEngine.DIRECTION_HIGHER;
        return HealthScoreEngine.DIRECTION_LOWER;
    }

    // ==================================================================
    // 3. 试算器
    // ==================================================================

    /**
     * 给定规则参数与当前值，返回该指标的得分与分数曲线（拖动当前值即可看到效果）。
     */
    public Map<String, Object> preview(Map<String, Object> payload) {
        Long metricId = asLong(payload.get("metricId"));
        if (metricId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metricId 必填");
        }
        MetricItem item = itemRepository.findById(metricId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "指标不存在"));
        MetricHealthRule stored = ruleRepository.findByMetricId(metricId).orElse(null);
        MetricHealthRule rule = mergeRule(stored, item, payload);
        HealthScoreEngine.Settings settings = engine.settings();
        List<HealthScoreEngine.Sample> samples = scoringService.sampleIndex(90)
                .getOrDefault(metricId, List.of());
        HealthScoringService.ValuePoint latest = scoringService.latestValueIndex().get(metricId);
        BigDecimal current = asDecimal(payload.get("currentValue"));
        if (current == null && latest != null) current = latest.value();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metricId", metricId);
        result.put("metricName", item.getMetricName());
        result.put("unit", item.getUnit());
        result.put("currentValue", current);
        result.put("stats", stats(samples));

        if (current != null) {
            HealthScoreEngine.Outcome outcome = engine.evaluate(rule, current,
                    latest == null ? "unknown" : latest.origin(), samples, LocalDateTime.now(), settings);
            Map<String, Object> now = new LinkedHashMap<>();
            now.put("score", outcome.score());
            now.put("status", outcome.status());
            now.put("mode", outcome.mode());
            now.put("baselineUsed", outcome.baselineUsed());
            now.put("deviation", outcome.deviation());
            now.put("learnState", outcome.learnState());
            now.put("message", outcome.message());
            now.put("trace", readJson(outcome.traceJson()));
            result.put("current", now);
        }

        result.put("curve", curve(rule, item, samples, current, settings));
        return result;
    }

    /**
     * 生成分数曲线：横轴指标值、纵轴分数。人工基线按“基线/扣到 0 值”定范围；
     * 动态基线按中位数 ± σ̂ 定范围，保证曲线覆盖有意义的区间。
     */
    private List<Map<String, Object>> curve(MetricHealthRule rule, MetricItem item,
                                            List<HealthScoreEngine.Sample> samples,
                                            BigDecimal current, HealthScoreEngine.Settings settings) {
        List<Map<String, Object>> points = new ArrayList<>();
        double from = 0;
        double to;
        if (rule.hasConfirmedBaseline()) {
            double baseline = rule.getBaselineValue().doubleValue();
            double penalty = rule.getPenaltyValue() != null ? rule.getPenaltyValue().doubleValue()
                    : baseline * 2;
            double max = Math.max(Math.max(penalty, baseline), current == null ? 0 : current.doubleValue());
            max = Math.max(max * 1.2, baseline + 1);
            if (HealthScoreEngine.DIRECTION_HIGHER.equals(rule.getDirection())) {
                from = Math.min(Math.min(penalty, baseline), current == null ? baseline : current.doubleValue());
                from = Math.min(from, baseline) * 0.8;
                to = Math.max(baseline * 1.2, baseline + 1);
            } else {
                from = 0;
                to = max;
            }
        } else {
            List<Double> values = samples.stream().filter(s -> s.value() != null)
                    .map(s -> s.value().doubleValue()).sorted().toList();
            if (values.isEmpty()) {
                double anchor = current == null ? 1 : current.doubleValue();
                from = 0;
                to = Math.max(anchor * 2, 1);
            } else {
                double med = median(values);
                double mad = median(values.stream().map(v -> Math.abs(v - med)).sorted().toList());
                double sigma = Math.max(1.4826 * mad, Math.max(Math.abs(med) * 0.05, 0.5));
                from = Math.max(0, med - 3 * sigma);
                to = med + 8 * sigma;
            }
        }
        if (to <= from) to = from + 1;

        int steps = 40;
        for (int i = 0; i <= steps; i++) {
            double value = from + (to - from) * i / steps;
            HealthScoreEngine.Outcome outcome = engine.evaluate(rule, BigDecimal.valueOf(value),
                    "curve", samples, LocalDateTime.now(), settings);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("value", BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP));
            point.put("score", outcome.score());
            points.add(point);
        }
        return points;
    }

    // ==================================================================
    // 4. 批量保存
    // ==================================================================

    /**
     * 增量保存白名单与基线规则，必须带变更原因；每条变更写审计，成功后立即重算。
     *
     * <p>payload 形如：
     * <pre>
     * {
     *   "reason": "稽核类不计分，2026-11-17 业务确认",
     *   "exemptChanges": [{"metricId":35,"exempt":true,"reason":"...","version":1}],
     *   "ruleChanges": [{"metricId":1,"direction":"lower_better","baselineValue":5,
     *                    "tolerance":0,"penaltyValue":10,"baselineStatus":"confirmed",
     *                    "tiers":[...],"windowDays":90,"minSamples":30,"version":2}]
     * }
     * </pre>
     */
    @Transactional
    public Map<String, Object> batchSave(Map<String, Object> payload) {
        String reason = asString(payload.get("reason"));
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须填写变更原因，便于审计复盘");
        }
        List<Map<String, Object>> exemptChanges = asList(payload.get("exemptChanges"));
        List<Map<String, Object>> ruleChanges = asList(payload.get("ruleChanges"));
        if (exemptChanges.isEmpty() && ruleChanges.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有需要保存的变更");
        }

        int exemptCount = 0;
        int ruleCount = 0;
        for (Map<String, Object> change : exemptChanges) {
            Long metricId = asLong(change.get("metricId"));
            if (metricId == null) continue;
            MetricItem item = itemRepository.findById(metricId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "指标不存在: " + metricId));
            MetricHealthRule rule = ruleRepository.findByMetricId(metricId).orElseGet(() -> newRule(item, metricId));
            checkVersion(change, rule);
            String before = serialize(rule);
            boolean exempt = Boolean.TRUE.equals(change.get("exempt"));
            rule.setExempt(exempt ? 1 : 0);
            rule.setExemptReason(exempt
                    ? (asString(change.get("reason")) == null ? reason : asString(change.get("reason")))
                    : null);
            bump(rule);
            MetricHealthRule saved = ruleRepository.save(rule);
            audit(saved.getMetricId(), "rule", before, serialize(saved), reason, saved.getVersion());
            exemptCount++;
        }

        for (Map<String, Object> change : ruleChanges) {
            Long metricId = asLong(change.get("metricId"));
            if (metricId == null) continue;
            MetricItem item = itemRepository.findById(metricId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "指标不存在: " + metricId));
            MetricHealthRule rule = ruleRepository.findByMetricId(metricId).orElseGet(() -> newRule(item, metricId));
            checkVersion(change, rule);
            String before = serialize(rule);
            applyRuleChange(rule, change);
            bump(rule);
            MetricHealthRule saved = ruleRepository.save(rule);
            audit(saved.getMetricId(), "rule", before, serialize(saved),
                    asString(change.get("reason")) == null ? reason : asString(change.get("reason")),
                    saved.getVersion());
            ruleCount++;
        }

        HealthScoringService.RunSummary summary = scoringService.runAll();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exemptChanged", exemptCount);
        result.put("ruleChanged", ruleCount);
        result.put("reason", reason);
        result.put("recalculated", Map.of(
                "scored", summary.scored(),
                "learning", summary.learning(),
                "noData", summary.noData(),
                "exempt", summary.exempt(),
                "recordTime", summary.recordTime()));
        return result;
    }

    private void checkVersion(Map<String, Object> change, MetricHealthRule rule) {
        Long expected = asLong(change.get("version"));
        if (expected == null || rule.getId() == null) return;
        int current = rule.getVersion() == null ? 1 : rule.getVersion();
        if (expected != current) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "规则已被其他人修改（当前版本 " + current + "，提交版本 " + expected + "），请刷新后重试");
        }
    }

    private void applyRuleChange(MetricHealthRule rule, Map<String, Object> change) {
        String direction = asString(change.get("direction"));
        if (direction != null) {
            if (!List.of(HealthScoreEngine.DIRECTION_LOWER, HealthScoreEngine.DIRECTION_HIGHER,
                    HealthScoreEngine.DIRECTION_RANGE, HealthScoreEngine.DIRECTION_ZERO).contains(direction)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的方向: " + direction);
            }
            rule.setDirection(direction);
            rule.setDirectionSource("manual");
        }
        BigDecimal baseline = asDecimal(change.get("baselineValue"));
        String status = asString(change.get("baselineStatus"));
        if (baseline != null) {
            rule.setBaselineValue(baseline);
            rule.setBaselineStatus(status == null || "none".equals(status) ? "confirmed" : status);
        } else if ("none".equals(status)) {
            rule.setBaselineValue(null);
            rule.setBaselineStatus("none");
        } else if (status != null) {
            rule.setBaselineStatus(status);
        }
        BigDecimal tolerance = asDecimal(change.get("tolerance"));
        if (tolerance != null) rule.setTolerance(tolerance.max(BigDecimal.ZERO));
        BigDecimal penalty = asDecimal(change.get("penaltyValue"));
        if (penalty != null) rule.setPenaltyValue(penalty);
        BigDecimal rangeMin = asDecimal(change.get("rangeMin"));
        BigDecimal rangeMax = asDecimal(change.get("rangeMax"));
        if (rangeMin != null) rule.setRangeMin(rangeMin);
        if (rangeMax != null) rule.setRangeMax(rangeMax);
        Object tiers = change.get("tiers");
        if (tiers != null) rule.setTiers(tiers instanceof String text ? text : serialize(tiers));
        Long windowDays = asLong(change.get("windowDays"));
        if (windowDays != null) rule.setWindowDays((int) Math.max(7, Math.min(365, windowDays)));
        Long minSamples = asLong(change.get("minSamples"));
        if (minSamples != null) rule.setMinSamples((int) Math.max(3, Math.min(1000, minSamples)));
        String seasonality = asString(change.get("seasonality"));
        if (seasonality != null) rule.setSeasonality(seasonality);
        Long statusValue = asLong(change.get("status"));
        if (statusValue != null) rule.setStatus(statusValue.intValue());
        if (rule.hasConfirmedBaseline() && rule.getPenaltyValue() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "人工基线必须同时填写「扣到 0 分的值」，否则无法计算扣分幅度");
        }
    }

    private MetricHealthRule newRule(MetricItem item, Long metricId) {
        MetricHealthRule rule = new MetricHealthRule();
        rule.setMetricId(metricId);
        rule.setExempt(0);
        rule.setDirection(suggestDirection(item));
        rule.setDirectionSource("auto");
        rule.setTolerance(BigDecimal.ZERO);
        rule.setWindowDays(90);
        rule.setMinSamples(30);
        rule.setSeasonality("none");
        rule.setBaselineStatus("none");
        rule.setStatus(1);
        rule.setVersion(1);
        return rule;
    }

    private void bump(MetricHealthRule rule) {
        rule.setVersion(rule.getVersion() == null ? 1 : rule.getVersion() + 1);
        rule.setUpdatedBy(users.operatorName());
    }

    private void audit(Long metricId, String scopeType, String before, String after, String reason, Integer version) {
        MetricHealthRuleVersion row = new MetricHealthRuleVersion();
        row.setScopeType(scopeType);
        row.setMetricId(metricId);
        row.setSource("manual");
        row.setOperatorId(users.currentUser().map(user -> user.getId()).orElse(null));
        row.setOperatorName(users.operatorName());
        row.setBeforeJson(before);
        row.setAfterJson(after);
        row.setReason(reason);
        row.setVersion(version == null ? 1 : version);
        row.setChangedAt(LocalDateTime.now());
        versionRepository.save(row);
    }

    // ==================================================================
    // 5. 变更历史 / 新旧对比 / 配置
    // ==================================================================

    public List<Map<String, Object>> history(Long metricId, int limit) {
        List<MetricHealthRuleVersion> rows = metricId == null
                ? versionRepository.findTop100ByOrderByChangedAtDesc()
                : versionRepository.findTop50ByMetricIdOrderByChangedAtDesc(metricId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (MetricHealthRuleVersion row : rows) {
            if (result.size() >= limit) break;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", row.getId());
            map.put("metricId", row.getMetricId());
            map.put("scopeType", row.getScopeType());
            map.put("operatorName", row.getOperatorName());
            map.put("reason", row.getReason());
            map.put("version", row.getVersion());
            map.put("changedAt", row.getChangedAt());
            map.put("before", readJson(row.getBeforeJson()));
            map.put("after", readJson(row.getAfterJson()));
            result.add(map);
        }
        return result;
    }

    /**
     * 新旧算法对比（双跑期用）：同一指标最近一次旧算法分数 vs 新算法分数。
     */
    public Map<String, Object> compare() {
        Map<Long, Map<String, Object>> legacy = latestByAlgorithm("legacy");
        Map<Long, Map<String, Object>> current = latestByAlgorithm(HealthScoreEngine.ALGORITHM_VERSION);
        Map<Long, MetricItem> items = new HashMap<>();
        for (MetricItem item : itemRepository.findAll()) items.put(item.getId(), item);

        List<Map<String, Object>> rows = new ArrayList<>();
        int changed = 0;
        for (Map.Entry<Long, Map<String, Object>> entry : current.entrySet()) {
            Map<String, Object> old = legacy.get(entry.getKey());
            if (old == null) continue;
            int oldScore = ((Number) old.get("health_score")).intValue();
            int newScore = ((Number) entry.getValue().get("health_score")).intValue();
            MetricItem item = items.get(entry.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metricId", entry.getKey());
            row.put("metricName", item == null ? null : item.getMetricName());
            row.put("categoryName", entry.getValue().get("category_name"));
            row.put("oldScore", oldScore);
            row.put("newScore", newScore);
            row.put("delta", newScore - oldScore);
            row.put("newMode", entry.getValue().get("mode"));
            row.put("dataOrigin", entry.getValue().get("data_origin"));
            rows.add(row);
            if (Math.abs(newScore - oldScore) >= 5) changed++;
        }
        rows.sort(Comparator.comparingInt(row -> -Math.abs((int) row.get("delta"))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("compared", rows.size());
        result.put("changedOver5", changed);
        result.put("rows", rows);
        result.put("note", "旧算法行（algorithm_version=legacy）保留未删，可随时切回；本对比只比同一指标最近一次新旧分数");
        return result;
    }

    private Map<Long, Map<String, Object>> latestByAlgorithm(String algorithmVersion) {
        Map<Long, Map<String, Object>> map = new HashMap<>();
        String sql = """
                SELECT h.metric_id, h.category_name, h.health_score, h.mode, h.data_origin, h.record_time
                FROM metric_health_history h
                JOIN (SELECT metric_id, MAX(record_time) AS rt FROM metric_health_history
                      WHERE algorithm_version = ? GROUP BY metric_id) x
                  ON x.metric_id = h.metric_id AND x.rt = h.record_time
                WHERE h.algorithm_version = ?
                """;
        for (Map<String, Object> row : jdbc.queryForList(sql, algorithmVersion, algorithmVersion)) {
            map.put(((Number) row.get("metric_id")).longValue(), row);
        }
        return map;
    }

    /** 页面顶部口径：告警线/关注线、覆盖率、最近一次算分时间 */
    public Map<String, Object> config() {
        HealthScoreEngine.Settings settings = engine.settings();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alarmThreshold", settings.alarmThreshold());
        result.put("warningThreshold", settings.warningThreshold());
        result.put("staleDays", settings.staleDays());
        result.put("minDays", settings.minDays());
        result.put("minSamples", settings.minSamples());
        result.put("mockFallback", settings.mockFallback());
        result.put("sampleMode", settings.sampleMode());
        List<Map<String, Object>> lastRun = jdbc.queryForList("""
                SELECT MAX(record_time) AS last_time, COUNT(*) AS rows_last_batch
                FROM metric_health_history
                WHERE algorithm_version = 'rule-v1'
                  AND record_time = (SELECT MAX(record_time) FROM metric_health_history WHERE algorithm_version = 'rule-v1')
                """);
        if (!lastRun.isEmpty()) {
            result.put("lastRunTime", lastRun.get(0).get("last_time"));
            result.put("lastRunRows", lastRun.get(0).get("rows_last_batch"));
        }
        result.put("systems", systemInfoRepository.findAll().stream()
                .filter(system -> system.getStatus() == null || system.getStatus() == 1)
                .map(system -> Map.of("systemId", system.getId(), "systemName", system.getSystemName()))
                .toList());
        return result;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private MetricHealthRule mergeRule(MetricHealthRule stored, MetricItem item, Map<String, Object> payload) {
        MetricHealthRule rule = new MetricHealthRule();
        rule.setMetricId(item.getId());
        rule.setExempt(stored != null && stored.isExempt() ? 1 : 0);
        rule.setExemptReason(stored == null ? null : stored.getExemptReason());
        String direction = asString(payload.get("direction"));
        rule.setDirection(direction != null ? direction
                : stored != null ? stored.getDirection() : suggestDirection(item));
        rule.setDirectionSource("manual");
        BigDecimal baseline = asDecimal(payload.get("baselineValue"));
        boolean baselineProvided = payload.containsKey("baselineValue");
        rule.setBaselineValue(baselineProvided ? baseline : stored == null ? null : stored.getBaselineValue());
        BigDecimal tolerance = asDecimal(payload.get("tolerance"));
        rule.setTolerance(tolerance != null ? tolerance
                : stored == null || stored.getTolerance() == null ? BigDecimal.ZERO : stored.getTolerance());
        BigDecimal penalty = asDecimal(payload.get("penaltyValue"));
        rule.setPenaltyValue(penaltyProvided(payload) ? penalty : stored == null ? null : stored.getPenaltyValue());
        rule.setRangeMin(asDecimal(payload.get("rangeMin")));
        rule.setRangeMax(asDecimal(payload.get("rangeMax")));
        String baselineStatus = asString(payload.get("baselineStatus"));
        rule.setBaselineStatus(baselineStatus != null ? baselineStatus
                : rule.getBaselineValue() != null ? "confirmed" : "none");
        Object tiers = payload.get("tiers");
        if (tiers != null) rule.setTiers(tiers instanceof String text ? text : serialize(tiers));
        else if (stored != null) rule.setTiers(stored.getTiers());
        Long windowDays = asLong(payload.get("windowDays"));
        rule.setWindowDays(windowDays != null ? windowDays.intValue() : stored == null ? 90 : stored.getWindowDays());
        Long minSamples = asLong(payload.get("minSamples"));
        rule.setMinSamples(minSamples != null ? minSamples.intValue() : stored == null ? 30 : stored.getMinSamples());
        String seasonality = asString(payload.get("seasonality"));
        rule.setSeasonality(seasonality != null ? seasonality : stored == null ? "none" : stored.getSeasonality());
        rule.setStatus(1);
        rule.setVersion(0);
        return rule;
    }

    private List<MetricItem> activeItems() {
        return itemRepository.findAll().stream()
                .filter(item -> item.getStatus() == null || item.getStatus() == 1)
                .toList();
    }

    private static String subNameOf(MetricItem item) {
        return item.getSubCategoryName() == null || item.getSubCategoryName().isBlank()
                ? "未分类" : item.getSubCategoryName();
    }

    private String serialize(Object value) {
        try {
            return value instanceof String text ? text : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Object readJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (JsonProcessingException e) {
            return raw;
        }
    }

    private static boolean penaltyProvided(Map<String, Object> payload) {
        return payload.containsKey("penaltyValue");
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long asLong(Object value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object row : list) {
                if (row instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
            }
            return result;
        }
        return List.of();
    }
}
