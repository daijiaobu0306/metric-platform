package com.metric.collect.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.collect.entity.OriginalDataImportLog;
import com.metric.collect.service.OriginalDataImportLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/original-data-import")
public class OriginalDataImportLogController {

    @Autowired
    private OriginalDataImportLogService importLogService;

    @GetMapping("/logs")
    public ApiResponse<Page<OriginalDataImportLog>> getImportLogs(
            @RequestParam(required = false) String subCategoryCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(importLogService.getImportLogs(subCategoryCode, page, size));
    }

    @GetMapping("/today-logs")
    public ApiResponse<Page<OriginalDataImportLog>> getTodayImportLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(importLogService.getTodayImportLogs(page, size));
    }

    @GetMapping("/statistics")
    public ApiResponse<Map<String, Object>> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        // today*：仅今日；total*：全时段累计（不会被 0 点重置，平台数据流总览用这个）
        stats.put("todayFileCount", importLogService.getTodayFileCount());
        stats.put("totalFileCount", importLogService.getTotalFileCount());
        stats.put("tableCount", importLogService.getTableCount());
        stats.put("todayRecordCount", importLogService.getTodayRecordCount());
        return ApiResponse.success(stats);
    }

    @PostMapping("/batch-delete")
    public ApiResponse<Void> batchDelete(@RequestBody List<Long> ids) {
        importLogService.batchDelete(ids);
        return ApiResponse.success();
    }
}