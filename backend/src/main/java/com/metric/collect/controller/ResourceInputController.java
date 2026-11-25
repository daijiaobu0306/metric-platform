package com.metric.collect.controller;

import com.metric.metric.service.ResourceInputMetricService;
import com.metric.metric.service.ResourceInputPeriodService;
import java.time.LocalDate;
import com.metric.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/collection/resource-input")
public class ResourceInputController {
    private final ResourceInputMetricService metrics;
    private final ResourceInputPeriodService periods;
    public ResourceInputController(ResourceInputMetricService metrics, ResourceInputPeriodService periods) {
        this.metrics = metrics; this.periods = periods;
    }
    @GetMapping("/catalog")
    public ApiResponse<?> catalog() { return ApiResponse.success(periods.catalog()); }
    @GetMapping("/records")
    public ApiResponse<?> records(@RequestParam long metricId,
            @RequestParam(required=false) LocalDate startDate, @RequestParam(required=false) LocalDate endDate,
            @RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="50") int size) {
        try { return ApiResponse.success(periods.records(metricId,startDate,endDate,keyword,page,size)); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage()); }
    }
    @GetMapping("/report")
    public ApiResponse<?> report(@RequestParam(required=false) LocalDate startDate,
            @RequestParam(required=false) LocalDate endDate, @RequestParam(required=false) String subCategory) {
        try { return ApiResponse.success(periods.report(startDate,endDate,subCategory)); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage()); }
    }
    @GetMapping
    public ApiResponse<?> overview(@RequestParam(required = false) String batchId) {
        try { return ApiResponse.success(metrics.overview(batchId)); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }
}
