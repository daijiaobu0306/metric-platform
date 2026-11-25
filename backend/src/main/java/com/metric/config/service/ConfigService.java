package com.metric.config.service;

import com.metric.shared.dto.SyncResult;
import com.metric.collect.service.CollectionDirectoryPreparationService;
import com.metric.alarm.entity.AlarmStrategy;
import com.metric.config.entity.SystemConfig;
import com.metric.alarm.repository.AlarmStrategyRepository;
import com.metric.config.repository.SystemConfigRepository;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.entity.MetricSubCategory;
import com.metric.metric.service.MetricService;
import com.metric.metric.service.MetricSubCategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    @Autowired
    private SystemConfigRepository configRepository;

    @Autowired
    private AlarmStrategyRepository strategyRepository;

    @Autowired
    private MetricService metricService;

    @Autowired
    private MetricSubCategoryService subCategoryService;

    /**
     * 用 @Lazy 打破循环：SystemConfigBasePathProvider → ConfigService → 这里。
     * saveConfig 时调用 invalidate() 触发下次重新读 DB。
     */
    @Autowired
    @Lazy
    private SystemConfigBasePathProvider basePathProvider;

    @Autowired
    @Lazy
    private CollectionDirectoryPreparationService directoryPreparation;


    // === 系统配置 ===

    public List<SystemConfig> findAllConfigs() {
        return configRepository.findAll();
    }

    public List<SystemConfig> findConfigsByType(String configType) {
        return configRepository.findByConfigType(configType);
    }

    public Optional<SystemConfig> findConfigById(Long id) {
        return configRepository.findById(id);
    }

    public Optional<SystemConfig> findConfigByKey(String configKey) {
        return configRepository.findByConfigKey(configKey);
    }

    /**
     * 读取 system_config 配置项（字符串）。
     * 若配置不存在或值为空，返回 defaultValue。
     */
    public String getConfigValue(String configKey, String defaultValue) {
        return configRepository.findByConfigKey(configKey)
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isEmpty())
                .orElse(defaultValue);
    }

    /**
     * 读取 system_config 配置项（Long 类型）。
     * 解析失败时返回 defaultValue 并打 warn 日志。
     */
    public Long getConfigLong(String configKey, Long defaultValue) {
        String value = getConfigValue(configKey, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("system_config[{}] 无法解析为 Long: '{}'，使用默认值 {}", configKey, value, defaultValue);
            return defaultValue;
        }
    }

    @Transactional
    public SystemConfig saveConfig(SystemConfig config) {
        if (config == null || config.getConfigKey() == null) {
            throw new IllegalArgumentException("configKey 不能为空");
        }
        java.util.Map<String, Object> preparation = null;
        // upload.base_path 特殊校验：合法目录 + 可写 + 无 '..'
        if (SystemConfigBasePathProvider.CONFIG_KEY.equals(config.getConfigKey())) {
            String err = SystemConfigBasePathProvider.validate(config.getConfigValue());
            if (err != null) {
                throw new IllegalArgumentException("文件根目录不合法：" + err);
            }
            String root = java.nio.file.Path.of(config.getConfigValue().trim()).toAbsolutePath().normalize().toString();
            preparation = directoryPreparation.prepare(root);
            config.setConfigValue(root);
        }
        SystemConfig saved = configRepository.save(config);
        saved.setDirectoryPreparation(preparation);
        // 保存后让 BasePathProvider 下次重新读 DB
        if (SystemConfigBasePathProvider.CONFIG_KEY.equals(saved.getConfigKey())) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { basePathProvider.invalidate(); }
                    });
            } else {
                basePathProvider.invalidate();
            }
        }
        return saved;
    }

    @Transactional
    public void deleteConfigById(Long id) {
        configRepository.deleteById(id);
    }

    // === 告警策略 ===

    public List<AlarmStrategy> findAllStrategies() {
        return strategyRepository.findAll();
    }

    public List<AlarmStrategy> findStrategiesByType(String strategyType) {
        return strategyRepository.findByStrategyType(strategyType);
    }

    public List<AlarmStrategy> findStrategiesByTarget(String targetType, Long targetId) {
        return strategyRepository.findByTargetTypeAndTargetId(targetType, targetId);
    }

    public Optional<AlarmStrategy> findStrategyById(Long id) {
        return strategyRepository.findById(id);
    }

    @Transactional
    public SyncResult saveStrategy(AlarmStrategy strategy) {
        // 策略即阈值唯一真值：不再反向写入 metric_monitor_config（2026-10-28 解耦）
        strategyRepository.save(strategy);
        return SyncResult.success("策略保存成功", 1);
    }

    @Transactional
    public void deleteStrategyById(Long id) {
        strategyRepository.deleteById(id);
    }

    // 分页筛选策略
    public Page<AlarmStrategy> findStrategiesByFilter(
            Long id,
            String name,
            String strategyType,
            String targetType,
            Integer status,
            Integer alarmEnabled,
            Integer gongdanEnabled,
            Long categoryId,
            Long subCategoryId,
            int pageNum,
            int pageSize) {
        Pageable pageable = PageRequest.of(pageNum - 1, pageSize, Sort.by(Sort.Direction.DESC, "updateTime"));
        if (categoryId == null && subCategoryId == null) {
            return strategyRepository.findByFilters(id, name, strategyType, targetType, status, alarmEnabled, gongdanEnabled, pageable);
        }
        // 大类/小类是策略的间接属性（指标→大类/小类，小类→大类），JPQL 里跨三张表拼条件可读性差；
        // 策略总量只有数百条，这里统一取回后在内存中按对象归属过滤，再分页返回。
        List<AlarmStrategy> matched = scopedStrategies(id, name, strategyType, targetType, status,
                alarmEnabled, gongdanEnabled, categoryId, subCategoryId);
        int from = Math.min((pageNum - 1) * pageSize, matched.size());
        int to = Math.min(from + pageSize, matched.size());
        return new PageImpl<>(matched.subList(from, to), pageable, matched.size());
    }

    /**
     * 按当前筛选条件批量启用/停用策略（不受分页限制）。
     * 与列表使用同一套筛选口径，保证「筛出多少条就改多少条」。
     */
    @Transactional
    public int batchUpdateStrategyStatus(
            Long id, String name, String strategyType, String targetType,
            Integer status, Integer alarmEnabled, Integer gongdanEnabled,
            Long categoryId, Long subCategoryId, Integer targetStatus) {
        if (!Objects.equals(targetStatus, 0) && !Objects.equals(targetStatus, 1))
            throw new IllegalArgumentException("目标状态必须为启用(1)或停用(0)");
        if (id == null && isBlank(name) && isBlank(strategyType) && isBlank(targetType)
                && categoryId == null && subCategoryId == null)
            throw new IllegalArgumentException("请先设置筛选条件（至少选择指标大类或指标小类）再批量操作");
        List<AlarmStrategy> matched = scopedStrategies(id, name, strategyType, targetType, status,
                alarmEnabled, gongdanEnabled, categoryId, subCategoryId);
        matched.forEach(strategy -> strategy.setStatus(targetStatus));
        strategyRepository.saveAll(matched);
        return matched.size();
    }

    /** 统一筛选口径：基础字段 + 指标大类/小类归属。结果按更新时间倒序（空值最后）。 */
    private List<AlarmStrategy> scopedStrategies(
            Long id, String name, String strategyType, String targetType,
            Integer status, Integer alarmEnabled, Integer gongdanEnabled,
            Long categoryId, Long subCategoryId) {
        Map<Long, MetricItem> metrics = new HashMap<>();
        Map<Long, MetricSubCategory> subCategories = new HashMap<>();
        if (categoryId != null || subCategoryId != null) {
            metricService.findAllItems().forEach(metric -> metrics.put(metric.getId(), metric));
            buildSubCategoryIndex(categoryId, subCategoryId, subCategories);
        }
        String keyword = isBlank(name) ? null : name.trim().toLowerCase();
        List<AlarmStrategy> matched = new ArrayList<>();
        for (AlarmStrategy strategy : strategyRepository.findAll()) {
            if (id != null && !id.equals(strategy.getId())) continue;
            if (keyword != null && (strategy.getStrategyName() == null
                    || !strategy.getStrategyName().toLowerCase().contains(keyword))) continue;
            if (!isBlank(strategyType) && !strategyType.equals(strategy.getStrategyType())) continue;
            if (!isBlank(targetType) && !targetType.equals(strategy.getTargetType())) continue;
            if (status != null && !status.equals(strategy.getStatus())) continue;
            if (alarmEnabled != null && !alarmEnabled.equals(strategy.getAlarmEnabled())) continue;
            if (gongdanEnabled != null && !gongdanEnabled.equals(strategy.getGongdanEnabled())) continue;
            if ((categoryId != null || subCategoryId != null)
                    && !belongsToScope(strategy, categoryId, subCategoryId, metrics, subCategories)) continue;
            matched.add(strategy);
        }
        matched.sort(Comparator.comparing(AlarmStrategy::getUpdateTime,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AlarmStrategy::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        return matched;
    }

    /**
     * 预加载小类字典：指定小类时只查该条；只按大类时查该大类下全部小类。
     * 指标表的 subCategory 只有编码没有 ID，所以指标策略要靠 (大类ID + 小类编码) 反查。
     */
    private void buildSubCategoryIndex(Long categoryId, Long subCategoryId, Map<Long, MetricSubCategory> target) {
        if (subCategoryId != null) {
            subCategoryService.getById(subCategoryId).ifPresent(sub -> {
                if (categoryId == null || categoryId.equals(sub.getCategoryId())) target.put(sub.getId(), sub);
            });
            return;
        }
        if (categoryId != null) subCategoryService.getByCategoryId(categoryId)
                .forEach(sub -> target.put(sub.getId(), sub));
    }

    /** 一条策略是否落在所选大类/小类下：大类/小类/指标三种对象都要能命中。 */
    private boolean belongsToScope(AlarmStrategy strategy, Long categoryId, Long subCategoryId,
                                   Map<Long, MetricItem> metrics, Map<Long, MetricSubCategory> subCategories) {
        String type = strategy.getTargetType();
        Long targetId = strategy.getTargetId();
        if (targetId == null) return false;
        if ("category".equals(type)) {
            // 大类级健康度策略只属于大类本身，选中小类时不算
            return categoryId != null && categoryId.equals(targetId);
        }
        if ("subCategory".equals(type)) {
            MetricSubCategory sub = subCategories.get(targetId);
            if (sub == null) return false;
            return (categoryId == null || categoryId.equals(sub.getCategoryId()))
                    && (subCategoryId == null || subCategoryId.equals(sub.getId()));
        }
        if ("metric".equals(type)) {
            MetricItem metric = metrics.get(targetId);
            if (metric == null) return false;
            if (categoryId != null && !categoryId.equals(metric.getCategoryId())) return false;
            if (subCategoryId != null) {
                MetricSubCategory sub = subCategories.get(subCategoryId);
                return sub != null && sub.getCategoryId().equals(metric.getCategoryId())
                        && Objects.equals(sub.getSubCategoryCode(), metric.getSubCategoryCode());
            }
            return true;
        }
        // 系统级策略 / 原始数据策略不属于任何指标大类
        return false;
    }

    private boolean isBlank(String value) { return value == null || value.isBlank(); }
}
