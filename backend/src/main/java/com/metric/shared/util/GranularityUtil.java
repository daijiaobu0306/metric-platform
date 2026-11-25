package com.metric.shared.util;

import java.time.LocalDateTime;

/**
 * 时段粒度工具类
 *
 * 5 档时间维度对应的查询策略：
 *
 * | 前端选择 | 查哪张表     | 时间窗  | 粒度    | 数据点 |
 * |---------|-------------|--------|---------|-------|
 * | 1h      | 原始表(5分桶) | 1小时   | 5分钟   | ~12   |
 * | 6h      | snapshot_1h | 6小时   | 1小时   | 6     |
 * | 1d      | snapshot_1h | 24小时  | 1小时   | 24    |
 * | 7d      | snapshot_1d | 7天    | 1天    | 7     |
 * | 30d     | snapshot_1d | 30天   | 1天    | 30    |
 */
public class GranularityUtil {

    /**
     * 根据 timeRange 推导应查询的快照表粒度
     * 返回 "raw" 表示查原始表（1h 场景需要 5 分钟分桶）
     */
    public static String getGranularity(String timeRange) {
        if (timeRange == null) return "1h";
        switch (timeRange) {
            case "1h":  return "raw";   // 1小时 → 查原始表，5分钟分桶
            case "6h":  return "1h";    // 6小时 → 查 1h 快照表
            case "1d":  return "1h";    // 24小时 → 查 1h 快照表
            case "7d":  return "1d";    // 7天 → 查 1d 快照表
            case "30d": return "1d";    // 30天 → 查 1d 快照表
            // 旧档位兼容
            case "24h": return "1h";
            case "15d": return "1d";
            default:    return "1h";
        }
    }

    /**
     * 获取时间范围的开始时间
     */
    public static LocalDateTime getStartTime(String timeRange, LocalDateTime endTime) {
        if (timeRange == null) return endTime.minusHours(24);
        switch (timeRange) {
            case "1h":  return endTime.minusHours(1);     // 最近 1 小时
            case "6h":  return endTime.minusHours(6);     // 最近 6 小时
            case "1d":  return endTime.minusHours(24);    // 最近 24 小时
            case "7d":  return endTime.minusDays(7);      // 最近 7 天
            case "30d": return endTime.minusDays(30);     // 最近 30 天
            // 旧档位兼容
            case "24h": return endTime.minusHours(24);
            case "15d": return endTime.minusDays(15);
            default:    return endTime.minusHours(24);
        }
    }

    /**
     * 获取时间范围的结束时间（默认 now）
     */
    public static LocalDateTime getEndTime() {
        return LocalDateTime.now();
    }

    /**
     * 计算记录时间所属的分桶起始时间
     * @param recordTime  记录时间
     * @param granularity 粒度：5m / 1h / 1d 等
     */
    public static LocalDateTime bucketTime(LocalDateTime recordTime, String granularity) {
        if (recordTime == null) return null;
        switch (granularity) {
            case "5m":
                int m5 = (recordTime.getMinute() / 5) * 5;
                return recordTime.withMinute(m5).withSecond(0).withNano(0);
            case "1h":
                return recordTime.withMinute(0).withSecond(0).withNano(0);
            case "1d":
                return recordTime.withHour(0).withMinute(0).withSecond(0).withNano(0);
            default:
                return recordTime.withMinute(0).withSecond(0).withNano(0);
        }
    }
}
