package com.metric.metric.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.metric.entity.*;
import com.metric.metric.repository.*;
import com.metric.metric.service.*;
import com.metric.collect.service.InnerDataScanScheduler;
import com.metric.collect.service.ScanHealthService;
import com.metric.config.service.ErrorFrontendScanScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/error")
public class MetricErrorConfigController {

    @Autowired
    private MetricErrorKeywordRepository keywordRepository;

    @Autowired
    private MetricImportConfigRepository importConfigRepository;

    @Autowired
    private MetricErrorImportService importService;

    @Autowired
    private MetricItemRepository metricItemRepository;

    @Autowired
    private MetricMonitorConfigRepository monitorConfigRepository;

    @Autowired
    private ScanHealthService scanHealthService;

    @Autowired
    private LegacyCollectionGuard collectionGuard;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ==================== 关键词配置 API ====================

    // 获取关键词列表
    @GetMapping("/keyword/list")
    public ApiResponse<List<MetricErrorKeyword>> listKeywords(@RequestParam(required = false) Long metricId) {
        if (metricId != null) {
            return ApiResponse.success(keywordRepository.findByMetricId(metricId));
        }
        return ApiResponse.success(keywordRepository.findAll());
    }

    // 获取单个关键词
    @GetMapping("/keyword/{id}")
    public ApiResponse<MetricErrorKeyword> getKeyword(@PathVariable Long id) {
        return ApiResponse.success(keywordRepository.findById(id).orElse(null));
    }

    // 创建关键词
    @PostMapping("/keyword")
    public ApiResponse<MetricErrorKeyword> createKeyword(@RequestBody MetricErrorKeyword keyword) {
        return ApiResponse.success(keywordRepository.save(keyword));
    }

    // 更新关键词
    @PutMapping("/keyword/{id}")
    public ApiResponse<MetricErrorKeyword> updateKeyword(@PathVariable Long id, @RequestBody MetricErrorKeyword keyword) {
        keyword.setId(id);
        return ApiResponse.success(keywordRepository.save(keyword));
    }

    // 删除关键词
    @DeleteMapping("/keyword/{id}")
    public ApiResponse<Void> deleteKeyword(@PathVariable Long id) {
        keywordRepository.deleteById(id);
        return ApiResponse.success();
    }

    // 按指标删除关键词
    @DeleteMapping("/keyword/metric/{metricId}")
    public ApiResponse<Void> deleteKeywordsByMetric(@PathVariable Long metricId) {
        keywordRepository.deleteByMetricId(metricId);
        return ApiResponse.success();
    }

    // ==================== 导入配置 API ====================

