-- 专屏视图首页性能优化（2026-11-22）
--
-- alarm / issue / gongdan 增加 metric_id、category_id 索引：
-- 专屏 events 统计（DashboardWorkspaceService.stats）用 filter 生成
--   metric_id IN (...) OR category_id IN (...) OR system_id IN (...) OR EXISTS(...)
-- 此前三表仅有 system_id / 时间 / 状态索引，缺少 metric_id、category_id，
-- 事件数据量上来后该 OR 条件会退化为全表扫描。
--
-- 注：专屏首页的另一个主瓶颈（observationReads 对 collection_row 全历史扫描取「每指标最新值」）
-- 需重构取数方式（按 metric_code_canonical_digest + data_time 复合索引做 loose index scan），
-- 属核心路径改动，另行实施；本次不包含。

ALTER TABLE alarm
    ADD INDEX idx_alarm_metric_id (metric_id),
    ADD INDEX idx_alarm_category_id (category_id);

ALTER TABLE issue
    ADD INDEX idx_issue_metric_id (metric_id),
    ADD INDEX idx_issue_category_id (category_id);

ALTER TABLE gongdan
    ADD INDEX idx_gongdan_metric_id (metric_id),
    ADD INDEX idx_gongdan_category_id (category_id);
