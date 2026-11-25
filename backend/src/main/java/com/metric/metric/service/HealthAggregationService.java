package com.metric.metric.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.metric.entity.CategoryHealthHistory;
import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricHealthRule;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.entity.SubCategoryHealthHistory;
import com.metric.metric.repository.CategoryHealthHistoryRepository;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.MetricHealthRuleRepository;
import com.metric.metric.repository.MetricItemRepository;
import com.metric.metric.repository.SubCategoryHealthHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 健康度聚合：指标 → 小类 → 大类 → 系统。
 *
 * <p>改造前聚合的 5 个静默失效（见设计文档 2.3）在这里统一修掉：
 * <ol>
 *   <li>小类权重按中文名匹配（历史编码键在 WeightResolver 里已归一）；</li>
 *   <li>系统分不再硬编码 4 个大类，按系统名下全部大类算；</li>
 *   <li>权重只对“真正参与计算的指标”重新归一，缺数指标不再拉低分数；</li>
 *   <li>输出覆盖率（参与/应有）与最差指标，避免平均分掩盖问题；</li>
 *   <li>状态按比例规则判定（alarm 占比 ≥ 20% 或存在 &lt; 40 分），不再“随便 1 个异常就整体 alarm”。</li>
 * </ol>
 */
@Service
public class HealthAggregationService {

    /** 单个指标的算分结果（聚合输入） */
    public record MetricScore(long metricId, String metricCode, String metricName,
                              String categoryName, String subCategoryName,
                              Integer score, String status, String mode, String dataOrigin) {}

    /** 最差指标：聚合层必须暴露，否则平均分会把严重问题稀释掉（业务明确 D4） */
    public record WorstMetric(long metricId, String metricCode, String metricName, Integer score, String status) {}

    /** 一个聚合节点（大类/小类/系统） */
    public record Node(String kind, String key, String name, String categoryName, Integer score, String status,
                       int participants, int expected, BigDecimal weight, BigDecimal weightShare,
                       Map<String, Integer> statusCounts, Map<String, Integer> modeCounts,
                       WorstMetric worst, List<Node> children, String traceJson) {}

    @Autowired private MetricItemRepository itemRepository;
    @Autowired private MetricCategoryRepository categoryRepository;
    @Autowired private MetricHealthRuleRepository ruleRepository;
    @Autowired private SubCategoryHealthHistoryRepository subCategoryHealthRepository;
    @Autowired private CategoryHealthHistoryRepository categoryHealthRepository;
    @Autowired private WeightResolver weightResolver;
    @Autowired private HealthScoreEngine engine;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    // ==================================================================
    // 对外：计算 + 落库
    // ==================================================================

    /**
     * 对全部大类做聚合并写入 category_health_history / sub_category_health_history。
     *
     * @param scores 指标级算分结果（指标 → 分数/状态/模式）
     * @param now    记录时间
     */
    @Transactional
    public List<Node> aggregateAndPersist(Map<Long, MetricScore> scores, LocalDateTime now) {
        HealthScoreEngine.Settings settings = engine.settings();
        WeightResolver.WeightSnapshot weights = weightResolver.snapshot();
        List<MetricItem> items = activeItems();
        Map<Long, String> categoryNames = categoryNames();
        Map<Long, MetricHealthRule> rules = loadRules();
        List<Node> categories = buildCategoryNodes(items, categoryNames, rules, scores, weights, settings);

        for (Node category : categories) {
            if (category.score() == null) continue;
            saveCategory(category, now);
            for (Node sub : category.children()) {
                if (sub.score() != null) saveSubCategory(sub, now);
            }
        }
        return categories;
    }

