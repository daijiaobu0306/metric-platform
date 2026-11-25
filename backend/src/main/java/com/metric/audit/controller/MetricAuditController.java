package com.metric.audit.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.audit.entity.MetricAuditLog;
import com.metric.audit.service.MetricAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import java.util.Map;

@RestController
@RequestMapping("/api/metric/audit")
public class MetricAuditController {

    @Autowired
    private MetricAuditService metricAuditService;

    /**
     * 同步系统稽核指标
     */
    @PostMapping("/sync")
    public ApiResponse<Map<String, Object>> syncSystemAuditMetrics() {
        return ApiResponse.success(metricAuditService.syncSystemAuditMetrics());
    }

    /**
     * 获取审计日志列表
     */
    @GetMapping("/logs")
    public ApiResponse<Page<MetricAuditLog>> getAuditLogs(
            @RequestParam(required = false) String operationType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createTime");
        PageRequest pageable = PageRequest.of(page, size, sort);
        return ApiResponse.success(metricAuditService.getAuditLogs(operationType, pageable));
    }

    @PostMapping("/batch-delete")
    public ApiResponse<Void> batchDelete(@RequestBody List<Long> ids) {
        metricAuditService.batchDelete(ids);
        return ApiResponse.success();
    }
}
