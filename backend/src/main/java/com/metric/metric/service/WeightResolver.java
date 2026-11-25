package com.metric.metric.service;

import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.entity.WeightConfig;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.MetricItemRepository;
import com.metric.metric.repository.WeightConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一权重解析器 —— 全项目唯一的权重口径。
 *
 * <p>改造前项目里同时存在三套权重默认值（算分缺省 1 / 展示导出缺省 0.01 / 系统分缺省 0.25）
 * 和两套键（小类编码 vs 中文名），导致「页面上改了权重，算分不生效」。现在统一为：
 * <ul>
 *   <li>键：小类统一用中文名（历史编码键会按指标表映射成中文名；映射不到的历史键不参与计算）；</li>
 *   <li>默认值：同层级未配置 = 该层级已配置权重的平均值；整层都没配置 = 等权。</li>
 * </ul>
 *
 * <p>注意这里返回的是<b>原始权重</b>，不做“合计为 1”的归一。归一必须放在聚合层、
 * 对“本次真正参与计算的指标集合”重新做，否则缺数指标会把权重漏掉，把大类分数算低。
 */
@Service
public class WeightResolver {

    @Autowired private WeightConfigRepository weightConfigRepository;
    @Autowired private MetricCategoryRepository categoryRepository;
    @Autowired private MetricItemRepository itemRepository;

    /**
     * 一次读取的全部权重快照（避免逐指标查库造成 N+1）。
     */
    public record WeightSnapshot(
            Map<Long, Map<Long, BigDecimal>> categoryWeightsBySystem,
            Map<Long, Map<String, BigDecimal>> subcategoryWeightsByCategory,
            Map<Long, Map<Long, BigDecimal>> metricWeightsByCategory) {

        public BigDecimal categoryWeight(Long systemId, Long categoryId) {
            if (categoryId == null) return BigDecimal.ONE;
            Map<Long, BigDecimal> map = categoryWeightsBySystem.get(systemId);
            return map == null ? BigDecimal.ONE : map.getOrDefault(categoryId, BigDecimal.ONE);
        }

        public BigDecimal subcategoryWeight(Long categoryId, String subCategoryName) {
            if (categoryId == null || subCategoryName == null) return BigDecimal.ONE;
            Map<String, BigDecimal> map = subcategoryWeightsByCategory.get(categoryId);
            return map == null ? BigDecimal.ONE : map.getOrDefault(subCategoryName, BigDecimal.ONE);
        }

        public BigDecimal metricWeight(Long categoryId, Long metricId) {
            if (categoryId == null || metricId == null) return BigDecimal.ONE;
            Map<Long, BigDecimal> map = metricWeightsByCategory.get(categoryId);
            return map == null ? BigDecimal.ONE : map.getOrDefault(metricId, BigDecimal.ONE);
        }
    }

