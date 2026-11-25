package com.metric.collect.service;

import com.metric.config.entity.PlatformConfig;
import com.metric.collect.repository.*;
import com.metric.config.repository.PlatformConfigRepository;
import com.metric.observe.repository.HealthSnapshot1dRepository;
import com.metric.observe.repository.HealthSnapshot1hRepository;
import com.metric.observe.repository.HealthSnapshot30dRepository;
import com.metric.observe.repository.HealthSnapshot6hRepository;
import com.metric.observe.repository.HealthSnapshot7dRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 健康度数据保留期清理任务
 *
 * 每天凌晨 4 点执行，按 platform_config 中的 6 个保留期配置删除过期数据。
 * 默认保留天数：
 *   - health_snapshot_1h:     30 天
 *   - health_snapshot_6h:     90 天
 *   - health_snapshot_1d:     365 天
 *   - health_snapshot_7d:     730 天 (2 年)
 *   - health_snapshot_30d:    1825 天 (5 年)
 *   - 明细表（4 张）:           7 天
 */
@Component
public class DataRetentionScheduler {

    @Autowired private PlatformConfigRepository platformConfigRepo;
    @Autowired private HealthSnapshot1hRepository snapshot1hRepo;
    @Autowired private HealthSnapshot6hRepository snapshot6hRepo;
    @Autowired private HealthSnapshot1dRepository snapshot1dRepo;
    @Autowired private HealthSnapshot7dRepository snapshot7dRepo;
    @Autowired private HealthSnapshot30dRepository snapshot30dRepo;

    @PersistenceContext
    private EntityManager entityManager;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 每天凌晨 4 点执行清理 */
    @Scheduled(cron = "0 0 4 * * ?")
    @Transactional
    public void cleanupExpiredData() {
        LocalDateTime now = LocalDateTime.now();
        long startTime = System.currentTimeMillis();
        System.out.println("[数据保留期清理] 开始执行 " + now.format(FORMATTER));

        Map<String, Integer> retentionConfig = loadRetentionConfig();
        int totalDeleted = 0;

        // 清理 5 张快照表
        totalDeleted += cleanupSnapshotTable(snapshot1hRepo, "health_snapshot_1h", retentionConfig.getOrDefault("health_retention_1h_days", 30), now);
        totalDeleted += cleanupSnapshotTable(snapshot6hRepo, "health_snapshot_6h", retentionConfig.getOrDefault("health_retention_6h_days", 90), now);
        totalDeleted += cleanupSnapshotTable(snapshot1dRepo, "health_snapshot_1d", retentionConfig.getOrDefault("health_retention_1d_days", 365), now);
        totalDeleted += cleanupSnapshotTable(snapshot7dRepo, "health_snapshot_7d", retentionConfig.getOrDefault("health_retention_7d_days", 730), now);
        totalDeleted += cleanupSnapshotTable(snapshot30dRepo, "health_snapshot_30d", retentionConfig.getOrDefault("health_retention_30d_days", 1825), now);

        // 清理明细表（4 张）
        int detailDays = retentionConfig.getOrDefault("health_retention_detail_days", 7);
        totalDeleted += cleanupDetailTables(detailDays, now);

        System.out.println("[数据保留期清理] 完成，总删除 " + totalDeleted + " 条，耗时 " +
                (System.currentTimeMillis() - startTime) + "ms");
    }

    private Map<String, Integer> loadRetentionConfig() {
        Map<String, Integer> result = new HashMap<>();
        for (String key : new String[]{
                "health_retention_1h_days", "health_retention_6h_days",
                "health_retention_1d_days", "health_retention_7d_days",
                "health_retention_30d_days", "health_retention_detail_days"
        }) {
            Optional<PlatformConfig> config = platformConfigRepo.findByConfigKey(key);
            if (config.isPresent() && config.get().getConfigValue() != null) {
                try {
                    result.put(key, Integer.parseInt(config.get().getConfigValue()));
                } catch (NumberFormatException e) {
                    System.err.println("[数据保留期清理] 配置项 " + key + " 值非法: " + config.get().getConfigValue());
                }
            }
        }
        return result;
    }

    private int cleanupSnapshotTable(org.springframework.data.jpa.repository.JpaRepository<?, Long> repo,
                                     String tableName,
                                     int retentionDays,
                                     LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(retentionDays);
        try {
            int deleted;
            if (repo instanceof HealthSnapshot1hRepository) {
                deleted = ((HealthSnapshot1hRepository) repo).deleteOlderThan(cutoff);
            } else if (repo instanceof HealthSnapshot6hRepository) {
                deleted = ((HealthSnapshot6hRepository) repo).deleteOlderThan(cutoff);
            } else if (repo instanceof HealthSnapshot1dRepository) {
                deleted = ((HealthSnapshot1dRepository) repo).deleteOlderThan(cutoff);
            } else if (repo instanceof HealthSnapshot7dRepository) {
                deleted = ((HealthSnapshot7dRepository) repo).deleteOlderThan(cutoff);
            } else if (repo instanceof HealthSnapshot30dRepository) {
                deleted = ((HealthSnapshot30dRepository) repo).deleteOlderThan(cutoff);
            } else {
                return 0;
            }
            if (deleted > 0) {
                System.out.println("[数据保留期清理] " + tableName + " 删除 " + deleted + " 条（保留 " + retentionDays + " 天，截止 " + cutoff + "）");
            }
            return deleted;
        } catch (Exception e) {
            System.err.println("[数据保留期清理] " + tableName + " 清理失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 清理 4 张明细表（按 record_time 删）
     * 使用 EntityManager 执行 native SQL 删除
     */
    private int cleanupDetailTables(int retentionDays, LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(retentionDays);
        int totalDeleted = 0;

        String[] detailTables = {
            "metric_health_history",
            "sub_category_health_history",
            "category_health_history",
            "metric_value_history"
        };

        for (String table : detailTables) {
            try {
                int deleted = entityManager.createNativeQuery(
                        "DELETE FROM " + table + " WHERE record_time < :cutoff")
                        .setParameter("cutoff", cutoff)
                        .executeUpdate();
                if (deleted > 0) {
                    System.out.println("[数据保留期清理] " + table + " 删除 " + deleted + " 条（保留 " + retentionDays + " 天）");
                }
                totalDeleted += deleted;
            } catch (Exception e) {
                System.err.println("[数据保留期清理] " + table + " 清理失败: " + e.getMessage());
            }
        }
        return totalDeleted;
    }
}
