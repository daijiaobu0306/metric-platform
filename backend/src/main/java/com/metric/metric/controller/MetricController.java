package com.metric.metric.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.repository.MetricSubCategoryRepository;
import com.metric.metric.service.MetricImportService;
import com.metric.metric.service.MetricImportPlanService;
import com.metric.metric.service.MetricService;
import com.metric.metric.service.MetricValueCalcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metric")
public class MetricController {

    @Autowired
    private MetricService metricService;

    @Autowired
    private MetricImportService metricImportService;

    @Autowired
    private MetricImportPlanService metricImportPlanService;

    @Autowired
    private MetricValueCalcService metricValueCalcService;

    @Autowired
    private MetricSubCategoryRepository subCategoryRepository;

    @Autowired
    private com.metric.metric.service.MetricAudienceService audienceService;

    // === 指标大类 API ===

    @GetMapping("/category")
    public ApiResponse<Page<MetricCategory>> findAllCategories(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return ApiResponse.success(metricService.findAllCategories(pageable));
    }

    @GetMapping("/category/list")
    public ApiResponse<List<MetricCategory>> findAllCategoriesList() {
        return ApiResponse.success(metricService.findAllCategories());
    }

    @GetMapping("/category/system/{systemId}")
    public ApiResponse<List<MetricCategory>> findCategoriesBySystemId(@PathVariable Long systemId) {
        return ApiResponse.success(metricService.findCategoriesBySystemId(systemId));
    }

    @GetMapping("/category/{id}")
    public ApiResponse<MetricCategory> findCategoryById(@PathVariable Long id) {
        return metricService.findCategoryById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Category not found"));
    }

    @PostMapping("/category")
    public ApiResponse<MetricCategory> createCategory(@RequestBody MetricCategory category) {
        return ApiResponse.success(metricService.saveCategory(category));
    }

    @PutMapping("/category/{id}")
    public ApiResponse<MetricCategory> updateCategory(@PathVariable Long id, @RequestBody MetricCategory category) {
        return metricService.findCategoryById(id)
                .map(existing -> {
                    category.setId(id);
                    return ApiResponse.success(metricService.saveCategory(category));
                })
                .orElse(ApiResponse.error("Category not found"));
    }

    @DeleteMapping("/category/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        metricService.deleteCategoryById(id);
        return ApiResponse.success();
    }

    // === 具体指标 API ===

