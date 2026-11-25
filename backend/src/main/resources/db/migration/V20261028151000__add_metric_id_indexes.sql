-- ============================================================================
-- 2026-10-28 导入改造配套：给两张历史表的 metric_id 加索引
-- ============================================================================
-- 背景：
--   全量导入改为"以 Excel 为准，库里缺失的指标真删除"。删除一个指标时，需要按
--   metric_id 清理它的历史数据。而 metric_health_history（约 1.5 万行）、
--   metric_value_history（约 1.7 万行）的 metric_id 没有索引，DELETE 会全表扫描，
--   删几十个指标可能卡住导入事务。
--
-- 影响：仅加普通索引（非唯一），不改任何数据，可随时用 DROP INDEX 回滚。
-- 幂等：先查 information_schema，索引已存在则跳过，避免在已手工加过的环境报错。
-- 备份：V38（这两张表的结构 + 全量数据快照）
-- ============================================================================

SET @c1 := (SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'metric_health_history'
              AND INDEX_NAME = 'idx_metric_id');
SET @s1 := IF(@c1 = 0,
              'ALTER TABLE metric_health_history ADD INDEX idx_metric_id (metric_id)',
              'SELECT 1');
PREPARE stmt1 FROM @s1;
EXECUTE stmt1;
DEALLOCATE PREPARE stmt1;

SET @c2 := (SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'metric_value_history'
              AND INDEX_NAME = 'idx_metric_id');
SET @s2 := IF(@c2 = 0,
              'ALTER TABLE metric_value_history ADD INDEX idx_metric_id (metric_id)',
              'SELECT 1');
PREPARE stmt2 FROM @s2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