    /** 系统层节点：按系统名下大类加权，输出覆盖率与最差指标 */
    public Node systemNode(long systemId, List<Node> categories,
                           WeightResolver.WeightSnapshot weights,
                           HealthScoreEngine.Settings settings) {
        List<Node> children = new ArrayList<>();
        for (MetricCategory category : categoryRepository.findBySystemId(systemId)) {
            if (category.getStatus() != null && category.getStatus() != 1) continue;
            categories.stream()
                    .filter(node -> node.key().equals(String.valueOf(category.getId())))
                    .findFirst().ifPresent(children::add);
        }

        BigDecimal numerator = BigDecimal.ZERO;
        BigDecimal denominator = BigDecimal.ZERO;
        for (Node child : children) {
            if (child.score() == null) continue;
            BigDecimal weight = weights.categoryWeight(systemId, Long.parseLong(child.key()));
            numerator = numerator.add(weight.multiply(BigDecimal.valueOf(child.score())));
            denominator = denominator.add(weight);
        }
        Integer score = denominator.signum() > 0
                ? numerator.divide(denominator, 0, RoundingMode.HALF_UP).intValue() : null;

        int participants = children.stream().mapToInt(Node::participants).sum();
        int expected = children.stream().mapToInt(Node::expected).sum();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, Integer> modeCounts = new LinkedHashMap<>();
        for (Node child : children) {
            child.statusCounts().forEach((status, count) -> statusCounts.merge(status, count, Integer::sum));
            child.modeCounts().forEach((mode, count) -> modeCounts.merge(mode, count, Integer::sum));
        }
        WorstMetric worst = worstOf(children);
        String status = statusRule(statusCounts, worst, settings);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("level", "system");
        trace.put("systemId", systemId);
        trace.put("formula", "系统健康度 = Σ(大类分数 × 大类权重) ÷ Σ(权重)，权重按本次参与计算的大类重新归一");
        trace.put("coverage", Map.of("participants", participants, "expected", expected));
        trace.put("categories", children.stream().map(child -> Map.of(
                "name", Objects.toString(child.name(), ""),
                "score", child.score() == null ? "" : child.score(),
                "weight", weights.categoryWeight(systemId, Long.parseLong(child.key())),
                "participants", child.participants(),
                "expected", child.expected())).toList());
        if (worst != null) trace.put("worst", worstMap(worst));

        return new Node("system", String.valueOf(systemId), null, null, score, status,
                participants, expected, BigDecimal.ONE, BigDecimal.ONE,
                statusCounts, modeCounts, worst, children, traceJson(trace));
    }

    public Node systemNode(long systemId, List<Node> categories, WeightResolver.WeightSnapshot weights) {
        return systemNode(systemId, categories, weights, engine.settings());
    }

    // ==================================================================
    // 对外：实时快照（页面用，不落库）
    // ==================================================================

