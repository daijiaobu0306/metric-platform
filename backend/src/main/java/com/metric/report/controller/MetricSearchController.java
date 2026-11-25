package com.metric.report.controller;

import com.metric.metric.service.MetricPeriodQueryService;
import com.metric.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营报表「指标检索」：按指标 + 数据时间区间（精确到秒）查询采集批次明细。
 */
@RestController
@RequestMapping("/api/report")
public class MetricSearchController {
    private final MetricPeriodQueryService metrics;
    public MetricSearchController(MetricPeriodQueryService metrics) { this.metrics = metrics; }

    @GetMapping("/metric-search")
    public ApiResponse<Map<String, Object>> search(@RequestParam Long metricId,
                                                   @RequestParam String startTime,
                                                   @RequestParam String endTime,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        LocalDateTime start = parse(startTime);
        LocalDateTime end = parse(endTime);
        if (start.isAfter(end)) throw new IllegalArgumentException("开始时间不能晚于结束时间");
        return ApiResponse.success(metrics.observations(metricId, start, end, page, size));
    }

    @GetMapping("/metric-catalog")
    public ApiResponse<List<Map<String, Object>>> catalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : metrics.catalog(null)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("metricId", row.get("metricId"));
            item.put("metricCode", row.get("metricCode"));
            item.put("metricName", row.get("metricName"));
            item.put("unit", row.get("unit"));
            item.put("categoryName", row.get("categoryName"));
            item.put("subCategoryName", row.get("subCategoryName"));
            item.put("collectFrequency", row.get("collectFrequency"));
            item.put("collectionMode", row.get("ruleType"));
            out.add(item);
        }
        return ApiResponse.success(out);
    }

    private static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("时间参数不能为空");
        try {
            return LocalDateTime.parse(s.trim().replace(' ', 'T'));
        } catch (Exception e) {
            throw new IllegalArgumentException("时间格式应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> invalid(IllegalArgumentException error) {
        return ApiResponse.error(400, error.getMessage());
    }
}
