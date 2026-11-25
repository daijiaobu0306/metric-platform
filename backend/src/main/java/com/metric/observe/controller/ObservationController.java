package com.metric.observe.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.metric.entity.CategoryHealthHistory;
import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricHealthHistory;
import com.metric.metric.entity.WeightConfig;
import com.metric.metric.repository.CategoryHealthHistoryRepository;
import com.metric.metric.repository.MetricHealthHistoryRepository;
import com.metric.metric.repository.MetricCategoryRepository;
import com.metric.metric.repository.WeightConfigRepository;
import com.metric.observe.service.HealthSnapshotQueryService;
import com.metric.metric.service.MetricValueCalcService;
import com.metric.metric.service.WeightConfigService;
import com.metric.config.service.ConfigService;
import com.metric.shared.util.GranularityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/observation")
public class ObservationController {

    @Autowired
    private MetricValueCalcService metricValueCalcService;

    @Autowired
    private MetricHealthHistoryRepository metricHealthHistoryRepository;

    @Autowired
    private CategoryHealthHistoryRepository categoryHealthHistoryRepository;

    @Autowired
    private MetricCategoryRepository metricCategoryRepository;

    @Autowired
    private WeightConfigRepository weightConfigRepository;

    @Autowired
    private WeightConfigService weightConfigService;

    @Autowired
    private HealthSnapshotQueryService snapshotQueryService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private com.metric.metric.service.MetricObservationReadService metricObservations;

    /** 2026-11-17 P3：系统分/覆盖率统一由聚合服务算，页面不再前端自己平均 */
    @Autowired
    private com.metric.metric.service.HealthAggregationService healthAggregationService;

    @Autowired
    private com.metric.metric.repository.SystemInfoRepository systemInfoRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private com.metric.metric.repository.MetricHealthRuleRepository healthRuleRepository;

    /** system_config key: 健康度/权重默认 systemId（V20260914110001 新增，修复前硬编码 8L）*/
    private static final String CONFIG_KEY_DEFAULT_SYSTEM_ID = "metric.health.default-system-id";

    /**
     * 获取指标大类健康度趋势（从预聚合快照表读取）
     */
    @GetMapping("/category-health-trend")
    public ApiResponse<Map<String, Object>> getCategoryHealthTrend(
            @RequestParam String categoryName,
            @RequestParam(defaultValue = "1h") String timeRange,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        LocalDateTime[] window = resolveObservationWindow(startTime, endTime);

        String granularity = GranularityUtil.getGranularity(timeRange);

        List<Map<String, Object>> data = snapshotQueryService.getCategoryTrend(categoryName, timeRange, window[0], window[1]);

        Map<String, Object> result = new HashMap<>();
        result.put("categoryName", categoryName);
        result.put("timeRange", timeRange);
        result.put("granularity", granularity);
        result.put("data", data);

        return ApiResponse.success(result);
    }

