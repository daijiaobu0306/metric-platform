package com.metric.gongdan.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.gongdan.dto.BomcRepushResult;
import com.metric.gongdan.dto.BomcTaskResult;
import com.metric.gongdan.dto.BomcTaskTrace;
import com.metric.gongdan.service.GongdanIntegrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工单 ↔ 省 BOMC 对接接口。
 *
 * <p>接口规则已写死在 {@code BomcGongdanClient} 中，本控制器不再接收 pluginId，
 * 调用方无需先在"插件配置"里配置任何内容。
 */
@RestController
@RequestMapping("/api/gongdan-integration")
public class GongdanIntegrationController {

    @Autowired
    private GongdanIntegrationService gongdanIntegrationService;

    /** 当前生效的 BOMC 对接配置（地址、操作人），用于界面展示和联调排查 */
    @GetMapping("/bomc-config")
    public ApiResponse<Map<String, String>> getBomcConfig() {
        return ApiResponse.success(gongdanIntegrationService.getBomcConfig());
    }

    /**
     * 修改 BOMC 对接配置（工单配置页双击数值修改后保存）。
     * 值留空表示删除该覆盖项、恢复平台默认值；返回修改后的最新配置。
     */
    @PutMapping("/bomc-config")
    public ApiResponse<Map<String, String>> updateBomcConfig(@RequestBody Map<String, String> changes) {
        try {
            gongdanIntegrationService.updateBomcConfig(changes);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
        return ApiResponse.success(gongdanIntegrationService.getBomcConfig());
    }

    /**
     * 推送工单到 BOMC（createTask）。
     *
     * @param force true=强制重新推送（已推送过的工单会再建一条）
     */
    @PostMapping("/push/{gongdanId}")
    public ApiResponse<BomcTaskResult> pushToBomc(@PathVariable Long gongdanId,
                                                  @RequestParam(defaultValue = "false") boolean force) {
        BomcTaskResult result = gongdanIntegrationService.pushToBomc(gongdanId, force);
        return result.isSuccess() ? ApiResponse.success(result) : ApiResponse.error(result.getMessage());
    }

    /** 查询工单在 BOMC 的处理轨迹（queryTask） */
    @GetMapping("/trace/{gongdanId}")
    public ApiResponse<BomcTaskTrace> queryTrace(@PathVariable Long gongdanId) {
        BomcTaskTrace trace = gongdanIntegrationService.queryTaskTrace(gongdanId);
        return trace.isSuccess() ? ApiResponse.success(trace) : ApiResponse.error(trace.getMessage());
    }

    /**
     * 归档工单（archiveTask）。
     *
     * <p>body.reason → 接口 opin 字段（必填）。工单未回复完时 BOMC 业务拒绝，
     * 报错信息原样返回（如「BOMC工单还未处理完成,请等待回复后再启动归档.」）。
     */
    @PostMapping("/archive/{gongdanId}")
    public ApiResponse<BomcTaskResult> archive(@PathVariable Long gongdanId,
                                               @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        BomcTaskResult result = gongdanIntegrationService.archiveGongdan(gongdanId, reason);
        return result.isSuccess() ? ApiResponse.success(result) : ApiResponse.error(result.getMessage());
    }

    /**
     * 撤销工单（quashTask）。
     *
     * <p>body.reason → 接口 opin 字段（可空，默认「工单撤销」）。
     * 成功后本地工单仅打标（已撤销）并关单，不删除数据。
     */
    @PostMapping("/quash/{gongdanId}")
    public ApiResponse<BomcTaskResult> quash(@PathVariable Long gongdanId,
                                             @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        BomcTaskResult result = gongdanIntegrationService.quashGongdan(gongdanId, reason);
        return result.isSuccess() ? ApiResponse.success(result) : ApiResponse.error(result.getMessage());
    }

    /**
     * 重新推送 BOMC（createTask）：在 BOMC 新建工单，并在本地新建一条工单承载新推送。
     * 原工单保持不动、不自动撤销（是否撤回原单由操作人决定）。
     */
    @PostMapping("/repush/{gongdanId}")
    public ApiResponse<BomcRepushResult> repush(@PathVariable Long gongdanId) {
        BomcRepushResult result = gongdanIntegrationService.repushToBomc(gongdanId);
        return result.isSuccess() ? ApiResponse.success(result) : ApiResponse.error(result.getMessage());
    }

    /**
     * 批量刷新工单的 BOMC 状态（列表页「刷新状态」按钮）。
     *
     * <p>body: {@code {"ids": [1,2,3]}}——只传当前页工单 id，后端逐个查 BOMC 轨迹并回填节点/处理人；
     * 未推送与已撤销/已归档的工单自动跳过。id 按宽松 Number 接收，避免 JSON 数字类型转换异常。
     */
    @PostMapping("/trace-refresh")
    public ApiResponse<Map<String, Object>> refreshTraces(@RequestBody(required = false) Map<String, List<Object>> body) {
        List<Long> ids = new ArrayList<>();
        if (body != null && body.get("ids") != null) {
            for (Object raw : body.get("ids")) {
                if (raw instanceof Number) {
                    ids.add(((Number) raw).longValue());
                }
            }
        }
        return ApiResponse.success(gongdanIntegrationService.refreshTraces(ids));
    }

    /** 补推所有未成功推送 BOMC 的工单，返回本次成功条数 */
    @PostMapping("/push-pending")
    public ApiResponse<Integer> pushPending() {
        return ApiResponse.success(gongdanIntegrationService.pushPending(GongdanIntegrationService.PUSH_PENDING_LIMIT));
    }
}
