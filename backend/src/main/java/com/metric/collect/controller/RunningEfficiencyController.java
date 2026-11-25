package com.metric.collect.controller;
import com.metric.metric.service.BusinessValueMetricService;
import com.metric.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/collection/running-efficiency")
public class RunningEfficiencyController {
    private final BusinessValueMetricService values;
    public RunningEfficiencyController(BusinessValueMetricService values) { this.values=values; }
    @GetMapping public ApiResponse<?> overview(@RequestParam(required=false) String batchId) {
        try { return ApiResponse.success(values.overview("running_efficiency",batchId)); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage()); }
    }
}
