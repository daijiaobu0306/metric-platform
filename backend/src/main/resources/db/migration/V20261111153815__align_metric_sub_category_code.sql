-- 修正 metric_item.sub_category_code：17 个指标原值为「小类名称」而非「小类编码」，
-- 导致按编码关联 metric_sub_category 时匹配不上：
--   对内部人员 7 个（办理类-单业务 -> crm、办理类-融合业务 -> chbn）
--   运行效率   10 个（系统性能 -> performance、系统异常 -> error）
-- 仅改编码，不改指标名称、定义、单位、阈值、采集配置与任何业务数据。
-- 幂等：执行后 sub_category_code 已等于目标编码，再次执行匹配不到任何行。

UPDATE metric_item mi
JOIN metric_sub_category ms
  ON ms.category_id = mi.category_id
 AND ms.sub_category_name = mi.sub_category_code
 AND ms.sub_category_code <> mi.sub_category_code
SET mi.sub_category_code = ms.sub_category_code;
