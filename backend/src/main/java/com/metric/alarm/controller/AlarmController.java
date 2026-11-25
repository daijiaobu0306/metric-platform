package com.metric.alarm.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.alarm.entity.Alarm;
import com.metric.alarm.service.AlarmService;
import com.metric.collect.service.AlertGenerateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarm")
public class AlarmController {

    @Autowired
    private AlarmService alarmService;

    @Autowired private com.metric.alarm.service.EffectiveObservationService effectiveObservationService;

    @GetMapping("/observation-status")
    public ApiResponse<List<Map<String, Object>>> observationStatus(@RequestParam Long strategyId) {
        return ApiResponse.success(effectiveObservationService.status(strategyId));
    }

    @Autowired
    private com.metric.alarm.service.AlarmConversionService alarmConversionService;

    @Autowired
    private com.metric.gongdan.service.GongdanIntegrationService gongdanIntegrationService;

    @PostMapping("/conversion/preview")
    public ApiResponse<Map<String, Object>> conversionPreview(@RequestBody com.metric.alarm.dto.AlarmConversionRequest request) {
        return ApiResponse.success(alarmConversionService.preview(request));
    }

    @PostMapping("/conversion")
    public ApiResponse<Map<String, Object>> convert(@RequestBody com.metric.alarm.dto.AlarmConversionRequest request) {
        Map<String, Object> result = new java.util.HashMap<>(alarmConversionService.convert(request));
        if ("gongdan".equals(request.getKind())) {
            // 本地事务已提交。对接失败不让用户误认为创建失败而重复建单。
            try {
                var push = gongdanIntegrationService.pushToBomc(((Number) result.get("id")).longValue());
                result.put("pushSuccess", push.isSuccess());
                result.put("pushMessage", java.util.Objects.toString(push.getMessage(), ""));
            } catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger(AlarmController.class).error("告警转工单已保存，推送失败", error);
                result.put("pushSuccess", false);
                result.put("pushMessage", "本地工单已保存，请在工单管理查看推送状态");
            }
        }
        return ApiResponse.success(result);
    }

    @Autowired
    private com.metric.alarm.service.AlarmSummaryService alarmSummaryService;

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(@ModelAttribute com.metric.alarm.dto.AlarmSummaryRequest request) {
        return ApiResponse.success(alarmSummaryService.summary(request));
    }

    @GetMapping("/summary/history")
    public ApiResponse<Map<String, Object>> history(@ModelAttribute com.metric.alarm.dto.AlarmSummaryRequest request) {
        return ApiResponse.success(alarmSummaryService.history(request));
    }

    @PostMapping("/summary/action")
    public ApiResponse<Map<String, Object>> groupAction(@RequestBody com.metric.alarm.dto.AlarmSummaryRequest request) {
        String actor = alarmService.currentOperator();
        return ApiResponse.success(alarmSummaryService.act(request, actor));
    }

    @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
    public ApiResponse<Void> invalidSummary(RuntimeException error) { return ApiResponse.error(error.getMessage()); }

    @GetMapping
    public ApiResponse<Page<Alarm>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long metricId,
            @RequestParam(required = false) String alarmType,
            @RequestParam(required = false) Integer processStatus,
            @RequestParam(required = false) Integer shieldStatus,
            @RequestParam(required = false) String alarmContent,
            @RequestParam(required = false) String alarmTimeStart,
            @RequestParam(required = false) String alarmTimeEnd,
            @RequestParam(defaultValue = "alarmTime") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        Sort sort = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        // 使用多维搜索
        return ApiResponse.success(alarmService.search(
                systemId, categoryId, metricId, alarmType, processStatus, shieldStatus, alarmContent, alarmTimeStart, alarmTimeEnd, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<Alarm> findById(@PathVariable Long id) {
        return alarmService.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Alarm not found"));
    }

    @PostMapping
    public ApiResponse<Alarm> create(@RequestBody Alarm alarm) {
        return ApiResponse.success(alarmService.save(alarm));
    }

    @PutMapping("/{id}")
    public ApiResponse<Alarm> update(@PathVariable Long id, @RequestBody Alarm alarm) {
        return alarmService.findById(id)
                .map(existing -> {
                    alarm.setId(id);
                    return ApiResponse.success(alarmService.save(alarm));
                })
                .orElse(ApiResponse.error("Alarm not found"));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        alarmService.deleteById(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/process")
    public ApiResponse<Alarm> process(
            @PathVariable Long id,
            @RequestParam Integer processStatus,
            @RequestParam(required = false) String processUser) {
        String actor = processUser != null && !processUser.isBlank() ? processUser
                : alarmService.currentOperator();
        Alarm alarm = alarmService.updateProcessStatus(id, processStatus, actor);
        return alarm != null ? ApiResponse.success(alarm) : ApiResponse.error("Alarm not found");
    }

    @PutMapping("/{id}/shield")
    public ApiResponse<Alarm> shield(
            @PathVariable Long id,
            @RequestParam Integer shieldStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime shieldEndTime) {
        Alarm alarm = alarmService.updateShieldStatus(id, shieldStatus, shieldEndTime);
        return alarm != null ? ApiResponse.success(alarm) : ApiResponse.error("Alarm not found");
    }

    @PutMapping("/{id}/tag")
    public ApiResponse<Alarm> updateTagStatus(
            @PathVariable Long id,
            @RequestParam Integer tagStatus,
            @RequestParam(required = false) Long relatedIssueId) {
        Alarm alarm = alarmService.updateTagStatus(id, tagStatus, relatedIssueId);
        return alarm != null ? ApiResponse.success(alarm) : ApiResponse.error("Alarm not found");
    }

    @GetMapping("/statistics")
    public ApiResponse<Map<String, Long>> getStatistics() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime thirtyDaysAgo = now.minusDays(30);

        return ApiResponse.success(Map.of(
                "totalAlarmCount", alarmService.countByAlarmTimeAfter(thirtyDaysAgo),
                "unprocessedCount", alarmService.countByProcessStatus(0),
                "processedCount", alarmService.countByProcessStatus(1)
        ));
    }

    @GetMapping("/trend")
    public ApiResponse<List<Map<String, Object>>> getTrend(
            @RequestParam(defaultValue = "today") String dimension,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ApiResponse.success(alarmService.getTrend(dimension, type, startDate, endDate));
    }

    @GetMapping("/is-shielded")
    public ApiResponse<Boolean> isShielded(
            @RequestParam Long systemId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long metricId) {
        Alarm dummy = new Alarm();
        dummy.setSystemId(systemId);
        dummy.setCategoryId(categoryId);
        dummy.setMetricId(metricId);
        return ApiResponse.success(alarmService.isShieldedByRule(dummy));
    }

    /**
     * 获取最近一次告警扫描的错误信息（供前端展示异常提示）
     */
    @Autowired
    private AlertGenerateService alertGenerateService;

    @GetMapping("/scan-errors")
    public ApiResponse<Map<String, Object>> getScanErrors() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("errors", alertGenerateService.getLastScanErrors());
        result.put("scanTime", alertGenerateService.getLastScanTime());
        result.put("hasErrors", !alertGenerateService.getLastScanErrors().isEmpty());
        return ApiResponse.success(result);
    }
}
