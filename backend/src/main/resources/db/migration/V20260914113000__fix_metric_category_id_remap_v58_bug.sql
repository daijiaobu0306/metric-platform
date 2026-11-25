-- V20260914113000: 修复 V58 重映射算法的偏移错误
--
-- 背景：
--   V58 (V20260914110000) 的两步法 UPDATE 偏移公式 -id + 9 只对 id=1 正确（-1+9=8），
--   但其他全错：
--     -2 + 9 = 7（期望 9）
--     -3 + 9 = 6（期望 10）
--     -4 + 9 = 5（期望 11）
--   实际结果：
--     metric_category.id 1/2/3/4 → 10/11/12/13（应为 8/9/10/11）
--     metric_item.category_id 1/2/3/4 → 10/11/12/13（应为 8/9/10/11）
--   metric_sub_category / weight_config 不受影响（它们保持原 8/9/10/11 不动）。
--
-- 修复：
--   使用 CASE WHEN 精确映射：
--     metric_category.id 10/11/12/13 → 8/9/10/11
--     metric_item.category_id 10/11/12/13 → 8/9/10/11
--
-- 幂等：
--   仅当存在错位（metric_category.id=10 AND category_code=metric_g1）时执行。
--   用 CASE WHEN 直接映射，不会触发 UNIQUE KEY uk_category_metric 冲突
--   （错位状态下 metric_item.category_id=10/11/12，修复后变成 8/9/10，而
--    原 8/9/10 是 metric_sub_category/weight_config 里的引用，metric_item 里没有这些 id）。

-- 仅当存在错位状态时执行
SET @needs_fix = (SELECT COUNT(*) FROM metric_category WHERE id = 10 AND category_code = 'metric_g1');

-- 1) 修复 metric_item.category_id：10/11/12/13 → 8/9/10/11
UPDATE metric_item
SET category_id = CASE category_id
  WHEN 10 THEN 8
  WHEN 11 THEN 9
  WHEN 12 THEN 10
  WHEN 13 THEN 11
END
WHERE @needs_fix > 0 AND category_id IN (10, 11, 12, 13);

-- 2) 修复 metric_category.id：10/11/12/13 → 8/9/10/11
UPDATE metric_category
SET id = CASE id
  WHEN 10 THEN 8
  WHEN 11 THEN 9
  WHEN 12 THEN 10
  WHEN 13 THEN 11
END
WHERE @needs_fix > 0 AND id IN (10, 11, 12, 13);

-- 3) 调整 AUTO_INCREMENT 计数器到 12 之后（避免新行 id 冲突）
SET @sql = IF(@needs_fix > 0,
  'ALTER TABLE metric_category AUTO_INCREMENT = 12',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
