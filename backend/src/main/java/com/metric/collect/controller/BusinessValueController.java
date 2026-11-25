package com.metric.collect.controller;
import com.metric.metric.service.BusinessValueMetricService;
import com.metric.metric.service.BusinessValueStatisticsService;
import java.time.LocalDate;
import com.metric.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/collection/business-value")
public class BusinessValueController {
    private final BusinessValueMetricService metrics;
    private final BusinessValueStatisticsService statistics;
    public BusinessValueController(BusinessValueMetricService metrics, BusinessValueStatisticsService statistics) {
        this.metrics=metrics;
        this.statistics=statistics;
    }
    @GetMapping("/statistics")
    public ApiResponse<?> statistics(@RequestParam(required=false) LocalDate startDate,
                                     @RequestParam(required=false) LocalDate endDate) {
        try { return ApiResponse.success(statistics.statistics(startDate, endDate)); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }
    @GetMapping("/report")
    public ApiResponse<?> report(@RequestParam(required=false) LocalDate startDate,
                                 @RequestParam(required=false) LocalDate endDate,
                                 @RequestParam(required=false) String subCategory) {
        try { return ApiResponse.success(statistics.report(startDate, endDate, subCategory)); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }
    @GetMapping public ApiResponse<?> overview(@RequestParam(required=false) String batchId) {
        try { return ApiResponse.success(metrics.overview(batchId)); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage()); }
    }
}
