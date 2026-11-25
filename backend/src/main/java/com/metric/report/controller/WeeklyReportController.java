package com.metric.report.controller;

import com.metric.report.service.WeeklyReportService;
import com.metric.shared.dto.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/report/weekly")
public class WeeklyReportController {
    private final WeeklyReportService reports;
    public WeeklyReportController(WeeklyReportService reports) { this.reports=reports; }

    @GetMapping
    public ApiResponse<Map<String,Object>> preview(
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required=false) Long systemId) {
        return ApiResponse.success(reports.preview(startDate,endDate,systemId));
    }

    @GetMapping("/observations")
    public ApiResponse<Map<String,Object>> observations(
            @RequestParam Long metricId,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(reports.observations(metricId,startDate,endDate,page,size));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> invalid(IllegalArgumentException error) { return ApiResponse.error(400,error.getMessage()); }
}
