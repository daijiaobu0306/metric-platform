package com.metric.observe.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 健康度快照聚合定时任务
 *
 * 5 个 cron 任务，按粒度分桶聚合明细数据写入预计算快照表。
 * 应用启动时执行一次回填，把最近 30 天的明细数据补全到 1h/1d/30d 三张表。
 */
@Component
public class HealthSnapshotAggregationScheduler {

    @Autowired
    private HealthSnapshotAggregationService aggregationService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 每小时 5 分执行：聚合上一小时明细 → health_snapshot_1h
     */
    @Scheduled(cron = "5 * * * * ?")
    public void aggregateHourly() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime targetHour = HealthSnapshotAggregationService.alignToHour(now).minusHours(1);
        long start = System.currentTimeMillis();
        try {
            int count = aggregationService.aggregateHourly(targetHour);
            System.out.println("[健康度1h快照] " + targetHour + " 写入 " + count + " 条，耗时 " + (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            System.err.println("[健康度1h快照] 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 每 6 小时的 10 分执行：聚合 1h 快照 → health_snapshot_6h
     */
    @Scheduled(cron = "10 */6 * * * ?")
    public void aggregateSixHourly() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime target = HealthSnapshotAggregationService.alignToSixHour(now).minusHours(6);
        long start = System.currentTimeMillis();
        try {
            int count = aggregationService.aggregateSixHourly(target);
            System.out.println("[健康度6h快照] " + target + " 写入 " + count + " 条，耗时 " + (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            System.err.println("[健康度6h快照] 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 每日凌晨 1 点执行：聚合昨日 1h 快照 → health_snapshot_1d
     */
    @Scheduled(cron = "0 1 * * * ?")
    public void aggregateDaily() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime yesterday = HealthSnapshotAggregationService.alignToDay(now).minusDays(1);
        long start = System.currentTimeMillis();
        try {
            int count = aggregationService.aggregateDaily(yesterday);
            System.out.println("[健康度1d快照] " + yesterday + " 写入 " + count + " 条，耗时 " + (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            System.err.println("[健康度1d快照] 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 每周一凌晨 2 点执行：聚合上周 1d 快照 → health_snapshot_7d
     */
    @Scheduled(cron = "0 0 2 ? * MON")
    public void aggregateWeekly() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastWeek = HealthSnapshotAggregationService.alignToWeek(now).minusWeeks(1);
        long start = System.currentTimeMillis();
        try {
            int count = aggregationService.aggregateWeekly(lastWeek);
            System.out.println("[健康度7d快照] " + lastWeek + " 写入 " + count + " 条，耗时 " + (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            System.err.println("[健康度7d快照] 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 每月 1 号凌晨 3 点执行：聚合上月 1d 快照 → health_snapshot_30d
     */
    @Scheduled(cron = "0 3 1 * * ?")
    public void aggregateMonthly() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastMonth = HealthSnapshotAggregationService.alignToMonth(now).minusMonths(1);
        long start = System.currentTimeMillis();
        try {
            int count = aggregationService.aggregateMonthly(lastMonth);
            System.out.println("[健康度30d快照] " + lastMonth + " 写入 " + count + " 条，耗时 " + (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            System.err.println("[健康度30d快照] 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 应用启动时执行一次回填：把最近 30 天的明细数据补全到 1h/1d/30d 表
     * 用 native SQL 一次聚合（高效，避免 720 次 Java 循环）
     * 延迟 60 秒启动，避免和 MetricObservationScheduler 的 5 分钟聚合抢资源
     */
    @Scheduled(initialDelay = 60000, fixedDelay = Long.MAX_VALUE)
    public void backfillOnStartup() {
        System.out.println("[健康度快照回填] 应用启动，开始回填最近 30 天数据..." + LocalDateTime.now().format(FORMATTER));
        long start = System.currentTimeMillis();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime thirtyDaysAgo = HealthSnapshotAggregationService.alignToDay(now.minusDays(30));

        try {
            // 第一步：回填 1h 快照（用 SQL 一次聚合明细表）
            int hourlyCount = aggregationService.backfillHourlyFromDetail(thirtyDaysAgo, now);
            System.out.println("[健康度1h快照] 回填完成，写入 " + hourlyCount + " 条");

            // 第二步：回填 1d 快照（用 SQL 从 1h 表聚合）
            int dailyCount = aggregationService.backfillDailyFromHourly(thirtyDaysAgo, now);
            System.out.println("[健康度1d快照] 回填完成，写入 " + dailyCount + " 条");

            // 第三步：回填 30d 快照（用 SQL 从 1d 表聚合最近 30 天的月份）
            int monthlyCount = aggregationService.backfillMonthlyFromDaily(thirtyDaysAgo, now);
            System.out.println("[健康度30d快照] 回填完成，写入 " + monthlyCount + " 条");
        } catch (Exception e) {
            System.err.println("[健康度快照回填] 失败: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[健康度快照回填] 总耗时 " + (System.currentTimeMillis() - start) + "ms");
    }
}
