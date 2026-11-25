package com.metric.metric.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.metric.service.WeightConfigService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/weight")
public class WeightConfigController {

    @Autowired
    private WeightConfigService weightConfigService;

    // 获取权重配置
    @GetMapping("/config/{systemId}")
    public ApiResponse<Map<String, Object>> getWeightConfig(@PathVariable Long systemId) {
        return ApiResponse.success(weightConfigService.getWeightConfig(systemId));
    }

    // 更新大类权重
    @PutMapping("/category")
    public ApiResponse<Void> updateCategoryWeights(
            @RequestParam Long systemId,
            @RequestBody List<Map<String, Object>> weights) {
        weightConfigService.updateCategoryWeights(systemId, weights);
        return ApiResponse.success();
    }

    // 更新小类权重
    @PutMapping("/subcategory")
    public ApiResponse<Void> updateSubcategoryWeights(
            @RequestParam Long categoryId,
            @RequestBody List<Map<String, Object>> weights) {
        weightConfigService.updateSubcategoryWeights(categoryId, weights);
        return ApiResponse.success();
    }

    // 更新指标权重
    @PutMapping("/metric")
    public ApiResponse<Void> updateMetricWeights(
            @RequestParam Long categoryId,
            @RequestBody List<Map<String, Object>> weights) {
        weightConfigService.updateMetricWeights(categoryId, weights);
        return ApiResponse.success();
    }

    // 导出权重配置
    @GetMapping("/export/{systemId}")
    public void exportWeightConfig(@PathVariable Long systemId, HttpServletResponse response) throws IOException {
        byte[] data = weightConfigService.exportWeightConfig(systemId);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "weight_config_" + timestamp + ".xlsx";

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(filename, StandardCharsets.UTF_8));
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }
}