    /** 装载一次权重快照。调用方在一次聚合/一次页面加载内复用同一份，避免重复查询。 */
    public WeightSnapshot snapshot() {
        List<WeightConfig> configs = weightConfigRepository.findAll();
        List<MetricCategory> categories = categoryRepository.findAll();
        List<MetricItem> items = itemRepository.findAll();

        Map<Long, List<MetricItem>> itemsByCategory = new HashMap<>();
        Map<Long, Map<String, String>> codeToNameByCategory = new HashMap<>();
        for (MetricItem item : items) {
            if (item.getCategoryId() == null) continue;
            itemsByCategory.computeIfAbsent(item.getCategoryId(), key -> new ArrayList<>()).add(item);
            if (item.getSubCategoryCode() != null && item.getSubCategoryName() != null) {
                codeToNameByCategory.computeIfAbsent(item.getCategoryId(), key -> new HashMap<>())
                        .put(item.getSubCategoryCode(), item.getSubCategoryName());
            }
        }

        // ---------- 大类权重：按系统分组 ----------
        Map<Long, Map<Long, BigDecimal>> configuredCategory = new HashMap<>();
        for (WeightConfig config : configs) {
            if (!"category".equals(config.getWeightType())) continue;
            if (config.getSystemId() == null || config.getCategoryId() == null || config.getWeight() == null) continue;
            configuredCategory.computeIfAbsent(config.getSystemId(), key -> new LinkedHashMap<>())
                    .putIfAbsent(config.getCategoryId(), config.getWeight());
        }
        Map<Long, Map<Long, BigDecimal>> categoryWeightsBySystem = new LinkedHashMap<>();
        Map<Long, List<MetricCategory>> categoriesBySystem = new HashMap<>();
        for (MetricCategory category : categories) {
            if (category.getSystemId() == null) continue;
            categoriesBySystem.computeIfAbsent(category.getSystemId(), key -> new ArrayList<>()).add(category);
        }
        for (var entry : categoriesBySystem.entrySet()) {
            Map<Long, BigDecimal> configured = configuredCategory.getOrDefault(entry.getKey(), Map.of());
            categoryWeightsBySystem.put(entry.getKey(),
                    fillMissing(entry.getValue().stream().map(MetricCategory::getId).toList(), configured));
        }

        // ---------- 小类权重：按大类分组，编码键归一成中文名 ----------
        Map<Long, Map<String, BigDecimal>> configuredSub = new HashMap<>();
        for (WeightConfig config : configs) {
            if (!"subcategory".equals(config.getWeightType())) continue;
            if (config.getCategoryId() == null || config.getSubCategoryName() == null || config.getWeight() == null) continue;
            String name = config.getSubCategoryName();
            Map<String, String> codeToName = codeToNameByCategory.getOrDefault(config.getCategoryId(), Map.of());
            if (codeToName.containsKey(name)) name = codeToName.get(name);
            configuredSub.computeIfAbsent(config.getCategoryId(), key -> new LinkedHashMap<>())
                    .putIfAbsent(name, config.getWeight());
        }
        Map<Long, Map<String, BigDecimal>> subcategoryWeightsByCategory = new LinkedHashMap<>();
        for (var entry : itemsByCategory.entrySet()) {
            List<String> names = entry.getValue().stream()
                    .filter(item -> item.getStatus() == null || item.getStatus() == 1)
                    .map(MetricItem::getSubCategoryName)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();
            if (names.isEmpty()) continue;
            subcategoryWeightsByCategory.put(entry.getKey(),
                    fillMissing(names, configuredSub.getOrDefault(entry.getKey(), Map.of())));
        }

        // ---------- 指标权重：按大类分组 ----------
        Map<Long, Map<Long, BigDecimal>> configuredMetric = new HashMap<>();
        for (WeightConfig config : configs) {
            if (!"metric".equals(config.getWeightType())) continue;
            if (config.getCategoryId() == null || config.getMetricId() == null || config.getWeight() == null) continue;
            configuredMetric.computeIfAbsent(config.getCategoryId(), key -> new LinkedHashMap<>())
                    .putIfAbsent(config.getMetricId(), config.getWeight());
        }
        Map<Long, Map<Long, BigDecimal>> metricWeightsByCategory = new LinkedHashMap<>();
        for (var entry : itemsByCategory.entrySet()) {
            List<Long> ids = entry.getValue().stream()
                    .filter(item -> item.getStatus() == null || item.getStatus() == 1)
                    .map(MetricItem::getId)
                    .toList();
            if (ids.isEmpty()) continue;
            metricWeightsByCategory.put(entry.getKey(),
                    fillMissing(ids, configuredMetric.getOrDefault(entry.getKey(), Map.of())));
        }

        return new WeightSnapshot(categoryWeightsBySystem, subcategoryWeightsByCategory, metricWeightsByCategory);
    }

    /**
     * 补齐缺失权重：已配置的保持原值；未配置的取“已配置权重的平均值”；
     * 整层都没配置时取 1（等权）。
     */
    private static <K> Map<K, BigDecimal> fillMissing(List<K> keys, Map<K, BigDecimal> configured) {
        Map<K, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal fallback = BigDecimal.ONE;
        if (!configured.isEmpty()) {
            BigDecimal sum = configured.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            fallback = sum.divide(BigDecimal.valueOf(configured.size()), 6, RoundingMode.HALF_UP);
            if (fallback.signum() <= 0) fallback = BigDecimal.ONE;
        }
        for (K key : keys) {
            BigDecimal weight = configured.get(key);
            result.put(key, weight != null && weight.signum() > 0 ? weight : fallback);
        }
        return result;
    }
}
