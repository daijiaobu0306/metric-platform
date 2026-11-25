-- ============================================================================
-- 清理两条不属于梳理表业务口径的指标：月均调用次数（EFF_GREEN_205）、AI响应时延（EFF_GREEN_206）
--
-- 来源：V20261019165711 从《4、运行效率（标绿色）》表尾第 205/206 行生成，编码是平台补的
--       （表内该两行没有指标编码，definition/threshold 均标 not_provided）。
-- 现状：V20261118105026 已把它们 status 置 0，但指标行、实时值、监控配置、空策略与两条样例采集记录仍在。
-- 用户 2026-11-21 要求全局清理。
--
-- 范围：仅这两个编码；不触碰其他指标、不改历史迁移、不重放基线。
-- 幂等：可重复执行。
-- 回滚：执行前按手册备份目标库；如需恢复，从备份还原或按 V20261019165711 的字段重建。
-- ============================================================================

START TRANSACTION;

-- 1) 依赖数据：实时值、监控配置、告警策略、评分基准、健康规则与历史
DELETE FROM metric_realtime
 WHERE metric_id IN (SELECT id FROM metric_item WHERE metric_code IN ('EFF_GREEN_205','EFF_GREEN_206'));

DELETE FROM metric_monitor_config
 WHERE metric_id IN (SELECT id FROM metric_item WHERE metric_code IN ('EFF_GREEN_205','EFF_GREEN_206'));

DELETE FROM alarm_strategy
 WHERE target_type='metric' AND strategy_type='metric'
   AND target_id IN (SELECT id FROM metric_item WHERE metric_code IN ('EFF_GREEN_205','EFF_GREEN_206'));

DELETE FROM metric_score_baseline
 WHERE metric_id IN (SELECT id FROM metric_item WHERE metric_code IN ('EFF_GREEN_205','EFF_GREEN_206'));

DELETE FROM metric_health_rule
 WHERE metric_id IN (SELECT id FROM metric_item WHERE metric_code IN ('EFF_GREEN_205','EFF_GREEN_206'));

DELETE FROM metric_health_history
 WHERE metric_id IN (SELECT id FROM metric_item WHERE metric_code IN ('EFF_GREEN_205','EFF_GREEN_206'));

-- 2) 验收样例采集记录（仅这两条，来自 V20261020101053 的样例投递）
DELETE FROM collection_row
 WHERE metric_code_digest IN (
   UNHEX(SHA2(CONVERT('EFF_GREEN_205' USING utf8mb4),256)),
   UNHEX(SHA2(CONVERT('EFF_GREEN_206' USING utf8mb4),256)));

-- 3) 指标本体
DELETE FROM metric_item
 WHERE metric_code IN ('EFF_GREEN_205','EFF_GREEN_206');

COMMIT;