    /**
     * 获取系统健康度（按该系统名下全部大类加权计算）。
     *
     * <p>2026-11-17 P3：改造前这里硬编码只算 4 个大类、权重缺省 0.25、且只算“默认系统”。
     * 现在改为按 systemId 取该系统名下全部大类，权重走统一 WeightResolver，
     * 只对“真正参与计算的大类”重新归一，并返回覆盖率与最差指标。
     */
    @GetMapping("/system-health-score")
    public ApiResponse<Map<String, Object>> getSystemHealthScore(
            @RequestParam(defaultValue = "1") Long systemId) {
        Map<String, Object> summary = healthAggregationService.liveSystemSummary(systemId);
        Map<String, Object> result = new HashMap<>();
        Object score = summary.get("score");
        result.put("healthScore", score == null ? 0 : score);
        Map<String, Object> categoryScores = new HashMap<>();
        Object categories = summary.get("categories");
        if (categories instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?> map) {
                    categoryScores.put(String.valueOf(map.get("name")), map.get("score"));
                }
            }
        }
        result.put("categoryScores", categoryScores);
        result.put("systemId", systemId);
        result.put("summary", summary);
        return ApiResponse.success(result);
    }

    /**
     * 系统健康度汇总（页面鼠标悬停「加权计算依据」直接读这个）：
     * 公式、每个大类的分数/权重/覆盖率/最差指标、整体覆盖率四分类。
     */
    @GetMapping("/system-health-summary")
    public ApiResponse<Map<String, Object>> getSystemHealthSummary(
            @RequestParam(defaultValue = "1") Long systemId) {
        return ApiResponse.success(healthAggregationService.liveSystemSummary(systemId));
    }

    /** 系统列表（页面系统切换用） */
    @GetMapping("/systems")
    public ApiResponse<List<Map<String, Object>>> getSystems() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var system : systemInfoRepository.findAll()) {
            if (system.getStatus() != null && system.getStatus() != 1) continue;
            Map<String, Object> row = new HashMap<>();
            row.put("systemId", system.getId());
            row.put("systemName", system.getSystemName());
            rows.add(row);
        }
        return ApiResponse.success(rows);
    }

    /**
     * 指标健康度趋势上的“规则断点”：规则版本发生变化的记录时间。
     * 趋势图在这些位置画竖线并标注「基线调整」，避免“昨天 90 今天 60”无法解释。
     */
    @GetMapping("/metric-rule-breakpoints")
    public ApiResponse<List<Map<String, Object>>> getMetricRuleBreakpoints(@RequestParam Long metricId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("""
                SELECT record_time, rule_version, mode
                FROM metric_health_history
                WHERE metric_id = ? AND rule_version IS NOT NULL
                ORDER BY record_time ASC
                """, metricId)) {
            rows.add(row);
        }
        List<Map<String, Object>> points = new ArrayList<>();
        Integer previous = null;
        for (Map<String, Object> row : rows) {
            Integer version = row.get("rule_version") == null ? null : ((Number) row.get("rule_version")).intValue();
            if (previous != null && version != null && !version.equals(previous)) {
                Map<String, Object> point = new HashMap<>();
                point.put("time", row.get("record_time"));
                point.put("ruleVersion", version);
                points.add(point);
            }
            previous = version;
        }
        return ApiResponse.success(points);
    }

    /**
     * 获取系统健康度历史趋势（从预聚合快照表读取，level_type=system）
     */
    @GetMapping("/system-health-trend")
    public ApiResponse<Map<String, Object>> getSystemHealthTrend(
            @RequestParam(defaultValue = "1h") String timeRange,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) Long systemId) {
        LocalDateTime[] window = resolveObservationWindow(startTime, endTime);

        String granularity = GranularityUtil.getGranularity(timeRange);

        // 页面可切换系统：优先按请求的 systemId 取趋势，未传时回退 system_config 默认系统。
        // 修复前这里固定用默认系统，切换系统后顶部区间均分和趋势图仍停在默认系统上。
        Long targetSystemId = systemId != null ? systemId : configService.getConfigLong(CONFIG_KEY_DEFAULT_SYSTEM_ID, 1L);
        List<Map<String, Object>> data = snapshotQueryService.getSystemTrend(targetSystemId, timeRange, window[0], window[1]);

        return ApiResponse.success(Map.of(
            "data", data,
            "granularity", granularity,
            "timeRange", timeRange
        ));
    }

    /**
     * 获取指标小类健康度趋势（从预聚合快照表读取，level_type=subcategory）
     */
    @GetMapping("/subcategory-health-trend")
    public ApiResponse<Map<String, Object>> getSubcategoryHealthTrend(
            @RequestParam String categoryName,
            @RequestParam String subCategoryName,
            @RequestParam(defaultValue = "1h") String timeRange,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        LocalDateTime[] window = resolveObservationWindow(startTime, endTime);

        String granularity = GranularityUtil.getGranularity(timeRange);

        List<Map<String, Object>> data = snapshotQueryService.getSubcategoryTrend(categoryName, subCategoryName, timeRange, window[0], window[1]);

        Map<String, Object> result = new HashMap<>();
        result.put("categoryName", categoryName);
        result.put("subCategoryName", subCategoryName);
        result.put("timeRange", timeRange);
        result.put("granularity", granularity);
        result.put("data", data);

        return ApiResponse.success(result);
    }

    /**
     * 获取具体指标的指标值趋势
     */
    @GetMapping("/metric-value-trend")
    public ApiResponse<Map<String, Object>> getMetricValueTrend(
            @RequestParam Long metricId,
            @RequestParam(defaultValue = "24h") String timeRange,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        LocalDateTime[] window = resolveObservationWindow(startTime, endTime);

        LocalDateTime queryEnd = window[1] != null ? window[1] : LocalDateTime.now();
        LocalDateTime queryStart = window[0] != null ? window[0] : GranularityUtil.getStartTime(timeRange, queryEnd);
        String granularity = GranularityUtil.getGranularity(timeRange);

        var result = new HashMap<String,Object>(metricObservations.trend(metricId,queryStart,queryEnd));
        result.put("metricId",metricId);
        result.put("timeRange",timeRange);
        result.put("granularity","source");
        return ApiResponse.success(result);
    }

    /**
     * 获取具体指标的健康度趋势（从预聚合快照表读取，level_type=metric）
     */
    @GetMapping("/metric-health-trend")
    public ApiResponse<Map<String, Object>> getMetricHealthTrend(
            @RequestParam Long metricId,
            @RequestParam(defaultValue = "1h") String timeRange,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        LocalDateTime[] window = resolveObservationWindow(startTime, endTime);

        String granularity = GranularityUtil.getGranularity(timeRange);

        List<Map<String, Object>> data = snapshotQueryService.getMetricHealthTrend(metricId, timeRange, window[0], window[1]);

        Map<String, Object> result = new HashMap<>();
        result.put("metricId", metricId);
        result.put("timeRange", timeRange);
        result.put("granularity", granularity);
        result.put("data", data);
        if (data.isEmpty()) result.put("notice", "当前范围暂无健康度记录（可能为不计分/基线学习中/缺数）");

        return ApiResponse.success(result);
    }

    /**
     * 获取指标小类的指标列表
     */
    @GetMapping("/subcategory-metrics")
    public ApiResponse<Map<String, Object>> getSubcategoryMetrics(
            @RequestParam String categoryName,
            @RequestParam String subCategoryName,
            @RequestParam(required = false, defaultValue = "7d") String timeRange,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        LocalDateTime[] window = resolveObservationWindow(startTime, endTime);

        LocalDateTime queryEnd = window[1] != null ? window[1] : LocalDateTime.now();
        LocalDateTime queryStart = window[0] != null ? window[0] : GranularityUtil.getStartTime(timeRange, queryEnd);

        var metrics=metricObservations.latest(metricObservations.scope(categoryName,subCategoryName),queryStart,queryEnd);
        decorateHealthRules(metrics);
        Map<String,Object> result=new HashMap<>();
        result.put("categoryName",categoryName);
        result.put("subCategoryName",subCategoryName);
        result.put("metrics",metrics);
        return ApiResponse.success(result);
    }

    /**
     * 获取指标健康度 5 分钟明细（下钻到最细粒度时使用）
     * 从明细表实时查，不走预聚合快照表
     */
    @GetMapping("/metric-health-detail")
    public ApiResponse<Map<String, Object>> getMetricHealthDetail(
            @RequestParam(required = false) Long metricId,
            @RequestParam String startTime,
            @RequestParam String endTime) {

        LocalDateTime start = LocalDateTime.parse(startTime.replace(" ", "T"));
        LocalDateTime end = LocalDateTime.parse(endTime.replace(" ", "T"));

        // 限制查询窗口最长 2 小时
        if (java.time.Duration.between(start, end).toHours() > 2) {
            end = start.plusHours(2);
        }

        final LocalDateTime queryStart = start;
        final LocalDateTime queryEnd = end;
        List<Map<String, Object>> data = new ArrayList<>();

        if (metricId != null) {
            data.addAll((List<Map<String,Object>>)metricObservations.trend(metricId,queryStart,queryEnd).get("data"));
        } else {
            data.addAll(metricObservations.latest(null,queryStart,queryEnd).stream()
                .filter(row -> row.get("dataTime")!=null && !Boolean.TRUE.equals(row.get("isStale"))).toList());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("data", data);
        result.put("startTime", startTime);
        result.put("endTime", endTime);
        result.put("count", data.size());

        return ApiResponse.success(result);
    }

    /**
     * 手动触发健康度计算（用于初始化/测试）
     */
    @PostMapping("/calc-health")
    public ApiResponse<Map<String, Object>> calcHealth(
            @RequestParam(defaultValue = "对内部人员") String categoryName,
            @RequestParam(defaultValue = "查询类") String subCategoryName) {

        try {
            metricValueCalcService.calcHealthScores(categoryName, subCategoryName);

            Map<String, Object> result = new HashMap<>();
            result.put("message", "健康度计算完成");
            result.put("categoryName", categoryName);
            result.put("subCategoryName", subCategoryName);

            return ApiResponse.success(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("error", e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 给指标行补「算分规则」信息（P4 规则徽标用）：
     * exempt=不计分；manual=人工基线；dynamic=动态基线；learning=基线学习中（还没出分）。
     * 一次装载规则表 + 最新新算法分数，避免逐行查库。
     */
    private void decorateHealthRules(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return;
        java.util.Map<Long, String> modes = new HashMap<>();
        for (var entry : healthAggregationService.latestScores().entrySet()) {
            modes.put(entry.getKey(), entry.getValue().mode());
        }
        java.util.Map<Long, com.metric.metric.entity.MetricHealthRule> rules = new HashMap<>();
        for (var rule : healthRuleRepository.findAll()) {
            rules.put(rule.getMetricId(), rule);
        }
        for (Map<String, Object> row : rows) {
            Object idValue = row.get("metricId");
            if (!(idValue instanceof Number number)) continue;
            long metricId = number.longValue();
            var rule = rules.get(metricId);
            boolean exempt = rule != null && rule.isExempt();
            row.put("healthExempt", exempt);
            row.put("healthMode", exempt ? "exempt" : modes.getOrDefault(metricId, "learning"));
            row.put("healthRuleVersion", rule == null ? null : rule.getVersion());
        }
    }

    /**
     * 根据时间范围计算开始时间
     */
    private LocalDateTime[] resolveObservationWindow(String startTime, String endTime) {
        if (startTime == null && endTime == null) return new LocalDateTime[] { null, null };
        if (startTime == null || endTime == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "开始和结束时间必须同时提供");
        }
        try {
            LocalDateTime start = LocalDateTime.parse(startTime.replace(" ", "T"));
            LocalDateTime end = LocalDateTime.parse(endTime.replace(" ", "T"));
            if (!start.isBefore(end)) throw new IllegalArgumentException("开始时间须早于结束时间");
            return new LocalDateTime[] { start, end };
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException exception) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "时间范围无效", exception);
        }
    }

    private LocalDateTime getStartTime(String timeRange, LocalDateTime endTime) {
        switch (timeRange) {
            case "24h":
                return endTime.minusHours(24);
            case "7d":
                return endTime.minusDays(7);
            case "15d":
                return endTime.minusDays(15);
            case "30d":
                return endTime.minusDays(30);
            default:
                return endTime.minusHours(24);
        }
    }
}