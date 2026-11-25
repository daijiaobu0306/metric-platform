package com.metric.metric.service;

import com.metric.audit.entity.MetricAuditLog;
import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricItem;
import com.metric.audit.repository.MetricAuditLogRepository;
import com.metric.security.service.CurrentUserProvider;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.MetricItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class MetricService {

    @Autowired
    private MetricCategoryRepository categoryRepository;

    @Autowired
    private MetricItemRepository itemRepository;

    @Autowired
    private MetricAuditLogRepository auditLogRepository;

    /**
     * 当前登录用户：用户触发的配置变更把真实操作人写进审计日志。
     * 无登录态（定时任务 / 无 Web 上下文的导入）operatorName() 会回落 "system"，保持原兜底语义。
     */
    @Autowired
    private CurrentUserProvider currentUserProvider;

    @Autowired
    private com.metric.alarm.service.AlarmStrategyQueryService strategyQueryService;

    /** 岗位字典：保存指标时把 audience_code 与「面向人员」展示文本对齐。 */
    @Autowired
    private MetricAudienceService audienceService;

    /**
     * 给指标列表装配「告警概览」展示字段（@Transient，不落库）。
     *
     * <p>一次装载策略快照再按 metricId 取，避免逐条查库造成 N+1。
     * 口径与 {@link com.metric.alarm.service.AlarmStrategyQueryService} 保持一致：
     * 只看启用策略、该字段有值、取 id 最小的一条；没有则为 null，前端显示「未配置」。
     */
    private List<MetricItem> withStrategyOverview(List<MetricItem> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        var snapshot = strategyQueryService.loadSnapshot();
        for (MetricItem item : items) {
            item.setAlarmRules(snapshot.alarmRules(item.getId()));
            item.setGongdanRules(snapshot.gongdanRules(item.getId()));
            item.setAlarmThreshold(snapshot.alarmThreshold(item.getId()));
            item.setAlarmCondition(snapshot.alarmCondition(item.getId()));
            item.setGongdanThreshold(snapshot.gongdanThreshold(item.getId()));
            item.setGongdanCondition(snapshot.gongdanCondition(item.getId()));
        }
        return items;
    }

    // === 指标大类操作 ===

    public Page<MetricCategory> findAllCategories(Pageable pageable) {
        return categoryRepository.findAll(pageable);
    }

    public List<MetricCategory> findAllCategories() {
        return categoryRepository.findAll();
    }

    public List<MetricCategory> findCategoriesBySystemId(Long systemId) {
        return categoryRepository.findBySystemId(systemId);
    }

    public List<MetricCategory> findCategoriesBySystemIdAndStatus(Long systemId, Integer status) {
        return categoryRepository.findBySystemIdAndStatus(systemId, status);
    }

    public Optional<MetricCategory> findCategoryById(Long id) {
        return categoryRepository.findById(id);
    }

    @Transactional
    public MetricCategory saveCategory(MetricCategory category) {
        boolean isUpdate = category.getId() != null;
        StringBuilder changeDesc = new StringBuilder();

        if (isUpdate) {
            Optional<MetricCategory> existingOpt = findCategoryById(category.getId());
            if (existingOpt.isPresent()) {
                MetricCategory existing = existingOpt.get();
                java.util.Map<String, String> changes = new java.util.LinkedHashMap<>();

                if (category.getCategoryCode() != null && !category.getCategoryCode().equals(existing.getCategoryCode())) {
                    changes.put("大类编码", existing.getCategoryCode() + " → " + category.getCategoryCode());
                }
                if (category.getCategoryName() != null && !category.getCategoryName().equals(existing.getCategoryName())) {
                    changes.put("大类名称", existing.getCategoryName() + " → " + category.getCategoryName());
                }
                if (category.getCategoryType() != null && !category.getCategoryType().equals(existing.getCategoryType())) {
                    changes.put("大类类型", existing.getCategoryType() + " → " + category.getCategoryType());
                }
                if (category.getDescription() != null && !category.getDescription().equals(existing.getDescription())) {
                    changes.put("描述", existing.getDescription() + " → " + category.getDescription());
                }
                if (category.getStatus() != null && !category.getStatus().equals(existing.getStatus())) {
                    changes.put("状态", (existing.getStatus() == 1 ? "启用" : "禁用") + " → " + (category.getStatus() == 1 ? "启用" : "禁用"));
                }

                for (java.util.Map.Entry<String, String> entry : changes.entrySet()) {
                    if (changeDesc.length() > 0) changeDesc.append("；");
                    changeDesc.append(entry.getKey()).append(": ").append(entry.getValue());
                }

                if (changes.isEmpty()) {
                    changeDesc.append("未检测到字段变更");
                }
            }
        }

        MetricCategory saved = categoryRepository.save(category);

        // 记录审计日志
        MetricAuditLog log = new MetricAuditLog();
        log.setOperationType(isUpdate ? "修改" : "新增");
        log.setEntityType("metric_category");
        log.setEntityId(saved.getId());
        log.setChangeContent((isUpdate ? "修改" : "新增") + "指标大类【" + saved.getCategoryName() + "】: " + changeDesc.toString());
        log.setOperator(currentUserProvider.operatorName());
        auditLogRepository.save(log);

        return saved;
    }

    @Transactional
    public void deleteCategoryById(Long id) {
        Optional<MetricCategory> category = categoryRepository.findById(id);
        categoryRepository.deleteById(id);

        // 记录审计日志
        if (category.isPresent()) {
            MetricAuditLog log = new MetricAuditLog();
            log.setOperationType("删除");
            log.setEntityType("metric_category");
            log.setEntityId(id);
            log.setChangeContent("删除指标大类: " + category.get().getCategoryName());
            log.setOperator(currentUserProvider.operatorName());
            auditLogRepository.save(log);
        }
    }

    // === 具体指标操作 ===

    public Page<MetricItem> findAllItems(Pageable pageable) {
        return itemRepository.findAll(pageable);
    }

    public Page<MetricItem> findItemsWithFilters(Long systemId, Long categoryId, String subCategoryName, String metricType, Integer status, String metricName, String metricCode, String metricDefinition, String alarmConfigured, String gongdanConfigured, Pageable pageable) {
        Page<MetricItem> page = itemRepository.findWithFilters(systemId, categoryId, subCategoryName, metricType, status, metricName, metricCode, metricDefinition, alarmConfigured, gongdanConfigured, pageable);
        withStrategyOverview(page.getContent());
        return page;
    }

    public List<MetricItem> findAllItems() {
        return withStrategyOverview(itemRepository.findAll());
    }

    public List<MetricItem> findItemsByCategoryId(Long categoryId) {
        return itemRepository.findByCategoryId(categoryId);
    }

    public List<MetricItem> findItemsByCategoryIdAndStatus(Long categoryId, Integer status) {
        return itemRepository.findByCategoryIdAndStatus(categoryId, status);
    }

    public Optional<MetricItem> findItemById(Long id) {
        return itemRepository.findById(id);
    }

    @Transactional
    public MetricItem saveItem(MetricItem item) {
        boolean isUpdate = item.getId() != null;
        StringBuilder changeDesc = new StringBuilder();

        // 如果是更新操作，保留原有数据中不能为null的字段，并记录变更
        if (isUpdate) {
            Optional<MetricItem> existingOpt = findItemById(item.getId());
            if (existingOpt.isPresent()) {
                MetricItem existing = existingOpt.get();
                java.util.Map<String, String> changes = new java.util.LinkedHashMap<>();

                // 检查各字段变更
                if (item.getMetricName() != null && !item.getMetricName().equals(existing.getMetricName())) {
                    changes.put("指标名称", existing.getMetricName() + " → " + item.getMetricName());
                }
                if (item.getMetricCode() != null && !item.getMetricCode().equals(existing.getMetricCode())) {
                    changes.put("指标编码", existing.getMetricCode() + " → " + item.getMetricCode());
                }
                if (item.getSubCategoryName() != null && !item.getSubCategoryName().equals(existing.getSubCategoryName())) {
                    changes.put("指标小类", existing.getSubCategoryName() + " → " + item.getSubCategoryName());
                }
                if (item.getMetricType() != null && !item.getMetricType().equals(existing.getMetricType())) {
                    changes.put("指标类型", existing.getMetricType() + " → " + item.getMetricType());
                }
                if (item.getMetricDefinition() != null && !item.getMetricDefinition().equals(existing.getMetricDefinition())) {
                    changes.put("指标定义", existing.getMetricDefinition() + " → " + item.getMetricDefinition());
                }
                if (item.getUnit() != null && !item.getUnit().equals(existing.getUnit())) {
                    changes.put("单位", existing.getUnit() + " → " + item.getUnit());
                }
                if (item.getCollectFrequency() != null && !item.getCollectFrequency().equals(existing.getCollectFrequency())) {
                    changes.put("采集频率", existing.getCollectFrequency() + " → " + item.getCollectFrequency());
                }
                if (item.getStatus() != null && !item.getStatus().equals(existing.getStatus())) {
                    changes.put("状态", (existing.getStatus() == 1 ? "启用" : "禁用") + " → " + (item.getStatus() == 1 ? "启用" : "禁用"));
                }

                // 保留所有不能为null的字段
                if (item.getCategoryId() == null) item.setCategoryId(existing.getCategoryId());
                if (item.getMetricCode() == null || item.getMetricCode().isEmpty()) item.setMetricCode(existing.getMetricCode());
                if (item.getMetricName() == null || item.getMetricName().isEmpty()) item.setMetricName(existing.getMetricName());
                if (item.getCreateTime() == null) item.setCreateTime(existing.getCreateTime());
                if (item.getSubCategoryName() == null) item.setSubCategoryName(existing.getSubCategoryName());
                if (item.getSubCategoryCode() == null) item.setSubCategoryCode(existing.getSubCategoryCode());
                if (item.getMetricType() == null) item.setMetricType(existing.getMetricType());
                if (item.getMetricDefinition() == null) item.setMetricDefinition(existing.getMetricDefinition());
                if (item.getUnit() == null) item.setUnit(existing.getUnit());
                if (item.getDataSource() == null) item.setDataSource(existing.getDataSource());
                if (item.getCollectFrequency() == null) item.setCollectFrequency(existing.getCollectFrequency());
                if (item.getBaselineValue() == null) item.setBaselineValue(existing.getBaselineValue());
                if (item.getNormalMin() == null) item.setNormalMin(existing.getNormalMin());
                if (item.getNormalMax() == null) item.setNormalMax(existing.getNormalMax());
                if (item.getTrendDirection() == null) item.setTrendDirection(existing.getTrendDirection());
                if (item.getStatus() == null) item.setStatus(existing.getStatus());
                // 单位与阈值统一改造新增的字段，同样遵循「null 保留原值」
                if (item.getResponsiblePerson() == null) item.setResponsiblePerson(existing.getResponsiblePerson());
                if (item.getAudienceCode() == null) item.setAudienceCode(existing.getAudienceCode());
                if (item.getStatisticsCaliber() == null) item.setStatisticsCaliber(existing.getStatisticsCaliber());
                if (item.getDataSyncMode() == null) item.setDataSyncMode(existing.getDataSyncMode());
                if (item.getCollectStatus() == null) item.setCollectStatus(existing.getCollectStatus());
                if (item.getDepartment() == null) item.setDepartment(existing.getDepartment());
                if (item.getReporter() == null) item.setReporter(existing.getReporter());
                // 岗位（面向人员）变更写进审计，便于追溯指标覆盖对象的变化
                if (item.getAudienceCode() != null && !item.getAudienceCode().equals(existing.getAudienceCode())) {
                    changes.put("面向人员", blankName(audienceService.nameOf(existing.getAudienceCode()))
                            + " → " + blankName(audienceService.nameOf(item.getAudienceCode())));
                }
                // 构建变更描述
                for (java.util.Map.Entry<String, String> entry : changes.entrySet()) {
                    if (changeDesc.length() > 0) changeDesc.append("；");
                    changeDesc.append(entry.getKey()).append(": ").append(entry.getValue());
                }

                if (changes.isEmpty()) {
                    changeDesc.append("未检测到字段变更");
                }
            }
        }

        // 岗位编码是唯一事实来源：「面向人员」文本由岗位名称回填，避免两列互相矛盾。
        // 岗位名称只从字典取，不信任前端传入的文本。
        if (item.getAudienceCode() != null) {
            String audienceName = audienceService.nameOf(item.getAudienceCode());
            if (audienceName != null) item.setResponsiblePerson(audienceName);
        }

        MetricItem saved = itemRepository.save(item);

        // 注：指标不再承载告警阈值，因此保存指标时无需同步告警策略。
        // 告警阈值唯一来源为 alarm_strategy，由告警配置模块自行维护。

        // 记录审计日志
        MetricAuditLog log = new MetricAuditLog();
        log.setOperationType(isUpdate ? "修改" : "新增");
        log.setEntityType("metric_item");
        log.setEntityId(saved.getId());
        log.setChangeContent((isUpdate ? "修改" : "新增") + "指标【" + saved.getMetricName() + "】: " + changeDesc.toString());
        log.setOperator(currentUserProvider.operatorName());
        auditLogRepository.save(log);

        return saved;
    }

    /**
     * 批量启用 / 停用指标（2026-10-28 新增）。
     *
     * 指标状态是"总开关"：停用后该指标退出算分、告警/工单、实时指标刷新与运营报表，
     * 历史数据保留（metric_realtime / *_health_history 不删除）。
     * 逐个走 saveItem，复用其字段保留与审计日志逻辑。
     *
     * @param ids    指标 ID 列表
     * @param status 0=停用，1=启用
     * @return 实际发生状态变化的条数（已是目标状态的会被跳过）
     */
    @Transactional
    public int batchUpdateItemStatus(List<Long> ids, Integer status) {
        if (ids == null || ids.isEmpty() || status == null) {
            return 0;
        }
        int changed = 0;
        for (Long id : ids) {
            if (id == null) continue;
            Optional<MetricItem> existingOpt = itemRepository.findById(id);
            if (existingOpt.isEmpty()) continue;
            Integer current = existingOpt.get().getStatus();
            // status 为 null 按"启用"处理，与 countEnabledItems 的口径一致
            int currentNormalized = (current == null) ? 1 : current;
            if (currentNormalized == status) continue;

            MetricItem patch = new MetricItem();
            patch.setId(id);
            patch.setStatus(status);
            try {
                saveItem(patch);
                changed++;
            } catch (Exception e) {
                System.err.println("批量更新指标状态失败 id=" + id + ": " + e.getMessage());
            }
        }
        return changed;
    }

    @Transactional
    public void deleteItemById(Long id) {
        Optional<MetricItem> item = itemRepository.findById(id);
        itemRepository.deleteById(id);

        // 记录审计日志
        if (item.isPresent()) {
            MetricAuditLog log = new MetricAuditLog();
            log.setOperationType("删除");
            log.setEntityType("metric_item");
            log.setEntityId(id);
            log.setChangeContent("删除指标: " + item.get().getMetricName());
            log.setOperator(currentUserProvider.operatorName());
            auditLogRepository.save(log);
        }
    }

    public void deleteAllItems() {
        itemRepository.deleteAll();
    }

    public void deleteAllCategories() {
        categoryRepository.deleteAll();
    }

    // === 统计 ===

    public long countCategories() {
        return categoryRepository.count();
    }

    public long countItems() {
        return itemRepository.count();
    }

    public long countEnabledItems() {
        return itemRepository.countEnabledItems();
    }

    /** 审计文案用：岗位为空时显示「未设置」，不要把 null 拼进变更描述。 */
    private static String blankName(String value) {
        return value == null || value.isBlank() ? "未设置" : value;
    }
}
