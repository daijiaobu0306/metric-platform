package com.metric.alarm.controller;

import com.metric.alarm.entity.AlarmAnalysisConfig;
import com.metric.alarm.service.AlarmAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarm-analysis")
public class AlarmAnalysisController {

    @Autowired
    private AlarmAnalysisService alarmAnalysisService;

    /**
     * 获取分析报告列表
     */
    @GetMapping("/reports")
    public ResponseEntity<Map<String, Object>> getReports(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", alarmAnalysisService.getReportsPaged(startDate, endDate, page, size)));
    }

    /**
     * 批量删除报告
     */
    @DeleteMapping("/reports")
    public ResponseEntity<Map<String, Object>> batchDeleteReports(@RequestBody List<Long> ids) {
        int count = alarmAnalysisService.batchDeleteReports(ids);
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", count));
    }

    /**
     * 获取指定日期的报告
     */
    @GetMapping("/reports/{date}")
    public ResponseEntity<Map<String, Object>> getReportsByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", alarmAnalysisService.getReportsByDate(date)));
    }

    /**
     * 获取分析配置
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", alarmAnalysisService.getConfig()));
    }

    /**
     * 更新分析配置
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody AlarmAnalysisConfig config) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "success", "data", alarmAnalysisService.updateConfig(config)));
    }

    /**
     * 手动触发分析
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerAnalysis(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportDate,
            @RequestParam(required = false) Integer lookbackDays) {
        alarmAnalysisService.triggerAnalysis(reportDate, lookbackDays);
        return ResponseEntity.ok(Map.of("code", 200, "message", "success"));
    }
}