    /**
     * 读取“新算法”最新一次打分，实时聚合某系统的健康度树。
     * 只取 mode 非空的行，避免改造前的历史分数混进来（双跑期旧行仍保留，可另做对比）。
     */
    public Map<String, Object> liveSystemSummary(long systemId) {
        HealthScoreEngine.Settings settings = engine.settings();
        WeightResolver.WeightSnapshot weights = weightResolver.snapshot();
        List<MetricItem> items = activeItems();
        Map<Long, MetricHealthRule> rules = loadRules();
        Map<Long, MetricScore> scores = latestScores();
        List<Node> allCategories = buildCategoryNodes(items, categoryNames(), rules, scores, weights, settings);
        Node system = systemNode(systemId, allCategories, weights, settings);

        Set<Long> systemMetricIds = systemMetricIds(systemId);
        int exempt = 0;
        int learning = 0;
        for (MetricItem item : items) {
            if (!systemMetricIds.contains(item.getId())) continue;
            MetricHealthRule rule = rules.get(item.getId());
            if (rule != null && rule.isExempt()) {
                exempt++;
            } else if (!scores.containsKey(item.getId())) {
                learning++;
            }
        }
        int expected = system.expected();
        int missing = Math.max(0, expected - system.participants() - learning);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("systemId", systemId);
        result.put("systemName", systemName(systemId));
        result.put("score", system.score());
        result.put("status", system.status());
        result.put("participants", system.participants());
        result.put("expected", expected);
        result.put("coverageRate", rate(system.participants(), expected));
        result.put("formula", "系统健康度 = Σ(大类分数 × 大类权重) ÷ Σ(权重)");
        result.put("alarmThreshold", settings.alarmThreshold());
        result.put("warningThreshold", settings.warningThreshold());
        result.put("worst", worstMap(system.worst()));
        result.put("modeCounts", system.modeCounts());

        BigDecimal shareBase = system.children().stream()
                .filter(node -> node.score() != null)
                .map(node -> weights.categoryWeight(systemId, Long.parseLong(node.key())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> categoryList = new ArrayList<>();
        for (Node child : system.children()) {
            Map<String, Object> row = new LinkedHashMap<>();
            BigDecimal weight = weights.categoryWeight(systemId, Long.parseLong(child.key()));
            row.put("categoryId", Long.parseLong(child.key()));
            row.put("name", child.name());
            row.put("score", child.score());
            row.put("status", child.status());
            row.put("participants", child.participants());
            row.put("expected", child.expected());
            row.put("coverageRate", rate(child.participants(), child.expected()));
            row.put("weight", weight);
            // 没有分数的大类不参与加权，权重占比记 0，避免“占比 25% 却完全没算分”的误导
            row.put("weightShare", child.score() == null || shareBase.signum() <= 0
                    ? BigDecimal.ZERO
                    : weight.divide(shareBase, 4, RoundingMode.HALF_UP));
            row.put("worst", worstMap(child.worst()));
            row.put("modeCounts", child.modeCounts());
            row.put("subcategories", child.children().stream().map(sub -> {
                Map<String, Object> subRow = new LinkedHashMap<>();
                subRow.put("name", sub.name());
                subRow.put("score", sub.score());
                subRow.put("status", sub.status());
                subRow.put("participants", sub.participants());
                subRow.put("expected", sub.expected());
                subRow.put("coverageRate", rate(sub.participants(), sub.expected()));
                subRow.put("weight", sub.weight());
                subRow.put("worst", worstMap(sub.worst()));
                return subRow;
            }).toList());
            categoryList.add(row);
        }
        result.put("categories", categoryList);

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("participants", system.participants());
        coverage.put("expected", expected);
        coverage.put("exempt", exempt);
        coverage.put("learning", learning);
        coverage.put("missing", missing);
        result.put("coverage", coverage);
        return result;
    }

    // ==================================================================
    // 构建节点
    // ==================================================================

    private List<Node> buildCategoryNodes(List<MetricItem> items, Map<Long, String> categoryNames,
                                          Map<Long, MetricHealthRule> rules, Map<Long, MetricScore> scores,
                                          WeightResolver.WeightSnapshot weights,
                                          HealthScoreEngine.Settings settings) {
        Map<Long, Map<String, List<MetricItem>>> grouped = new LinkedHashMap<>();
        for (MetricItem item : items) {
            grouped.computeIfAbsent(item.getCategoryId(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(subNameOf(item), key -> new ArrayList<>()).add(item);
        }

        List<Node> categories = new ArrayList<>();
        for (var categoryEntry : grouped.entrySet()) {
            Long categoryId = categoryEntry.getKey();
            String categoryName = categoryNames.getOrDefault(categoryId, "未分类大类");
            List<Node> subNodes = new ArrayList<>();
            for (var subEntry : categoryEntry.getValue().entrySet()) {
                subNodes.add(buildGroup("subcategory", subEntry.getKey(), categoryName,
                        subEntry.getValue(), rules, scores, weights, settings,
                        weights.subcategoryWeight(categoryId, subEntry.getKey())));
            }
            // 小类按得分从低到高，最差的排前面（页面直接照此顺序展示）
            subNodes.sort(Comparator.comparing(node -> node.score() == null ? Integer.MAX_VALUE : node.score()));

            Integer score = weightedAverage(subNodes);
            int participants = subNodes.stream().mapToInt(Node::participants).sum();
            int expected = subNodes.stream().mapToInt(Node::expected).sum();
            Map<String, Integer> statusCounts = new LinkedHashMap<>();
            Map<String, Integer> modeCounts = new LinkedHashMap<>();
            for (Node sub : subNodes) {
                sub.statusCounts().forEach((status, count) -> statusCounts.merge(status, count, Integer::sum));
                sub.modeCounts().forEach((mode, count) -> modeCounts.merge(mode, count, Integer::sum));
            }
            // 状态判定用“最差指标”，不看小类均分：均分 97 但里面有 1 个 0 分指标时，大类仍然是 alarm
            WorstMetric worst = worstOf(subNodes);
            String status = statusRule(statusCounts, worst, settings);

            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("level", "category");
            trace.put("categoryId", categoryId);
            trace.put("formula", "大类健康度 = Σ(小类分数 × 小类权重) ÷ Σ(权重)，只对参与计算的小类归一");
            trace.put("coverage", Map.of("participants", participants, "expected", expected));
            if (worst != null) trace.put("worst", worstMap(worst));

            categories.add(new Node("category", String.valueOf(categoryId), categoryName, categoryName,
                    score, status, participants, expected, BigDecimal.ONE, BigDecimal.ONE,
                    statusCounts, modeCounts, worst, subNodes, traceJson(trace)));
        }
        return categories;
    }

    private Node buildGroup(String kind, String name, String categoryName, List<MetricItem> items,
                            Map<Long, MetricHealthRule> rules, Map<Long, MetricScore> scores,
                            WeightResolver.WeightSnapshot weights, HealthScoreEngine.Settings settings,
                            BigDecimal weight) {
        List<MetricScore> participants = new ArrayList<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, Integer> modeCounts = new LinkedHashMap<>();
        int expected = 0;
        BigDecimal numerator = BigDecimal.ZERO;
        BigDecimal denominator = BigDecimal.ZERO;

        for (MetricItem item : items) {
            MetricHealthRule rule = rules.get(item.getId());
            boolean exempt = rule != null && rule.isExempt();
            if (!exempt) expected++;
            MetricScore score = scores.get(item.getId());
            if (score == null || score.score() == null) continue;
            participants.add(score);
            BigDecimal metricWeight = weights.metricWeight(item.getCategoryId(), item.getId());
            if (metricWeight.signum() <= 0) metricWeight = BigDecimal.ONE;
            numerator = numerator.add(metricWeight.multiply(BigDecimal.valueOf(score.score())));
            denominator = denominator.add(metricWeight);
            statusCounts.merge(score.status() == null ? "normal" : score.status(), 1, Integer::sum);
            modeCounts.merge(score.mode() == null ? "unknown" : score.mode(), 1, Integer::sum);
        }

        Integer score = denominator.signum() > 0
                ? numerator.divide(denominator, 0, RoundingMode.HALF_UP).intValue() : null;
        WorstMetric worst = participants.stream()
                .min(Comparator.comparing(MetricScore::score))
                .map(row -> new WorstMetric(row.metricId(), row.metricCode(), row.metricName(), row.score(), row.status()))
                .orElse(null);
        String status = statusRule(statusCounts, worst, settings);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("level", kind);
        trace.put("name", name);
        trace.put("formula", "小类健康度 = Σ(指标分数 × 指标权重) ÷ Σ(权重)，只对参与计算的指标归一");
        trace.put("weight", weight);
        trace.put("coverage", Map.of("participants", participants.size(), "expected", expected));
        trace.put("modeCounts", modeCounts);
        if (worst != null) trace.put("worst", worstMap(worst));

        return new Node(kind, categoryName + "|" + name, name, categoryName, score, status,
                participants.size(), expected, weight, BigDecimal.ZERO,
                statusCounts, modeCounts, worst, List.of(), traceJson(trace));
    }

    /** 对参与计算的小类做加权平均；只统计有分且权重为正的子节点 */
    private Integer weightedAverage(List<Node> children) {
        BigDecimal numerator = BigDecimal.ZERO;
        BigDecimal denominator = BigDecimal.ZERO;
        for (Node child : children) {
            if (child.score() == null) continue;
            BigDecimal weight = child.weight();
            if (weight == null || weight.signum() <= 0) continue;
            numerator = numerator.add(weight.multiply(BigDecimal.valueOf(child.score())));
            denominator = denominator.add(weight);
        }
        return denominator.signum() > 0
                ? numerator.divide(denominator, 0, RoundingMode.HALF_UP).intValue() : null;
    }

    // ==================================================================
    // 状态与工具
    // ==================================================================

    /**
     * 聚合状态规则（业务确认口径）：alarm 指标占比 ≥ 20% 或存在 &lt; 40 分的指标 → alarm；
     * warning 占比 ≥ 30% → warning；否则 normal。替代原来的“有 1 个 alarm 就整体 alarm”。
     *
     * <p>注意 {@code statusCounts} 是“指标级”状态计数（逐层累加不丢），{@code worst} 是穿透到
     * 指标级的最差指标，二者配合才能避免“一个大类平均分很高、但里面有一个 0 分指标”被掩盖。
     */
    private String statusRule(Map<String, Integer> statusCounts, WorstMetric worst,
                              HealthScoreEngine.Settings settings) {
        int total = statusCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return "unknown";
        int alarm = statusCounts.getOrDefault("alarm", 0);
        int warning = statusCounts.getOrDefault("warning", 0);
        boolean critical = worst != null && worst.score() != null && worst.score() < 40;
        if (critical || alarm / (double) total >= settings.alarmRatio()) return "alarm";
        if (warning / (double) total >= settings.warningRatio()) return "warning";
        return "normal";
    }

    private WorstMetric worstOf(List<Node> children) {
        return children.stream()
                .map(Node::worst)
                .filter(Objects::nonNull)
                .min(Comparator.comparing(WorstMetric::score))
                .orElse(null);
    }

    private Map<String, Object> worstMap(WorstMetric worst) {
        if (worst == null) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("metricId", worst.metricId());
        map.put("metricName", worst.metricName());
        map.put("metricCode", worst.metricCode());
        map.put("score", worst.score());
        map.put("status", worst.status());
        return map;
    }

    private double rate(int participants, int expected) {
        return expected <= 0 ? 0 : Math.round(participants * 10000.0 / expected) / 100.0;
    }

    private List<MetricItem> activeItems() {
        return itemRepository.findAll().stream()
                .filter(item -> item.getStatus() == null || item.getStatus() == 1)
                .toList();
    }

    private Map<Long, String> categoryNames() {
        Map<Long, String> names = new HashMap<>();
        for (MetricCategory category : categoryRepository.findAll()) {
            names.put(category.getId(), category.getCategoryName());
        }
        return names;
    }

    private Map<Long, MetricHealthRule> loadRules() {
        Map<Long, MetricHealthRule> rules = new HashMap<>();
        for (MetricHealthRule rule : ruleRepository.findAll()) {
            rules.put(rule.getMetricId(), rule);
        }
        return rules;
    }

    private Set<Long> systemMetricIds(long systemId) {
        Set<Long> categoryIds = new HashSet<>();
        for (MetricCategory category : categoryRepository.findBySystemId(systemId)) {
            categoryIds.add(category.getId());
        }
        Set<Long> ids = new HashSet<>();
        for (MetricItem item : itemRepository.findAll()) {
            if (item.getCategoryId() != null && categoryIds.contains(item.getCategoryId())
                    && (item.getStatus() == null || item.getStatus() == 1)) {
                ids.add(item.getId());
            }
        }
        return ids;
    }

    private String systemName(long systemId) {
        List<String> names = jdbc.queryForList("SELECT system_name FROM system_info WHERE id=?", String.class, systemId);
        return names.isEmpty() ? ("系统" + systemId) : names.get(0);
    }

    private static String subNameOf(MetricItem item) {
        return item.getSubCategoryName() == null || item.getSubCategoryName().isBlank()
                ? "未分类" : item.getSubCategoryName();
    }

    private String traceJson(Map<String, Object> trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // ==================================================================
    // 落库
    // ==================================================================

    private void saveCategory(Node category, LocalDateTime now) {
        String categoryName = category.name();
        Optional<CategoryHealthHistory> existing =
                categoryHealthRepository.findByCategoryNameAndRecordTime(categoryName, now);
        CategoryHealthHistory row = existing.orElseGet(CategoryHealthHistory::new);
        row.setCategoryName(categoryName);
        row.setRecordTime(now);
        row.setHealthScore(category.score());
        row.setStatus(category.status());
        row.setNormalCount(category.statusCounts().getOrDefault("normal", 0));
        row.setWarningCount(category.statusCounts().getOrDefault("warning", 0));
        row.setAlarmCount(category.statusCounts().getOrDefault("alarm", 0));
        row.setWeightTrace(category.traceJson());
        categoryHealthRepository.save(row);
    }

    private void saveSubCategory(Node sub, LocalDateTime now) {
        Optional<SubCategoryHealthHistory> existing = subCategoryHealthRepository
                .findByCategoryNameAndSubCategoryNameAndRecordTime(sub.categoryName(), sub.name(), now);
        SubCategoryHealthHistory row = existing.orElseGet(SubCategoryHealthHistory::new);
        row.setCategoryName(sub.categoryName());
        row.setSubCategoryName(sub.name());
        row.setRecordTime(now);
        row.setHealthScore(sub.score());
        row.setStatus(sub.status());
        row.setNormalCount(sub.statusCounts().getOrDefault("normal", 0));
        row.setWarningCount(sub.statusCounts().getOrDefault("warning", 0));
        row.setAlarmCount(sub.statusCounts().getOrDefault("alarm", 0));
        row.setWeightTrace(sub.traceJson());
        subCategoryHealthRepository.save(row);
    }

    // ==================================================================
    // 读取：最新新算法分数
    // ==================================================================

    /** metricId → 最新一次新算法打分（只取 mode 非空） */
    public Map<Long, MetricScore> latestScores() {
        Map<Long, String> categoryNames = categoryNames();
        Map<Long, MetricItem> items = new HashMap<>();
        for (MetricItem item : activeItems()) items.put(item.getId(), item);

        Map<Long, MetricScore> scores = new HashMap<>();
        String sql = """
                SELECT h.metric_id, h.health_score, h.status, h.mode, h.data_origin
                FROM metric_health_history h
                JOIN (SELECT metric_id, MAX(record_time) AS rt
                      FROM metric_health_history
                      WHERE mode IS NOT NULL
                      GROUP BY metric_id) x
                  ON x.metric_id = h.metric_id AND x.rt = h.record_time
                WHERE h.mode IS NOT NULL
                """;
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            Long metricId = ((Number) row.get("metric_id")).longValue();
            MetricItem item = items.get(metricId);
            if (item == null) continue;
            Object scoreValue = row.get("health_score");
            Integer score = scoreValue == null ? null : ((Number) scoreValue).intValue();
            scores.put(metricId, new MetricScore(metricId, item.getMetricCode(), item.getMetricName(),
                    categoryNames.getOrDefault(item.getCategoryId(), "未分类大类"),
                    subNameOf(item), score, (String) row.get("status"), (String) row.get("mode"),
                    (String) row.get("data_origin")));
        }
        return scores;
    }
}
