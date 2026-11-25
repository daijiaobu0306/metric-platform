package com.metric.metric.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;
import com.metric.collect.service.NotificationEventService;

/**
 * 指标监控定时任务
 * 定时扫描指标数据，更新实时状态，检查阈值，生成告警/工单
 */
@Component
public class MetricMonitorScheduler {

    @Autowired
    private MetricMonitorService metricMonitorService;

    // V46: 全局通知事件服务（扫描完成后按 sub_category 聚合弹 Toast）
    @Autowired
    private NotificationEventService notificationEventService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 每1分钟执行一次指标监控扫描
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 * * * * ?")
    public void scanAndMonitor() {
        System.out.println("=== 指标监控扫描开始 " + LocalDateTime.now().format(FORMATTER) + " ===");
        try {
            int count = metricMonitorService.scanAndUpdateMetrics();
            System.out.println("=== 指标监控扫描完成，处理 " + count + " 个指标 " + LocalDateTime.now().format(FORMATTER) + " ===");

            // V46: 每次扫描聚合为一条 Toast（避免 6 条堆叠刷屏）
            // 格式："「系统稽核」98 条 · 「系统性能」10 条 · ..."
            Map<String, Integer> stats = metricMonitorService.getLastScanSubCategoryCount();
            if (!stats.isEmpty()) {
                String content = stats.entrySet().stream()
                        .map(e -> "「" + e.getKey() + "」" + e.getValue() + " 条")
                        .collect(Collectors.joining(" · "));
                int total = stats.values().stream().mapToInt(Integer::intValue).sum();
                notificationEventService.record(
                        NotificationEventService.TYPE_DATA_COLLECTION,
                        "数据采集",
                        content,
                        total);
            }
        } catch (Exception e) {
            System.err.println("指标监控扫描出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 应用启动时执行一次初始化扫描（延迟1分钟执行）
     */
    @Scheduled(initialDelay = 60000, fixedDelay = Long.MAX_VALUE)
    public void initializeOnStartup() {
        System.out.println("=== 应用启动，执行初始指标监控配置初始化 ===");
        try {
            metricMonitorService.initializeMonitorConfigs();
            System.out.println("=== 初始指标监控配置初始化完成 ===");
        } catch (Exception e) {
            System.err.println("初始指标监控配置初始化出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
