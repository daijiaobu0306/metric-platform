package com.metric.gongdan.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.gongdan.entity.Gongdan;
import com.metric.gongdan.service.GongdanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gongdan")
public class GongdanController {

    @Autowired
    private GongdanService gongdanService;

    @Autowired
    private com.metric.gongdan.service.GongdanIntegrationService gongdanIntegrationService;

    @GetMapping
    public ApiResponse<Page<Gongdan>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String gongdanId,
            @RequestParam(required = false) String gongdanTitle,
            @RequestParam(required = false) String gongdanContent,
            @RequestParam(required = false) String gongdanType,
            @RequestParam(required = false) String currentStep,
            @RequestParam(required = false) String currentHandler,
            @RequestParam(required = false) String gongdanTimeStart,
            @RequestParam(required = false) String gongdanTimeEnd,
            @RequestParam(required = false) Integer closedStatus,
            @RequestParam(required = false) Long systemId,
            @RequestParam(required = false) String externalId,
            @RequestParam(required = false) String alarmObject,
            @RequestParam(required = false) Integer alarmLevel,
            @RequestParam(required = false) Integer synced,
            @RequestParam(defaultValue = "gongdanTime") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        Sort sort = sortOrder.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        // 使用多维搜索
        return ApiResponse.success(gongdanService.search(
                gongdanId, gongdanTitle, gongdanContent, gongdanType, currentStep, currentHandler,
                gongdanTimeStart, gongdanTimeEnd, closedStatus,
                externalId, alarmObject, alarmLevel, synced, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<Gongdan> findById(@PathVariable Long id) {
        return gongdanService.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("Gongdan not found"));
    }

    @PostMapping
    public ApiResponse<Gongdan> create(@RequestBody Gongdan gongdan) {
        Gongdan saved = gongdanService.save(gongdan);
        // 手动创建的工单立即推送 BOMC 创建工单（接口规则写死在 BomcGongdanClient，无需插件配置）。
        // 推送失败不影响本地建单，失败原因写入 external_sync_msg，可在列表上"重新推送"。
        gongdanIntegrationService.pushToBomc(saved.getId());
        return ApiResponse.success(gongdanService.findById(saved.getId()).orElse(saved));
    }

    @PutMapping("/{id}")
    public ApiResponse<Gongdan> update(@PathVariable Long id, @RequestBody Gongdan gongdan) {
        return gongdanService.findById(id)
                .map(existing -> {
                    gongdan.setId(id);
                    return ApiResponse.success(gongdanService.save(gongdan));
                })
                .orElse(ApiResponse.error("Gongdan not found"));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        gongdanService.deleteById(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/close")
    public ApiResponse<Gongdan> close(@PathVariable Long id) {
        Gongdan gongdan = gongdanService.closeGongdan(id);
        return gongdan != null ? ApiResponse.success(gongdan) : ApiResponse.error("Gongdan not found");
    }

    @GetMapping("/statistics")
    public ApiResponse<Map<String, Object>> getStatistics() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime todayStart = now.with(java.time.LocalTime.MIN);
        java.time.LocalDateTime weekStart = now.minusDays(7).with(java.time.LocalTime.MIN);

        return ApiResponse.success(java.util.Map.of(
                "totalGongdanCount", gongdanService.countByGongdanTimeAfter(now.minusDays(30)),
                "todayCount", gongdanService.countByGongdanTimeAfter(todayStart),
                "weekCount", gongdanService.countByGongdanTimeAfter(weekStart),
                "openCount", gongdanService.countByClosedStatus(0),
                "closedCount", gongdanService.countByClosedStatus(1),
                // 未成功推送 BOMC 的工单数（含从未推送和推送失败）
                "pendingSyncCount", gongdanIntegrationService.countPending()
        ));
    }

    @GetMapping("/trend")
    public ApiResponse<List<Map<String, Object>>> getTrend(
            @RequestParam(defaultValue = "today") String dimension,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ApiResponse.success(gongdanService.getTrend(dimension, type, startDate, endDate));
    }
}
