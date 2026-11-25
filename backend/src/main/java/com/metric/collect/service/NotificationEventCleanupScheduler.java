package com.metric.collect.service;

import com.metric.collect.repository.NotificationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 全局通知事件保留策略：只保留最近 30 天。
 *
 * 通知表是"瞬态消息"用途，前端轮询消费完、历史管理页也主要是近期排查，
 * 长期不清会因历史噪音无限膨胀；需要更长留存时改这一个常量即可。
 */
@Component
public class NotificationEventCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventCleanupScheduler.class);

    /** 保留天数 */
    private static final int RETENTION_DAYS = 30;

    @Autowired
    private NotificationEventRepository repository;

    /** 每小时清理一次 7 天前的事件 */
    @Scheduled(cron = "0 17 * * * ?")
    public void cleanup() {
        try {
            LocalDateTime deadline = LocalDateTime.now().minusDays(RETENTION_DAYS);
            int deleted = repository.deleteOlderThan(deadline);
            if (deleted > 0) {
                log.info("清理全局通知事件 {} 条（早于 {}）", deleted, deadline);
            }
        } catch (Exception e) {
            log.warn("清理全局通知事件失败: {}", e.getMessage());
        }
    }
}
