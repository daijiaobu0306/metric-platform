-- ============================================================
-- V46 全局动态通知事件表
-- ------------------------------------------------------------
-- 用途：被前端轮询消费的平台级事件流（指标采集/告警/问题/工单）
-- 设计：毫秒精度时间戳，避免同秒事件覆盖排序
-- ============================================================

CREATE TABLE IF NOT EXISTS notification_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL COMMENT 'DATA_COLLECTION/ALARM/ISSUE/GONGDAN',
    title VARCHAR(200) NOT NULL,
    content VARCHAR(500),
    related_count INT DEFAULT 1,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_id (id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局通知事件（前端轮询消费）';