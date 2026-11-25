package com.metric.audit.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.audit.entity.EntityAuditLog;
import com.metric.audit.service.EntityAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/entity-audit")
public class EntityAuditController {

    @Autowired
    private EntityAuditService entityAuditService;

    @GetMapping("/logs")
    public ApiResponse<Page<EntityAuditLog>> getLogs(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<EntityAuditLog> logs = entityAuditService.findLogs(entityType, entityId, operationType, startTime, endTime, page, size);
        return ApiResponse.success(logs);
    }

    @GetMapping("/logs/entity/{entityType}")
    public ApiResponse<Page<EntityAuditLog>> getLogsByEntityType(
            @PathVariable String entityType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<EntityAuditLog> logs = entityAuditService.findByEntityType(entityType, page, size);
        return ApiResponse.success(logs);
    }

    @GetMapping("/logs/{entityType}/{entityId}")
    public ApiResponse<List<EntityAuditLog>> getLogsByEntityId(
            @PathVariable String entityType,
            @PathVariable Long entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<EntityAuditLog> logs = entityAuditService.findByEntityId(entityType, entityId, page, size);
        return ApiResponse.success(logs.getContent());
    }

    @GetMapping("/statistics")
    public ApiResponse<Map<String, Object>> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("entityTypes", new String[]{EntityAuditService.ENTITY_GONGDAN, EntityAuditService.ENTITY_ISSUE});
        stats.put("operationTypes", new String[]{
                EntityAuditService.OP_CREATE, EntityAuditService.OP_UPDATE,
                EntityAuditService.OP_DELETE, EntityAuditService.OP_CLOSE,
                EntityAuditService.OP_RESOLVE_STATUS_CHANGE
        });
        return ApiResponse.success(stats);
    }

    @PostMapping("/batch-delete")
    public ApiResponse<Void> batchDelete(@RequestBody List<Long> ids) {
        entityAuditService.batchDelete(ids);
        return ApiResponse.success();
    }
}