-- =============================================================================
-- 健康度算法改造 P2：历史表补算法溯源列 + 纠正 46 个指标的历史大类错记
--
-- 1. metric_health_history 增加：
--    mode / rule_version / baseline_used / deviation / sample_count / score_trace / algorithm_version
--    - 新算法写入的行 mode 非空、algorithm_version='rule-v1'；
--    - 迁移前的历史行 algorithm_version='legacy'，页面「新旧对比」据此区分，不删旧行（双跑）。
--    - score_trace 存算分全过程（输入值、方向、基线、偏离度、扣分、结论），
--      解释弹窗直接读它，前端不再复刻公式。
-- 2. 纠正历史上按「小类名」定位导致的大类错记：
--    改造前合规管理/系统稽核 34 个指标被记到「对外部客户」，计划执行 12 个被记到「对内部人员」。
--    只按 metric_item 的真实归属改写 category_name / sub_category_name，保留原行与时间（审计可追溯）。
-- 3. 同步把 metric_value_history 的同类错记一并纠正（动态基线按大类取数时口径一致）。
-- =============================================================================

ALTER TABLE metric_health_history
  ADD COLUMN mode            VARCHAR(20)  NULL COMMENT '算分模式：manual/dynamic/exempt，NULL=旧算法' AFTER status,
  ADD COLUMN rule_version    INT          NULL COMMENT '算分时使用的规则版本，趋势图按此打断点' AFTER mode,
  ADD COLUMN baseline_used   DECIMAL(15,4) NULL COMMENT '本次实际使用的基线（人工基线或动态中位数）' AFTER threshold,
  ADD COLUMN deviation       DECIMAL(15,4) NULL COMMENT '偏离度：人工=相对基线偏离；动态=稳健 z 值' AFTER baseline_used,
  ADD COLUMN sample_count    INT          NULL COMMENT '动态基线样本数' AFTER deviation,
  ADD COLUMN score_trace     JSON         NULL COMMENT '算分过程明细（解释弹窗直接读取）' AFTER sample_count,
  ADD COLUMN algorithm_version VARCHAR(20) NULL COMMENT '规则引擎版本：rule-v1=新算法，legacy=改造前旧算法' AFTER score_trace;

ALTER TABLE metric_health_history
  ADD KEY idx_health_metric_mode (metric_id, mode);

-- 存量行统一标为旧算法，便于「新旧对比」和按算法版本回滚
UPDATE metric_health_history SET algorithm_version = 'legacy' WHERE algorithm_version IS NULL;

-- 纠正历史大类错记（只改归属字段，不改分数与时间）
UPDATE metric_health_history h
JOIN metric_item i ON i.id = h.metric_id
JOIN metric_category c ON c.id = i.category_id
SET h.category_name = c.category_name,
    h.sub_category_name = i.sub_category_name
WHERE h.category_name <> c.category_name;

UPDATE metric_value_history v
JOIN metric_item i ON i.id = v.metric_id
JOIN metric_category c ON c.id = i.category_id
SET v.category_name = c.category_name,
    v.sub_category_name = i.sub_category_name
WHERE v.category_name <> c.category_name;

-- 同一指标同一记录时间的旧行只保留一条（历史数据里可能存在算法版本切换期的重复写入）
-- 注意：唯一键 metric_id + record_time 已保证不重复，这里不需要额外去重。
