-- =============================================================================
-- 指标与告警解耦：指标只描述"是什么"，告警只在 alarm_strategy 配
-- =============================================================================
-- 背景：metric_item 上原本挂了 alarm_threshold / gongdan_threshold 等 6 列，
--       与 alarm_strategy、metric_monitor_config 形成三处存储、两条链路读取。
--       本次把告警相关字段从指标表移除，告警阈值唯一来源改为 alarm_strategy。
--
-- 算分基准：现算法（100 −(值−基准)×系数）仍需要一个"基准值"。
--   按约定算法暂不改，故把该值迁入独立的 metric_score_baseline 表承接，
--   待算法专题（趋势/偏离度）落地后整表替换，指标表保持干净。
--
-- 本迁移不改任何历史迁移，不重建表，不做范围删除。
-- =============================================================================

-- 1) 算分基准表（一指标一行）
CREATE TABLE IF NOT EXISTS `metric_score_baseline` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `metric_id`          BIGINT       NOT NULL                COMMENT '指标ID',
  `score_mode`         VARCHAR(20)  NOT NULL DEFAULT 'threshold' COMMENT '算分模式：threshold=按基准值扣分（当前算法）；trend=按历史偏离（待算法专题实现）',
  `baseline_value`     DECIMAL(15,4)         DEFAULT NULL     COMMENT '评分基准值（由 metric_item.alarm_threshold 迁移而来）',
  `baseline_condition` VARCHAR(20)           DEFAULT NULL     COMMENT '基准比较方向，如 > / < / >= / <=',
  `create_time`        DATETIME              DEFAULT CURRENT_TIMESTAMP,
  `update_time`        DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_metric_score_baseline_metric` (`metric_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标评分基准（与告警解耦；算法专题将替换此表）';

-- 2) 把指标表现有的阈值搬为"评分基准值"
INSERT INTO `metric_score_baseline` (`metric_id`, `score_mode`, `baseline_value`, `baseline_condition`)
SELECT i.`id`, 'threshold', i.`alarm_threshold`, i.`alarm_threshold_type`
FROM `metric_item` i
WHERE i.`alarm_threshold` IS NOT NULL
   OR i.`alarm_threshold_type` IS NOT NULL
ON DUPLICATE KEY UPDATE
  `baseline_value`     = VALUES(`baseline_value`),
  `baseline_condition` = VALUES(`baseline_condition`);

-- 3) 为"指标表有阈值、但没有任何告警策略"的指标补建策略
--    目的：这 56 个阈值原本配在指标上、实际未生效（无策略不扫描），
--          直接删列会丢失配置。补建为 status=0（停用），
--          既保留数据，又不会立刻产生新告警，由业务在告警页确认后逐个启用。
INSERT INTO `alarm_strategy`
  (`strategy_name`, `strategy_type`, `target_type`, `target_id`, `target_name`,
   `alarm_enabled`, `alarm_threshold`, `alarm_condition`, `alarm_consecutive_times`,
   `gongdan_enabled`, `gongdan_threshold`, `gongdan_condition`, `gongdan_consecutive_times`,
   `condition_type`, `threshold_value`, `consecutive_times`, `generate_type`, `status`)
SELECT CONCAT(LEFT(i.`metric_name`, 80), '-指标阈值迁移'),
       'metric', 'metric', i.`id`, LEFT(i.`metric_name`, 200),
       1, i.`alarm_threshold`, IFNULL(NULLIF(i.`alarm_threshold_type`, ''), '>'),
       IFNULL(i.`alarm_consecutive_times`, 1),
       IF(i.`gongdan_threshold` IS NULL, 0, 1),
       i.`gongdan_threshold`, IFNULL(NULLIF(i.`gongdan_threshold_type`, ''), '>'),
       IFNULL(i.`gongdan_consecutive_times`, 1),
       IFNULL(NULLIF(i.`alarm_threshold_type`, ''), '>'),
       i.`alarm_threshold`,
       IFNULL(i.`alarm_consecutive_times`, 1),
       'alarm', 0
FROM `metric_item` i
WHERE (i.`alarm_threshold` IS NOT NULL OR i.`gongdan_threshold` IS NOT NULL)
  AND NOT EXISTS (
        SELECT 1 FROM `alarm_strategy` s
        WHERE s.`strategy_type` = 'metric' AND s.`target_id` = i.`id`
      );

-- 4) 指标表去掉 6 个告警/工单字段
ALTER TABLE `metric_item`
  DROP COLUMN `alarm_threshold`,
  DROP COLUMN `alarm_threshold_type`,
  DROP COLUMN `gongdan_threshold`,
  DROP COLUMN `gongdan_threshold_type`,
  DROP COLUMN `alarm_consecutive_times`,
  DROP COLUMN `gongdan_consecutive_times`;
