package com.metric.alarm.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.alarm.entity.AlarmAuditLog;
import com.metric.alarm.service.AlarmAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarm-audit")
public class AlarmAuditController {

    @Autowired
    private AlarmAuditService alarmAuditService;

    /**
     * 分页查询操作日志
     */
    @GetMapping("/logs")
    public ApiResponse<Page<AlarmAuditLog>> getLogs(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AlarmAuditLog> logs = alarmAuditService.findLogs(entityType, operationType, startTime, endTime, page, size);
        return ApiResponse.success(logs);
    }

    /**
     * 根据实体类型查询日志
     */
    @GetMapping("/logs/entity/{entityType}")
    public ApiResponse<Page<AlarmAuditLog>> getLogsByEntityType(
            @PathVariable String entityType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AlarmAuditLog> logs = alarmAuditService.findByEntityType(entityType, page, size);
        return ApiResponse.success(logs);
    }

    /**
     * 根据实体ID查询日志
     */
    @GetMapping("/logs/{entityType}/{entityId}")
    public ApiResponse<List<AlarmAuditLog>> getLogsByEntityId(
            @PathVariable String entityType,
            @PathVariable Long entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<AlarmAuditLog> logs = alarmAuditService.findByEntityId(entityType, entityId, page, size);
        return ApiResponse.success(logs.getContent());
    }

    /**
     * 获取统计数据
     */
    @GetMapping("/statistics")
    public ApiResponse<Map<String, Object>> getStatistics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        // 简化实现，实际可按需扩展
        Map<String, Object> stats = new HashMap<>();
        stats.put("entityTypes", new String[]{"alarm", "alarm_strategy", "alarm_channel", "shield_rule", "raw_data_alarm"});
        stats.put("operationTypes", new String[]{
                "SHIELD", "UNSHIELD", "PROCESS", "CANCEL_PROCESS", "TAG_TO_ISSUE",
                "STRATEGY_CREATE", "STRATEGY_UPDATE", "STRATEGY_DELETE",
                "CHANNEL_CREATE", "CHANNEL_UPDATE", "CHANNEL_DELETE"
        });
        return ApiResponse.success(stats);
    }

    @PostMapping("/batch-delete")
    public ApiResponse<Void> batchDelete(@RequestBody List<Long> ids) {
        alarmAuditService.batchDelete(ids);
        return ApiResponse.success();
    }
}