package com.metric.collect.controller;
import com.metric.metric.service.BusinessValueMetricService;
import com.metric.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** 对内部人员：生产 CSV/Python 采集的指标值（collection_file/collection_row），供数据汇聚页展示。 */
@RestController
@RequestMapping("/api/collection/internal-staff")
public class InternalStaffController {
    private final BusinessValueMetricService values;
    public InternalStaffController(BusinessValueMetricService values) { this.values=values; }
    @GetMapping public ApiResponse<?> overview(@RequestParam(required=false) String batchId) {
        try { return ApiResponse.success(values.overview("internal_staff",batchId)); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage()); }
    }
}
