package com.metric.metric.controller;

import com.metric.metric.service.HealthRuleService;
import com.metric.metric.service.HealthScoringService;
import com.metric.shared.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 健康度规则配置接口（指标管理页「健康度规则配置」抽屉使用）。
 *
 * <p>接口按“一次取整棵树”设计，避免页面拼几十个请求：
 * <ul>
 *   <li>GET  /tree        系统→大类→小类→指标 + 规则 + 覆盖率</li>
 *   <li>GET  /suggest     近 90 天统计（中位数/MAD/样本数），用于建议基线</li>
 *   <li>POST /preview     试算：给定参数返回分数曲线与当前值得分</li>
 *   <li>PUT  /batch       增量保存白名单/基线（带原因 + 乐观锁），保存后立即重算</li>
 *   <li>GET  /history     变更审计</li>
 *   <li>GET  /compare     新旧算法对比（双跑期）</li>
 *   <li>POST /recalculate 手动重算</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/metric/health-rules")
public class HealthRuleController {

    @Autowired private HealthRuleService healthRuleService;
    @Autowired private HealthScoringService healthScoringService;

    @GetMapping("/tree")
    public ApiResponse<Map<String, Object>> tree(@RequestParam(required = false) Long systemId) {
        return ApiResponse.success(healthRuleService.tree(systemId));
    }

    @GetMapping("/suggest")
    public ApiResponse<Map<String, Object>> suggest(@RequestParam Long metricId) {
        return ApiResponse.success(healthRuleService.suggest(metricId));
    }

    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestBody Map<String, Object> payload) {
        return ApiResponse.success(healthRuleService.preview(payload));
    }

    @PutMapping("/batch")
    public ApiResponse<Map<String, Object>> batch(@RequestBody Map<String, Object> payload) {
        return ApiResponse.success(healthRuleService.batchSave(payload));
    }

    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> history(@RequestParam(required = false) Long metricId,
                                                          @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(healthRuleService.history(metricId, Math.max(1, Math.min(100, limit))));
    }

    @GetMapping("/compare")
    public ApiResponse<Map<String, Object>> compare() {
        return ApiResponse.success(healthRuleService.compare());
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.success(healthRuleService.config());
    }

    @PostMapping("/recalculate")
    public ApiResponse<Map<String, Object>> recalculate() {
        HealthScoringService.RunSummary summary = healthScoringService.runAll();
        return ApiResponse.success(Map.of(
                "scored", summary.scored(),
                "learning", summary.learning(),
                "noData", summary.noData(),
                "exempt", summary.exempt(),
                "failed", summary.failed(),
                "recordTime", summary.recordTime()));
    }

    /** 指标级算分明细：解释弹窗直接读 score_trace，前端不再复刻公式 */
    @GetMapping("/metric-trace")
    public ApiResponse<Map<String, Object>> metricTrace(@RequestParam Long metricId) {
        return ApiResponse.success(healthScoringService.latestTrace(metricId));
    }
}
