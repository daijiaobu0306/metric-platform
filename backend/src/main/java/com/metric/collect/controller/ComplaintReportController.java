package com.metric.collect.controller;

import com.metric.collect.service.ComplaintReportService;
import com.metric.security.service.CurrentUserProvider;
import com.metric.shared.dto.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/collection/satisfaction-report")
@ConditionalOnProperty(name = "collection.satisfaction.enabled", havingValue = "true", matchIfMissing = true)
public class ComplaintReportController {
    private final ComplaintReportService service;
    private final CurrentUserProvider users;
    @org.springframework.beans.factory.annotation.Autowired private com.metric.collect.service.SatisfactionAlarmRuleService rules;
    public ComplaintReportController(ComplaintReportService service, CurrentUserProvider users) { this.service = service; this.users = users; }
    @GetMapping
    public ApiResponse<Map<String, Object>> overview(@RequestParam(required = false) String batchId) { return ApiResponse.success(service.overview(batchId)); }
    @PutMapping("/alarm-rule/{metricId}")
    public ApiResponse<Void> updateRule(@PathVariable long metricId, @RequestBody Map<String, Object> input) {
        requireAdmin();
        try { rules.update(metricId, input); return ApiResponse.success(); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }
    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam MultipartFile file, @RequestParam LocalDate businessDate) throws IOException {
        requireAdmin();
        try { throw new ResponseStatusException(HttpStatus.GONE, "旧报表采集已下线，请使用满意度及投诉服务体验脚本采集任务"); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }
    @PostMapping("/scan")
    public ApiResponse<Map<String, Object>> scan() { requireAdmin(); throw new ResponseStatusException(HttpStatus.GONE, "旧报表采集已下线，请使用脚本采集任务"); }
    @PostMapping("/reprocess")
    public ApiResponse<Map<String, Object>> reprocess() throws IOException { requireAdmin(); throw new ResponseStatusException(HttpStatus.GONE, "旧报表重处理已下线，历史记录保留"); }
    private void requireAdmin() {
        if (users.currentUser().filter(user -> Integer.valueOf(1).equals(user.getIsAdmin())).isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可采集报表");
    }
}
