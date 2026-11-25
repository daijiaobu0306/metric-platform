-- =============================================================================
-- 健康度算法改造 P0 地基（2026-11-17）
-- 前置：deploy/db_export/V54 全库备份已完成并登记台账（doc/数据库版本记录.md）
--
-- 1) 历史表新增数据来源标记 data_origin：动态基线只采样真实采集数据
-- 2) weight_config：清理无效行、小类权重键由编码归一为中文小类名、合并重复行
-- 3) 健康度告警策略：阈值为空 / 目标已不存在的策略置为停用（不补默认阈值）
--
-- 不改历史迁移，不做范围删除（只删本迁移明确列出的无效配置行），不改业务指标数据。
-- mock 生成器下线属于代码变更，不在本迁移内。
-- =============================================================================

-- 1) 数据来源字段 -------------------------------------------------------------
ALTER TABLE `metric_value_history`
  ADD COLUMN `data_origin` VARCHAR(20) NOT NULL DEFAULT 'collected'
  COMMENT '数据来源：collected=真实采集/计算，mock=模拟生成，unknown=无法判定' AFTER `record_time`;

ALTER TABLE `metric_health_history`
  ADD COLUMN `data_origin` VARCHAR(20) NOT NULL DEFAULT 'collected'
  COMMENT '数据来源：collected=真实采集/计算，mock=模拟生成，unknown=无法判定' AFTER `record_time`;

-- 动态基线按「指标 + 来源」取近 90 天数据，补组合索引
CREATE INDEX `idx_value_history_origin` ON `metric_value_history` (`metric_id`, `data_origin`);

-- 回填依据（2026-11-17 实测，两类互斥无交集）：
--   metric_health_history 29634 行：26832 行 metric_code 带 MOCK_ 前缀且 baseline 非空（模拟生成器写入）；
--   其余 2802 行无前缀且 baseline 为 NULL（真实算分写入，算法不写 baseline）。
UPDATE `metric_value_history` SET `data_origin` = 'mock' WHERE `metric_code` LIKE 'MOCK\_%';
UPDATE `metric_health_history` SET `data_origin` = 'mock' WHERE `metric_code` LIKE 'MOCK\_%';
UPDATE `metric_health_history` SET `data_origin` = 'collected'
WHERE `data_origin` <> 'mock' AND `baseline` IS NULL;
UPDATE `metric_health_history` SET `data_origin` = 'unknown'
WHERE `data_origin` <> 'mock' AND `baseline` IS NOT NULL;

-- 2) 权重配置清理与键归一 -----------------------------------------------------
-- 2.1 删除指向已不存在系统的大类权重行（实测 system_id=8 为旧环境遗留，
--     与 system_id=1 的同大类行重复，导致权重读取按重复键失败）。
DELETE FROM `weight_config`
WHERE `weight_type` = 'category'
  AND `system_id` NOT IN (SELECT `id` FROM `system_info`);

-- 2.2 删除指向已不存在大类的权重行（实测 category_id=16 已无对应大类）。
DELETE FROM `weight_config`
WHERE `category_id` NOT IN (SELECT `id` FROM `metric_category`);

-- 2.3 小类权重键归一：historical 数据里有编码键（performance/error/crm/query/jihe/
--     operationEffect/reportBoard/payActive/trafficAlert…）和中文名键混用，
--     这里统一替换为该大类下真实存在的 sub_category_name。
UPDATE `weight_config` w
SET w.`sub_category_name` = (
  SELECT MIN(i.`sub_category_name`)
  FROM `metric_item` i
  WHERE i.`category_id` = w.`category_id`
    AND (BINARY i.`sub_category_code` = BINARY w.`sub_category_name`
         OR BINARY i.`sub_category_name` = BINARY w.`sub_category_name`)
)
WHERE w.`weight_type` = 'subcategory'
  AND EXISTS (
    SELECT 1 FROM `metric_item` i
    WHERE i.`category_id` = w.`category_id`
      AND (BINARY i.`sub_category_code` = BINARY w.`sub_category_name`
           OR BINARY i.`sub_category_name` = BINARY w.`sub_category_name`)
  );

-- 2.4 合并重复小类权重行：同一 (大类, 小类) 保留 update_time 最新的一行
--     （实测 category 13 同时存在 运营成效/operationEffect、报表看板/reportBoard 两组，
--      归一后变成重名，保留 2026-10-22 的 0.50 一组）。
--     注意：无法映射到现有小类的历史键（如计划执行旧分类 gongdan/issue）不删除，
--     它们不会被算分链路读取（按名称查找），保留以避免丢失业务配置意图。
DELETE w1 FROM `weight_config` w1
JOIN `weight_config` w2
  ON w1.`weight_type` = 'subcategory' AND w2.`weight_type` = 'subcategory'
 AND w1.`category_id` = w2.`category_id`
 AND BINARY w1.`sub_category_name` = BINARY w2.`sub_category_name`
 AND (w1.`update_time` < w2.`update_time`
      OR (w1.`update_time` = w2.`update_time` AND w1.`id` < w2.`id`));

-- 3) 健康度告警策略 ------------------------------------------------------------
-- 3.1 阈值为空的健康度策略（实测 4 条：业务价值/资源投入/数据质量/系统健康度）。
--     按项目约定「未提供阈值就是未配置」，不填默认值，直接停用待业务配置。
UPDATE `alarm_strategy`
SET `status` = 0, `update_time` = NOW()
WHERE `strategy_type` = 'health' AND `alarm_threshold` IS NULL;

-- 3.2 目标已不存在的健康度策略（实测 id=3 指向 category_id=1，该大类已不存在）。
UPDATE `alarm_strategy` s
SET s.`status` = 0, s.`update_time` = NOW()
WHERE s.`strategy_type` = 'health' AND s.`target_type` = 'category'
  AND NOT EXISTS (SELECT 1 FROM `metric_category` c WHERE c.`id` = s.`target_id`);
