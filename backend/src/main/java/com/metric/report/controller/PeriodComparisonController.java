package com.metric.report.controller;

import com.metric.report.dto.PeriodComparisonRequest;
import com.metric.report.service.PeriodComparisonService;
import com.metric.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运营报表「环比分析」：多维度（大类健康度/小类健康度/指标值）两时段对比。
 */
@RestController
@RequestMapping("/api/report")
public class PeriodComparisonController {

    private final PeriodComparisonService service;

    public PeriodComparisonController(PeriodComparisonService service) {
        this.service = service;
    }

    @PostMapping("/period-comparison")
    public ApiResponse<Map<String, Object>> compare(@RequestBody PeriodComparisonRequest request) {
        return ApiResponse.success(service.compare(request));
    }

    /**
     * 进页默认预选的指标（最近有采集记录的指标，按大类封顶）。
     * 页面用它把「进页一片空白」变成「进页就有几个指标在对比」。
     */
    @GetMapping("/period-comparison/active-metrics")
    public ApiResponse<List<Map<String, Object>>> activeMetrics(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "2") int perCategory,
            @RequestParam(defaultValue = "8") int limit) {
        return ApiResponse.success(service.activeMetrics(days, perCategory, limit));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> invalid(IllegalArgumentException error) {
        return ApiResponse.error(400, error.getMessage());
    }
}
