package com.metric.metric.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 运营观测数据定时计算任务
 */
@Component
public class MetricObservationScheduler {

    @Autowired
    private MetricValueCalcService metricValueCalcService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 每5分钟执行一次指标值计算
     *
     * <p>2026-11-17 P0：集成 mock 模块整体下线后，不再有「mock 运行期间暂停真实计算」的开关，
     * 计算任务恒定启用（原 enabled 标志仅由 MockDataOrchestrator 切换）。</p>
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void calcMetricValues() {
        System.out.println("=== 指标值计算开始 " + LocalDateTime.now().format(FORMATTER) + " ===");
        try {
            metricValueCalcService.calcAllMetricValues();
        } catch (Exception e) {
            System.err.println("指标值计算出错: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("=== 指标值计算完成 " + LocalDateTime.now().format(FORMATTER) + " ===");
    }

    /**
     * 每5分钟执行一次健康度计算
     */
    @Scheduled(cron = "30 */5 * * * ?")
    public void calcHealthScores() {
        System.out.println("=== 健康度计算开始 " + LocalDateTime.now().format(FORMATTER) + " ===");
        try {
            metricValueCalcService.calcAllHealthScores();
        } catch (Exception e) {
            System.err.println("健康度计算出错: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("=== 健康度计算完成 " + LocalDateTime.now().format(FORMATTER) + " ===");
    }

    /**
     * 应用启动时执行一次计算（延迟30秒执行）
     */
    @Scheduled(initialDelay = 30000, fixedDelay = Long.MAX_VALUE)
    public void calcOnStartup() {
        System.out.println("=== 应用启动，执行初始指标计算 ===");
        metricValueCalcService.calcAllMetricValues();
        metricValueCalcService.calcAllHealthScores();
    }
}