    @GetMapping("/item")
    public ApiResponse<Page<MetricItem>> findAllItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String subCategoryName,
            @RequestParam(required = false) String metricType,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String metricName,
            @RequestParam(required = false) String metricCode,
            @RequestParam(required = false) String metricDefinition,
            // 告警/工单配置筛选：不传=不筛，yes=只看已配置，no=只看未配置
            // （由「指标总数 / 告警 / 工单」概览卡片点击触发）
            @RequestParam(required = false) String alarmConfigured,
            @RequestParam(required = false) String gongdanConfigured) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return ApiResponse.success(metricService.findItemsWithFilters(systemId, categoryId, subCategoryName, metricType, status, metricName, metricCode, metricDefinition, alarmConfigured, gongdanConfigured, pageable));
    }

    @GetMapping("/item/list")
    public ApiResponse<List<MetricItem>> findAllItemsList() {
        return ApiResponse.success(metricService.findAllItems());
    }

    @GetMapping("/item/category/{categoryId}")
    public ApiResponse<List<MetricItem>> findItemsByCategoryId(@PathVariable Long categoryId) {
        return ApiResponse.success(metricService.findItemsByCategoryId(categoryId));
    }

    /**
     * 岗位（面向人员）字典 + 指标数量。
     *
     * <p>2026-11-17 新增：指标体系配置页的「面向人员」下拉、运营观测/专屏的人员维度
     * 都从这里取选项，保证同一份字典。岗位只用于业务标签与聚合，不参与菜单鉴权。
     */
    @GetMapping("/audience/list")
    public ApiResponse<List<Map<String, Object>>> findAudiences() {
        return ApiResponse.success(audienceService.catalog());
    }

    /**
     * 岗位字典维护视图：全部岗位（含停用）+ 引用指标数 + 指标里出现但未登记的编码。
     */
    @GetMapping("/audience/manage")
    public ApiResponse<Map<String, Object>> manageAudiences() {
        return ApiResponse.success(audienceService.adminCatalog());
    }

    @PostMapping("/audience/item")
    public ApiResponse<?> createAudience(@RequestBody com.metric.metric.service.MetricAudienceService.AudienceForm form) {
        try {
            return ApiResponse.success(audienceService.createAudience(form));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PutMapping("/audience/item/{id}")
    public ApiResponse<?> updateAudience(@PathVariable Long id,
                                         @RequestBody com.metric.metric.service.MetricAudienceService.AudienceForm form) {
        try {
            return ApiResponse.success(audienceService.updateAudience(id, form));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/audience/item/{id}")
    public ApiResponse<?> deleteAudience(@PathVariable Long id) {
        try {
            audienceService.deleteAudience(id);
            return ApiResponse.success(null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/item/{id}")
    public ApiResponse<MetricItem> findItemById(@PathVariable Long id) {
        return metricService.findItemById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Item not found"));
    }

    @PostMapping("/item")
    public ApiResponse<MetricItem> createItem(@RequestBody MetricItem item) {
        return ApiResponse.success(metricService.saveItem(item));
    }

    @PutMapping("/item/{id}")
    public ApiResponse<MetricItem> updateItem(@PathVariable Long id, @RequestBody MetricItem item) {
        return metricService.findItemById(id)
                .map(existing -> {
                    // 指标编码不可修改（2026-11-18）：采集数据、指标值、告警与工单都靠编码关联，
                    // 改编码等于把历史链路全部断开。页面已改为只读，这里再挡一层，
                    // 防止接口调用或脚本误改。
                    if (item.getMetricCode() != null && !item.getMetricCode().isBlank()
                            && !item.getMetricCode().equals(existing.getMetricCode())) {
                        return ApiResponse.<MetricItem>error(
                                "指标编码不可修改：编码用于关联采集数据、指标值与告警记录，改动会导致历史数据断链");
                    }
                    item.setId(id);
                    return ApiResponse.success(metricService.saveItem(item));
                })
                .orElse(ApiResponse.error("Item not found"));
    }

    @DeleteMapping("/item/{id}")
    public ApiResponse<Void> deleteItem(@PathVariable Long id) {
        metricService.deleteItemById(id);
        return ApiResponse.success();
    }

    /**
     * 批量启用 / 禁用指标（2026-10-28 新增）。
     * 入参：{ "ids": [1,2,3], "status": 0 }，status=0 停用、1 启用。
     *
     * 指标状态是"总开关"：停用后该指标不再参与算分、不再产生告警/工单、
     * 不再出现在实时指标与运营报表中（历史数据保留）。
     */
    @PutMapping("/item/batch-status")
    public ApiResponse<Map<String, Object>> batchUpdateItemStatus(@RequestBody Map<String, Object> body) {
        Object rawIds = body.get("ids");
        Object rawStatus = body.get("status");
        if (!(rawIds instanceof List) || ((List<?>) rawIds).isEmpty()) {
            return ApiResponse.error("请先选择要操作的指标");
        }
        if (rawStatus == null) {
            return ApiResponse.error("缺少目标状态");
        }
        Integer status;
        try {
            status = Integer.valueOf(String.valueOf(rawStatus));
        } catch (NumberFormatException e) {
            return ApiResponse.error("状态值非法，只能是 0（停用）或 1（启用）");
        }
        if (status != 0 && status != 1) {
            return ApiResponse.error("状态值非法，只能是 0（停用）或 1（启用）");
        }
        List<Long> ids = new ArrayList<>();
        for (Object o : (List<?>) rawIds) {
            if (o == null) continue;
            try {
                ids.add(Long.valueOf(String.valueOf(o)));
            } catch (NumberFormatException e) {
                return ApiResponse.error("指标 ID 非法: " + o);
            }
        }
        int updated = metricService.batchUpdateItemStatus(ids, status);
        return ApiResponse.success(Map.of(
                "requested", ids.size(),
                "updated", updated,
                "status", status
        ));
    }

    // === 统计 API ===

    @GetMapping("/statistics")
    public ApiResponse<Map<String, Long>> getStatistics() {
        return ApiResponse.success(Map.of(
                "categoryCount", metricService.countCategories(),
                "itemCount", metricService.countItems(),
                "enabledCount", metricService.countEnabledItems(),
                "subCategoryCount", subCategoryRepository.count()
        ));
    }

    // === 导入导出 API ===

    // 下载空白导入模板（3 个 sheet，与导出结构同构）
    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        byte[] template = metricImportService.generateTemplate();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=metric_template.xlsx");
        response.getOutputStream().write(template);
        response.getOutputStream().flush();
    }

    // 配置导出 - 导出多sheet Excel（系统、指标大类、指标）
    @GetMapping("/export")
    public void exportConfig(HttpServletResponse response) throws IOException {
        byte[] data = metricImportService.exportFullConfig();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=metric_config.xlsx");
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }

    // ==================== 导入：预演 + 执行 两阶段 ====================
    //
    // 2026-10-28 起不再提供"清空重建"式的一步导入。原 /import-full 会 deleteAll 后重建，
    // 导致 metric_item.id 全变，17 张关联表（历史值、健康度、告警触发历史、策略等）断裂。
    // 现在改为：先 preview 出变更计划，确认后再 execute，且 upsert 保留原 id。

    /**
     * 第一步：校验 + 预演。只解析不落库。
     *
     * @param file                 指标模板（系统清单 / 指标大类清单 / 指标清单 三个 Sheet）
     * @param mode                 full=全量（Excel 里没有的指标会被删除）；incremental=增量（只增改不删）
     * @param allowDeleteCategory  是否允许删除整个大类（Excel 缺少库里已有大类时的护栏开关）
     */
    @PostMapping("/import/preview")
    public ApiResponse<Map<String, Object>> previewImport(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "incremental") String mode,
            @RequestParam(value = "allowDeleteCategory", defaultValue = "false") boolean allowDeleteCategory) {
        try {
            if (file == null || file.isEmpty()) {
                return ApiResponse.error("请选择要导入的文件");
            }
            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
                return ApiResponse.error("请上传 xlsx 格式的 Excel 文件");
            }
            return ApiResponse.success(metricImportPlanService.preview(file, mode, allowDeleteCategory));
        } catch (Exception e) {
            return ApiResponse.error("校验失败: " + e.getMessage());
        }
    }

    /**
     * 第二步：按预演结果执行。需要带上 preview 返回的 token；
     * 删除量较大时还需带确认词（preview 返回的 confirmWord）。
     */
    @PostMapping("/import/execute")
    public ApiResponse<Map<String, Object>> executeImport(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ApiResponse.error("缺少导入令牌，请重新校验文件");
        }
        try {
            return ApiResponse.success(metricImportPlanService.execute(token, body.get("confirmWord")));
        } catch (IllegalStateException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    // 获取备份列表
    @GetMapping("/backup-list")
    public ApiResponse<List<Map<String, Object>>> getBackupList() {
        return ApiResponse.success(metricImportService.getBackupList());
    }

    // 下载备份
    @GetMapping("/backup-download/{id}")
    public void downloadBackup(@PathVariable Long id, HttpServletResponse response) throws IOException {
        byte[] data = metricImportService.exportBackup(id);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=backup_" + id + ".xlsx");
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }

    // 恢复备份
    @PostMapping("/backup-restore/{id}")
    public ApiResponse<Map<String, Object>> restoreBackup(@PathVariable Long id) {
        Map<String, Object> result = metricImportService.restoreBackup(id);
        return ApiResponse.success(result);
    }

    // 重新计算所有指标值
    @PostMapping("/calc-all-values")
    public ApiResponse<String> calcAllValues() {
        try {
            metricValueCalcService.calcAllMetricValues();
            return ApiResponse.success("指标值计算完成");
        } catch (Exception e) {
            return ApiResponse.error("计算失败: " + e.getMessage());
        }
    }

    // 重新计算所有健康度
    @PostMapping("/calc-all-health")
    public ApiResponse<String> calcAllHealth() {
        try {
            metricValueCalcService.calcAllHealthScores();
            return ApiResponse.success("健康度计算完成");
        } catch (Exception e) {
            return ApiResponse.error("计算失败: " + e.getMessage());
        }
    }
}
