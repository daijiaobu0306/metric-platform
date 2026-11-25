-- ============================================================================
-- 指标单位与阈值口径统一（改动点 1 + 改动点 2）
--
-- 基线：V20261027150955（calc 字段清理）之后，metric_item 为 23 列
-- 原则：
--   1. 只新增列，不删除任何列、不修改任何已有列类型
--   2. 阈值回填遵循「metric_item 现有值优先」，仅在为空时从另两处补入
--   3. 不清理任何存量数据（孤儿行、空壳策略均保留）
--   4. 本迁移幂等：若目标库已有这些列则跳过（保护演示环境从 dump 恢复的场景）
--
-- 执行后：
--   metric_item   23 -> 31 列，成为「单位 + 阈值」的唯一真值来源
--   metric_monitor_config / alarm_strategy 的阈值字段降级为只读历史值
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 新增 6 个描述性字段（均可空，允许历史数据为空）
-- ---------------------------------------------------------------------------
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'metric_item'
                     AND column_name = 'alarm_consecutive_times');

SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE metric_item
     ADD COLUMN responsible_person      VARCHAR(100) NULL COMMENT ''面向人员（如网格长、营业员、客户经理）'',
     ADD COLUMN statistics_caliber      VARCHAR(500) NULL COMMENT ''统计口径'',
     ADD COLUMN data_sync_mode          VARCHAR(50)  NULL COMMENT ''数据对接模式（如 prometheus）'',
     ADD COLUMN collect_status          VARCHAR(50)  NULL COMMENT ''采集状态（如 已采集/未对接）'',
     ADD COLUMN department              VARCHAR(100) NULL COMMENT ''部室'',
     ADD COLUMN reporter                VARCHAR(50)  NULL COMMENT ''填报人'',
     ADD COLUMN alarm_consecutive_times   INT NULL COMMENT ''告警连续次数阈值'',
     ADD COLUMN gongdan_consecutive_times INT NULL COMMENT ''工单连续次数阈值''',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2) 条件字段脏值归一化：历史上有把中文「超标」写进条件列的情况
--    「超标」语义等价于「大于阈值」，统一归一为 '>'
-- ---------------------------------------------------------------------------
UPDATE metric_item
   SET alarm_threshold_type = '>'
 WHERE alarm_threshold_type IN ('超标', '超', '超出');

UPDATE metric_item
   SET gongdan_threshold_type = '>'
 WHERE gongdan_threshold_type IN ('超标', '超', '超出');

UPDATE metric_monitor_config
   SET alarm_condition = '>'
 WHERE alarm_condition IN ('超标', '超', '超出');

UPDATE metric_monitor_config
   SET gongdan_condition = '>'
 WHERE gongdan_condition IN ('超标', '超', '超出');

UPDATE alarm_strategy
   SET alarm_condition = '>'
 WHERE alarm_condition IN ('超标', '超', '超出');

UPDATE alarm_strategy
   SET gongdan_condition = '>'
 WHERE gongdan_condition IN ('超标', '超', '超出');

-- ---------------------------------------------------------------------------
-- 3) 阈值回填（源 1：metric_monitor_config，按 metric_id 一对一）
--    仅在 metric_item 对应字段为空时补入，已有值不动
-- ---------------------------------------------------------------------------
UPDATE metric_item i
  JOIN metric_monitor_config c ON c.metric_id = i.id
   SET i.alarm_threshold            = COALESCE(i.alarm_threshold, c.alarm_threshold),
       i.alarm_threshold_type       = COALESCE(i.alarm_threshold_type, c.alarm_condition),
       i.gongdan_threshold          = COALESCE(i.gongdan_threshold, c.gongdan_threshold),
       i.gongdan_threshold_type     = COALESCE(i.gongdan_threshold_type, c.gongdan_condition),
       i.alarm_consecutive_times    = COALESCE(i.alarm_consecutive_times, c.consecutive_count),
       i.gongdan_consecutive_times  = COALESCE(i.gongdan_consecutive_times, c.consecutive_count)
 WHERE i.alarm_threshold IS NULL
    OR i.gongdan_threshold IS NULL
    OR i.alarm_threshold_type IS NULL
    OR i.gongdan_threshold_type IS NULL;

-- ---------------------------------------------------------------------------
-- 4) 阈值回填（源 2：alarm_strategy，仅 strategy_type='metric' 的本指标策略）
--    注意：health 类型策略的阈值是 0-100 分、无量纲，绝不回填
-- ---------------------------------------------------------------------------
UPDATE metric_item i
  JOIN alarm_strategy s ON s.target_id = i.id
                       AND s.strategy_type = 'metric'
   SET i.alarm_threshold            = COALESCE(i.alarm_threshold, s.alarm_threshold),
       i.alarm_threshold_type       = COALESCE(i.alarm_threshold_type, s.alarm_condition),
       i.gongdan_threshold          = COALESCE(i.gongdan_threshold, s.gongdan_threshold),
       i.gongdan_threshold_type     = COALESCE(i.gongdan_threshold_type, s.gongdan_condition),
       i.alarm_consecutive_times    = COALESCE(i.alarm_consecutive_times, s.alarm_consecutive_times),
       i.gongdan_consecutive_times  = COALESCE(i.gongdan_consecutive_times, s.gongdan_consecutive_times)
 WHERE i.alarm_threshold IS NULL
    OR i.gongdan_threshold IS NULL
    OR i.alarm_threshold_type IS NULL
    OR i.gongdan_threshold_type IS NULL;
