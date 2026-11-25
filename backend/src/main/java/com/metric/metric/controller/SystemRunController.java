package com.metric.metric.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.metric.entity.MetricSystemRunDetail;
import com.metric.metric.entity.MetricSystemRunSummary;
import com.metric.metric.repository.MetricSystemRunDetailRepository;
import com.metric.metric.repository.MetricSystemRunSummaryRepository;
import com.metric.metric.service.SystemRunImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system-run")
public class SystemRunController {

    @Autowired
    private SystemRunImportService systemRunImportService;

    @Autowired
    private MetricSystemRunSummaryRepository summaryRepository;

    @Autowired
    private MetricSystemRunDetailRepository detailRepository;

    // 手动导入Excel文件
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        systemRunImportService.importExcel(file);
        Map<String, Object> result = new HashMap<>();
        result.put("message", "导入成功");
        return ApiResponse.success(result);
    }

    // 获取汇总指标数据
    @GetMapping("/summary")
    public ApiResponse<List<MetricSystemRunSummary>> getSummary(
            @RequestParam(required = false) String subCategoryCode) {
        List<MetricSystemRunSummary> list;
        if (subCategoryCode != null) {
            list = summaryRepository.findAll().stream()
                    .filter(s -> subCategoryCode.equals(s.getSubCategoryCode()))
                    .toList();
        } else {
            list = summaryRepository.findAll();
        }
        return ApiResponse.success(list);
    }

    // 获取明细指标数据
    @GetMapping("/detail")
    public ApiResponse<List<MetricSystemRunDetail>> getDetail(
            @RequestParam(required = false) String subCategoryCode) {
        List<MetricSystemRunDetail> list;
        if (subCategoryCode != null) {
            list = detailRepository.findAll().stream()
                    .filter(d -> subCategoryCode.equals(d.getSubCategoryCode()))
                    .toList();
        } else {
            list = detailRepository.findAll();
        }
        return ApiResponse.success(list);
    }
}