    // 获取导入配置列表
    @GetMapping("/import/config/list")
    public ApiResponse<List<Map<String, Object>>> listImportConfigs() {
        var rows = importConfigRepository.findAll().stream().map(config -> {
            Map<String, Object> row = objectMapper.convertValue(config, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            boolean available = LegacyImportSupport.available(config.getSubCategoryCode());
            row.put("collectorAvailable", available);
            row.put("collectionStatus", available ? (Integer.valueOf(1).equals(config.getEnabled()) ? "启用" : "停用") : "未接入（已停止采集）");
            return row;
        }).toList();
        return ApiResponse.success(rows);
    }

    // 获取单个导入配置
    @GetMapping("/import/config/{id}")
    public ApiResponse<MetricImportConfig> getImportConfig(@PathVariable Long id) {
        return ApiResponse.success(importConfigRepository.findById(id).orElse(null));
    }

    // 按小类编码获取导入配置
    @GetMapping("/import/config/subcategory/{subCategoryCode}")
    public ApiResponse<MetricImportConfig> getImportConfigBySubCategory(@PathVariable String subCategoryCode) {
        return ApiResponse.success(importConfigRepository.findBySubCategoryCode(subCategoryCode).orElse(null));
    }

    // 创建导入配置
    @PostMapping("/import/config")
    public ApiResponse<MetricImportConfig> createImportConfig(@RequestBody MetricImportConfig config) {
        try {
            collectionGuard.prepareConfig(config);
            return ApiResponse.success(importConfigRepository.save(config));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        } catch (DataIntegrityViolationException e) {
            String msg = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
            if (msg != null && msg.contains("uk_sub_category")) {
                return ApiResponse.error("该小类编码已存在配置: " + config.getSubCategoryCode());
            }
            return ApiResponse.error("数据完整性错误: " + msg);
        }
    }

    // 更新导入配置
    @PutMapping("/import/config/{id}")
    public ApiResponse<MetricImportConfig> updateImportConfig(@PathVariable Long id, @RequestBody MetricImportConfig config) {
        try {
            config.setId(id);
            var existing = importConfigRepository.findById(id);
            if (existing.isEmpty()) return ApiResponse.error("导入配置不存在");
            if (!Objects.equals(existing.get().getSubCategoryCode(), config.getSubCategoryCode()))
                return ApiResponse.error("不能更换已有配置的小类编码，请新建配置");
            collectionGuard.prepareConfig(config);
            return ApiResponse.success(importConfigRepository.save(config));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        } catch (DataIntegrityViolationException e) {
            String msg = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
            if (msg != null && msg.contains("uk_sub_category")) {
                return ApiResponse.error("该小类编码已存在配置: " + config.getSubCategoryCode());
            }
            return ApiResponse.error("数据完整性错误: " + msg);
        }
    }

    // 删除导入配置
    @DeleteMapping("/import/config/{id}")
    public ApiResponse<Void> deleteImportConfig(@PathVariable Long id) {
        importConfigRepository.deleteById(id);
        return ApiResponse.success();
    }

    // ==================== 扫描健康检查 API ====================

    /**
     * 检查所有已启用导入配置的扫描目录健康状态
     * 返回各小类的目录是否存在、问题诊断和修复建议
     */
    @GetMapping("/scan-health")
    public ApiResponse<Map<String, Object>> getScanHealth() {
        return ApiResponse.success(scanHealthService.checkHealth());
    }

    // 触发指定类别的扫描
    @PostMapping("/import/scan/{subCategoryCode}")
    public ApiResponse<String> triggerScan(@PathVariable String subCategoryCode) {
        try {
            Optional<MetricImportConfig> configOpt = importConfigRepository.findBySubCategoryCode(subCategoryCode);
            if (configOpt.isEmpty()) {
                return ApiResponse.error("未找到对应的导入配置: " + subCategoryCode);
            }

            LegacyImportSupport.requireAvailable(subCategoryCode);

            // 根据 subCategoryCode 调用对应的扫描服务
            switch (subCategoryCode) {
                case "error" -> metricErrorScanScheduler.scanAndImportByConfig(configOpt.get());
                case "error_frontend" -> errorFrontendScanScheduler.scanAndImportByConfig(configOpt.get());
                case "performance" -> systemRunScanScheduler.scanAndImportByConfig(configOpt.get());
                case "inner" -> innerDataScanScheduler.scanAndImportByConfig(configOpt.get());
                case "jihe" -> systemJhdScanScheduler.scanAndImportByConfig(configOpt.get());
                case "jihe_detail" -> systemJhdDetailScanScheduler.scanAndImportByConfig(configOpt.get());
                case "ability_metric" -> abilityMetricScanScheduler.scanAndImportByConfig(configOpt.get());
                default -> { return ApiResponse.error("未知的子类别: " + subCategoryCode); }
            }
            return ApiResponse.success("扫描触发成功: " + subCategoryCode);
        } catch (Exception e) {
            System.err.println("触发扫描失败: " + e.getMessage());
            return ApiResponse.error("触发扫描失败: " + e.getMessage());
        }
    }

    @Autowired
    private MetricErrorScanScheduler metricErrorScanScheduler;

    @Autowired
    private ErrorFrontendScanScheduler errorFrontendScanScheduler;

    @Autowired
    private SystemRunImportScheduler systemRunScanScheduler;

    @Autowired
    private InnerDataScanScheduler innerDataScanScheduler;

    @Autowired
    private SystemJhdScanScheduler systemJhdScanScheduler;

    @Autowired
    private SystemJhdDetailScanScheduler systemJhdDetailScanScheduler;

    @Autowired
    private AbilityMetricScanScheduler abilityMetricScanScheduler;

    // ==================== 下钻明细 API ====================

    /**
     * 获取指标下钻明细（带 metadata）
     * 返回结构：{ metricId, metricName, subCategoryName,
     *           threshold, keywords, tableName, dataTime, rowCount, content, reason }
     * content 是原始记录列表（该指标计算背后的原始数据）
     */
    @GetMapping("/drilldown/{metricId}")
    public ApiResponse<Map<String, Object>> getDrilldown(
            @PathVariable Long metricId,
            @RequestParam(required = false) BigDecimal threshold) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metricId", metricId);

        Optional<MetricItem> itemOpt = metricItemRepository.findById(metricId);
        if (itemOpt.isEmpty()) {
            result.put("content", new ArrayList<>());
            result.put("rowCount", 0);
            result.put("reason", "指标不存在 metricId=" + metricId);
            return ApiResponse.success(result);
        }
        MetricItem metricItem = itemOpt.get();
        result.put("metricName", metricItem.getMetricName());
        result.put("subCategoryName", metricItem.getSubCategoryName());
        result.put("subCategoryCode", metricItem.getSubCategoryCode());

        result.put("threshold", threshold != null ? threshold : monitorConfigRepository.findByMetricId(metricId)
                .map(MetricMonitorConfig::getAlarmThreshold).orElse(null));
        result.put("content", new ArrayList<>());
        result.put("rowCount", 0);
        result.put("reason", "旧计算规则已停用，请通过采集数据来源查看指标明细");
        return ApiResponse.success(result);
    }
}
