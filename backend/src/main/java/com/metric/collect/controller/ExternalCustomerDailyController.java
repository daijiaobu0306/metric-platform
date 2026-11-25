package com.metric.collect.controller;

import com.metric.metric.service.CollectedMetricService;
import com.metric.shared.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 对外部客户指标按日明细（只读）。
 *
 * <p>2026-11-17 P0：原该接口挂在已下线的 mockdata 模块（{@code ExternalCustomerSimulationController}），
 * 但它本身读的是正式采集值（{@code CollectedMetricService.dailyOverview}），不是模拟数据。
 * mock 模块整体删除后把这份正式读能力迁到这里，路径保持 {@code /api/data/external-customer} 不变，
 * 前端「数据汇聚 → 对外部客户」页面无需改动。</p>
 *
 * <p>演示/模拟相关接口（demo-state、generate、scan）随 mock 模块一并下线，不再提供。</p>
 */
@RestController
@RequestMapping("/api/data/external-customer")
public class ExternalCustomerDailyController {

    /** 对外部客户当前只有这两个小类有按日明细口径，超出范围属调用错误。 */
    private static final List<String> SUB_CATEGORIES = List.of("缴费开机", "流量提醒");

    private final CollectedMetricService collectedMetrics;

    public ExternalCustomerDailyController(CollectedMetricService collectedMetrics) {
        this.collectedMetrics = collectedMetrics;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam String subCategory,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String dataTime) {
        if (!SUB_CATEGORIES.contains(subCategory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的指标小类");
        }
        try {
            return ApiResponse.success(collectedMetrics.dailyOverview("external_customer", subCategory, date, dataTime));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期或时间点格式无效");
        }
    }
}
