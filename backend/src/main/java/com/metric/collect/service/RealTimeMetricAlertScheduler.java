package com.metric.collect.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 实时指标告警定时任务
 * 定时扫描原始数据表，生成触发阈值的告警记录
 */
@Component
public class RealTimeMetricAlertScheduler {

    @Autowired
    private AlertGenerateService alertGenerateService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 每5分钟执行一次告警扫描
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void scanAndGenerateAlerts() {
        System.out.println("=== 实时指标告警扫描开始 " + LocalDateTime.now().format(FORMATTER) + " ===");
        try {
            int count = alertGenerateService.scanAndGenerateAlerts();
            System.out.println("=== 实时指标告警扫描完成，生成 " + count + " 条告警 " + LocalDateTime.now().format(FORMATTER) + " ===");
        } catch (Exception e) {
            System.err.println("实时指标告警扫描出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 应用启动时执行一次扫描（延迟30秒执行）
     */
    @Scheduled(initialDelay = 30000, fixedDelay = Long.MAX_VALUE)
    public void scanOnStartup() {
        System.out.println("=== 应用启动，执行初始告警扫描 ===");
        scanAndGenerateAlerts();
    }
}