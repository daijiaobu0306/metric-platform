package com.metric.collect.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.shared.dto.SyncResult;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.entity.MetricMonitorConfig;
import com.metric.metric.service.MetricMonitorService;
import com.metric.collect.service.RealTimeMetricService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/realtime")
public class RealTimeMetricController {

    @Autowired
    private RealTimeMetricService realtimeMetricService;

    @Autowired
    private MetricMonitorService metricMonitorService;

    /**
     * 获取实时指标数据 - 业务视角（用于实时指标页面）
     * 按业务聚合，展示哪些业务超时了、超时多少
     * 如果传了 metricId，则只取该指标的最新值（不再聚合 subcategory 下所有指标）
     */
    @GetMapping("/metrics")
    public ApiResponse<Map<String, Object>> getRealtimeMetrics(
            @RequestParam(required = false) Long metricId,
            @RequestParam(required = false, defaultValue = "对内部人员") String categoryName,
            @RequestParam(required = false, defaultValue = "查询类") String subCategoryName,
            @RequestParam(required = false) String dataDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Map<String, Object> result = realtimeMetricService.getRealtimeMetricsByBusiness(
                metricId, categoryName, subCategoryName, dataDate, page, size);

        return ApiResponse.success(result);
    }

    /**
     * 获取实时告警数据（从 alarm 表读取）
     */
    @GetMapping("/alerts")
    public ApiResponse<Map<String, Object>> getRealtimeAlerts(
            @RequestParam(required = false, defaultValue = "对内部人员") String categoryName,
            @RequestParam(required = false) String subCategoryName,
            @RequestParam(required = false) String dataDate) {

        Map<String, Object> result = realtimeMetricService.getRealtimeAlerts(
                categoryName, subCategoryName, dataDate);

        return ApiResponse.success(result);
    }

    /**
     * 获取实时指标配置列表（用于页面初始化）
     */
    @GetMapping("/metric-configs")
    public ApiResponse<List<MetricItem>> getMetricConfigs(
            @RequestParam(required = false, defaultValue = "对内部人员") String categoryName) {

        List<MetricItem> configs = realtimeMetricService.getMetricConfigs(categoryName);
        return ApiResponse.success(configs);
    }

    /**
     * 获取所有实时监控指标（用于运营报表-实时指标页面）
     */
    @GetMapping("/monitor/metrics")
    public ApiResponse<List<Map<String, Object>>> getAllMonitorMetrics(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String subCategory,
            @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        List<Map<String, Object>> metrics = metricMonitorService.getRealtimeMetrics(category, subCategory, refresh);
        return ApiResponse.success(metrics);
    }

    /**
     * 获取业务视角的实时指标（用于运营报表-实时指标页面对外部客户/对内部人员/计划执行）
     * 从 metric_realtime 表读取，按指标大类和小类过滤
     */
    @GetMapping("/business/metrics")
    public ApiResponse<List<Map<String, Object>>> getBusinessRealtimeMetrics(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String subCategory,
            @RequestParam(required = false) String timeRange) {
        List<Map<String, Object>> metrics = metricMonitorService.getBusinessRealtimeMetrics(category, subCategory, timeRange);
        return ApiResponse.success(metrics);
    }

    /**
     * 获取单个指标的实时数据
     */
    @GetMapping("/monitor/metrics/{metricId}")
    public ApiResponse<Map<String, Object>> getMonitorMetricById(@PathVariable Long metricId) {
        Map<String, Object> metric = metricMonitorService.getRealtimeMetricById(metricId);
        if (metric == null) {
            return ApiResponse.error("未找到该指标的实时数据");
        }
        return ApiResponse.success(metric);
    }

    /**
     * 获取监控配置
     */
    @GetMapping("/monitor/config/{metricId}")
    public ApiResponse<MetricMonitorConfig> getMonitorConfig(@PathVariable Long metricId) {
        MetricMonitorConfig config = metricMonitorService.getMonitorConfig(metricId);
        if (config == null) {
            return ApiResponse.error("未找到该指标的监控配置");
        }
        return ApiResponse.success(config);
    }

    /**
     * 更新监控配置
     */
    @PutMapping("/monitor/config/{metricId}")
    public ApiResponse<SyncResult> updateMonitorConfig(
            @PathVariable Long metricId,
            @RequestBody MetricMonitorConfig config) {
        SyncResult result = metricMonitorService.updateMonitorConfig(metricId, config);
        return ApiResponse.success(result);
    }

    /**
     * 手动触发一次监控扫描
     */
    @PostMapping("/monitor/scan")
    public ApiResponse<Map<String, Object>> triggerScan() {
        int count = metricMonitorService.scanAndUpdateMetrics();
        Map<String, Object> result = Map.of("message", "扫描完成", "processedCount", count);
        return ApiResponse.success(result);
    }

    /**
     * 阈值模拟 - 基于历史数据预测告警数量
     */
    @PostMapping("/monitor/simulate")
    public ApiResponse<Map<String, Object>> simulateThreshold(
            @RequestParam Long metricId,
            @RequestParam BigDecimal threshold,
            @RequestParam String condition,
            @RequestParam(required = false) LocalDateTime startTime,
            @RequestParam(required = false) LocalDateTime endTime) {
        Map<String, Object> result = metricMonitorService.simulateThreshold(metricId, threshold, condition, startTime, endTime);
        return ApiResponse.success(result);
    }
}