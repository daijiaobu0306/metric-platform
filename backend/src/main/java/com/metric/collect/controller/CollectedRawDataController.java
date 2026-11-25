package com.metric.collect.controller;

import java.time.format.DateTimeParseException;
import com.metric.collect.service.CollectedRawDataService;
import com.metric.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据汇聚页原始数据：回放采集到的原始文件/原始行，指标计算只在运营报表展示。
 */
@RestController
@RequestMapping("/api/collection/raw-data")
public class CollectedRawDataController {
    private final CollectedRawDataService service;

    public CollectedRawDataController(CollectedRawDataService service) {
        this.service = service;
    }

    @GetMapping("/catalog")
    public ApiResponse<?> catalog(@RequestParam String category) {
        return ApiResponse.success(service.catalog(category));
    }

    @GetMapping("/records")
    public ApiResponse<?> records(@RequestParam String category, @RequestParam long metricId,
            @RequestParam(required=false) String date, @RequestParam(required=false) String dataTime,
            @RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="50") int size) {
        try {
            return ApiResponse.success(service.records(category,metricId,date,dataTime,keyword,page,size));
        } catch (DateTimeParseException e) {
            return ApiResponse.error(400, "数据日期格式无效，请使用 YYYY-MM-DD");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /**
     * 某指标某天有哪些采集时间点。各大类采集频率不同（外部客户小时级、运行效率 5 分钟级、
     * 合规/资源投入按日），前端用这份列表决定是否显示"时间点"下拉，避免硬编码粒度。
     */
    @GetMapping("/time-points")
    public ApiResponse<?> timePoints(@RequestParam String category, @RequestParam long metricId,
            @RequestParam(required=false) String date) {
        try {
            return ApiResponse.success(service.timePointsFor(category, metricId, date));
        } catch (DateTimeParseException e) {
            return ApiResponse.error(400, "数据日期格式无效，请使用 YYYY-MM-DD");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/preview")
    public ApiResponse<?> preview(@RequestParam String category,
                                  @RequestParam(required = false) String source,
                                  @RequestParam(required = false) String batchId,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        try {
            return ApiResponse.success(service.preview(category, source, batchId, page, size));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
