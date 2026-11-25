-- V20260914110000: 重映射 metric_category.id 1/2/3/4 → 8/9/10/11
--
-- 背景：
--   metric_category 表当前 id=1/2/3/4（"对外部客户/对内部人员/系统运行/计划执行"），
--   但 metric_sub_category 和 weight_config 历史数据全部引用 category_id=8/9/10/11
--   （V7 时期 metric_category seed 用的就是 8/9/10/11，后续被改成 1/2/3/4 但子表没同步）。
--   形成悬挂引用：metric_item.category_id=1/2/3/4 → metric_sub_category/weight_config.category_id=8/9/10/11
--   在 JPA 层关联断裂。
--
-- 修复方案：
--   将 metric_category.id 从 1/2/3/4 重映射为 8/9/10/11（与 metric_sub_category/weight_config 历史引用对齐）：
--     1 对外部客户 → 8
--     2 对内部人员 → 9
--     3 系统运行   → 10
--     4 计划执行   → 11
--   同时把 metric_item.category_id 从 1/2/3/4 同步重映射到 8/9/10/11（131 行）。
--   metric_sub_category 和 weight_config 引用保持原值不动（它们本来就是按 8/9/10/11 设计的）。
--
-- 幂等性：
--   使用"先负值再回正"两步法避免 UNIQUE KEY uk_category_metric (category_id, metric_code) 冲突。
--   重映射后所有现有 category_id 都在 8-11 区间内，重跑 migration 会因不存在 (1,2,3,4) 而 no-op。
--   用条件判断确保幂等：仅当 category_id=1 存在时才执行。
--
-- 风险：
--   - DB 层无外键约束（已确认），不需要先 DROP。
--   - AUTO_INCREMENT 计数器需要手动调整到 12 之后。
--   - 应用层 (JPA/Repository) 没有 @JoinColumn 强制校验，无需改实体。
--   - metric_category 重映射后，category_code 没变，UNIQUE KEY uk_system_category (system_id, category_code) 不受影响。
--   - 任何硬编码 category_id 的代码路径（如 MetricAuditService.JIANDU_CATEGORY_ID = 8L），
--     重映射后 8 仍是"对外部客户"，无需修改。
--   - 任何硬编码 1L/2L/3L/4L 引用 metric_category 的代码需自行调整（已知：无）。

-- 仅当存在旧 id=1 时执行
SET @needs_remap = (SELECT COUNT(*) FROM metric_category WHERE id = 1);
SET @sql = IF(@needs_remap > 0,
  'UPDATE metric_item SET category_id = -category_id WHERE category_id IN (1,2,3,4)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(@needs_remap > 0,
  'UPDATE metric_item SET category_id = -category_id + 9 WHERE category_id IN (-1,-2,-3,-4)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(@needs_remap > 0,
  'UPDATE metric_category SET id = -id WHERE id IN (1,2,3,4)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(@needs_remap > 0,
  'UPDATE metric_category SET id = -id + 9 WHERE id IN (-1,-2,-3,-4)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 调整 AUTO_INCREMENT 计数器，避免新行 id 冲突
SET @sql = IF(@needs_remap > 0,
  'ALTER TABLE metric_category AUTO_INCREMENT = 12',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
