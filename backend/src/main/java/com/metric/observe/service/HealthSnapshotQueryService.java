package com.metric.observe.service;

import com.metric.observe.entity.HealthSnapshotBase;
import com.metric.observe.repository.*;
import com.metric.shared.util.GranularityUtil;
import com.metric.metric.repository.MetricHealthHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康度快照统一查询入口
 *
 * 根据 timeRange 路由到对应的查询策略：
 * - 1h → 原始表 metric_health_history，5 分钟分桶
 * - 6h / 1d → health_snapshot_1h 表
 * - 7d / 30d → health_snapshot_1d 表
 */
@Service
public class HealthSnapshotQueryService {

    @Autowired private HealthSnapshot1hRepository snapshot1hRepo;
    @Autowired private HealthSnapshot1dRepository snapshot1dRepo;
    @Autowired private MetricHealthHistoryRepository healthHistoryRepo;

    private static final DateTimeFormatter BUCKET_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ============================================================
    // System 层趋势
    // ============================================================

    public List<Map<String, Object>> getSystemTrend(Long systemId, String timeRange) {
        return getSystemTrend(systemId, timeRange, null, null);
    }

    public List<Map<String, Object>> getSystemTrend(Long systemId, String timeRange, LocalDateTime windowStart, LocalDateTime windowEnd) {
        String granularity = GranularityUtil.getGranularity(timeRange);
        LocalDateTime endTime = windowEnd != null ? windowEnd : LocalDateTime.now();
        LocalDateTime startTime = windowStart != null ? windowStart : GranularityUtil.getStartTime(timeRange, endTime);

        // 系统级暂无原始表 5m 分桶，统一用 snapshot_1h 兜底
        List<? extends HealthSnapshotBase> snaps = switch (granularity) {
            case "raw"  -> snapshot1hRepo.findSystemTrend(systemId, startTime, endTime);
            case "1h"  -> snapshot1hRepo.findSystemTrend(systemId, startTime, endTime);
            case "1d"  -> snapshot1dRepo.findSystemTrend(systemId, startTime, endTime);
            default    -> snapshot1hRepo.findSystemTrend(systemId, startTime, endTime);
        };
        return toTrendData(snaps);
    }

    // ============================================================
    // Category 层趋势
    // ============================================================

    public List<Map<String, Object>> getCategoryTrend(String categoryName, String timeRange) {
        return getCategoryTrend(categoryName, timeRange, null, null);
    }

    public List<Map<String, Object>> getCategoryTrend(String categoryName, String timeRange, LocalDateTime windowStart, LocalDateTime windowEnd) {
        String granularity = GranularityUtil.getGranularity(timeRange);
        LocalDateTime endTime = windowEnd != null ? windowEnd : LocalDateTime.now();
        LocalDateTime startTime = windowStart != null ? windowStart : GranularityUtil.getStartTime(timeRange, endTime);

        if ("raw".equals(granularity)) {
            // 1h 场景：从原始表按 5 分钟分桶
            return fromRawCategory5Min(categoryName, startTime, endTime);
        }

        List<? extends HealthSnapshotBase> snaps = switch (granularity) {
            case "1h"  -> snapshot1hRepo.findCategoryTrend(categoryName, startTime, endTime);
            case "1d"  -> snapshot1dRepo.findCategoryTrend(categoryName, startTime, endTime);
            default    -> snapshot1hRepo.findCategoryTrend(categoryName, startTime, endTime);
        };
        return toTrendData(snaps);
    }

    // ============================================================
    // Subcategory 层趋势
    // ============================================================

    public List<Map<String, Object>> getSubcategoryTrend(String categoryName, String subCategoryName, String timeRange) {
        return getSubcategoryTrend(categoryName, subCategoryName, timeRange, null, null);
    }

    public List<Map<String, Object>> getSubcategoryTrend(String categoryName, String subCategoryName, String timeRange, LocalDateTime windowStart, LocalDateTime windowEnd) {
        String granularity = GranularityUtil.getGranularity(timeRange);
        LocalDateTime endTime = windowEnd != null ? windowEnd : LocalDateTime.now();
        LocalDateTime startTime = windowStart != null ? windowStart : GranularityUtil.getStartTime(timeRange, endTime);

        // 子类在原始表没有 category_name 级别的 5m 分桶，用 snapshot_1h 兜底
        List<? extends HealthSnapshotBase> snaps = switch (granularity) {
            case "raw"  -> snapshot1hRepo.findSubcategoryTrend(categoryName, subCategoryName, startTime, endTime);
            case "1h"  -> snapshot1hRepo.findSubcategoryTrend(categoryName, subCategoryName, startTime, endTime);
            case "1d"  -> snapshot1dRepo.findSubcategoryTrend(categoryName, subCategoryName, startTime, endTime);
            default    -> snapshot1hRepo.findSubcategoryTrend(categoryName, subCategoryName, startTime, endTime);
        };
        return toTrendData(snaps);
    }

    // ============================================================
    // Metric 层趋势
    // ============================================================

    public List<Map<String, Object>> getMetricHealthTrend(Long metricId, String timeRange) {
        return getMetricHealthTrend(metricId, timeRange, null, null);
    }

    public List<Map<String, Object>> getMetricHealthTrend(Long metricId, String timeRange, LocalDateTime windowStart, LocalDateTime windowEnd) {
        String granularity = GranularityUtil.getGranularity(timeRange);
        LocalDateTime endTime = windowEnd != null ? windowEnd : LocalDateTime.now();
        LocalDateTime startTime = windowStart != null ? windowStart : GranularityUtil.getStartTime(timeRange, endTime);

        List<? extends HealthSnapshotBase> snaps = switch (granularity) {
            case "raw"  -> snapshot1hRepo.findMetricTrend(metricId, startTime, endTime);
            case "1h"  -> snapshot1hRepo.findMetricTrend(metricId, startTime, endTime);
            case "1d"  -> snapshot1dRepo.findMetricTrend(metricId, startTime, endTime);
            default    -> snapshot1hRepo.findMetricTrend(metricId, startTime, endTime);
        };
        List<Map<String, Object>> data = toTrendData(snaps);
        if (!data.isEmpty()) return data;
        // 采集类（sourceKind=collection）指标过去被直接屏蔽趋势，专屏卡片点开永远是
        // "当前范围暂无健康度趋势"；且这类指标的快照可能还没回填。健康度分数本身来自
        // metric_health_history，与指标值来源无关，所以快照为空时直接用明细兜底。
        return fromMetricHealthHistory(metricId, startTime, endTime);
    }

    /**
     * 快照缺失时的兜底：直接读指标健康度明细。
     *
     * <p>采集类指标一天可能只有一条算分记录，点会稀疏（1 天 1 个），属于正常现象；
     * 为免前端图表点数过多，超过 500 点时按等间距抽样。
     */
    private List<Map<String, Object>> fromMetricHealthHistory(Long metricId, LocalDateTime startTime, LocalDateTime endTime) {
        List<com.metric.metric.entity.MetricHealthHistory> rows =
                healthHistoryRepo.findMetricHealthTrend(metricId, startTime, endTime);
        List<Map<String, Object>> data = new ArrayList<>();
        if (rows.isEmpty()) return data;
        int step = Math.max(1, (int) Math.ceil(rows.size() / 500.0));
        for (int i = 0; i < rows.size(); i += step) {
            var row = rows.get(i);
            Map<String, Object> point = new HashMap<>();
            point.put("recordTime", row.getRecordTime());
            point.put("healthScore", row.getHealthScore());
            point.put("status", row.getStatus());
            data.add(point);
        }
        return data;
    }

    // ============================================================
    // 原始表 → 5 分钟分桶（1h 场景专用）
    // ============================================================

    private List<Map<String, Object>> fromRawCategory5Min(String categoryName, LocalDateTime startTime, LocalDateTime endTime) {
        List<Object[]> rows = healthHistoryRepo.aggregateCategoryBy5Min(categoryName, startTime, endTime);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> point = new HashMap<>();
            String bucket = row[0].toString();
            point.put("recordTime", LocalDateTime.parse(bucket, BUCKET_FMT));
            point.put("healthScore", ((BigDecimal) row[1]).intValue());
            data.add(point);
        }
        return data;
    }

    // ============================================================
    // 格式转换：快照实体 → 前端期望的 Map 列表
    // ============================================================

    private List<Map<String, Object>> toTrendData(List<? extends HealthSnapshotBase> snaps) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (HealthSnapshotBase s : snaps) {
            Map<String, Object> point = new HashMap<>();
            point.put("recordTime", s.getSnapshotTime());
            point.put("healthScore", s.getHealthScore());
            point.put("status", s.getStatus());
            data.add(point);
        }
        return data;
    }
}
