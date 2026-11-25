-- 标记合规管理工单明细任务为明细行协议（raw-rows）。
--
-- 背景：合规管理有两条采集任务（数据集不同，不是重复采集同一数据集）：
--   compliance       = 月数据清单，指标值协议，产出指标值与月清单
--   compliance_detail= 工单明细，明细行协议，只回写 system_jhd_detail，不产生指标值
-- compliance_detail 由 V20261113113000 直接写入，fields 为空。
-- 平台的字段映射校验只适用于指标值协议任务，明细行协议任务不需要指标字段映射，
-- 因此在任务配置里显式声明协议，避免保存调度时被误判为“未配置字段映射”。
--
-- 本迁移只补充任务配置里的协议标记，不动指标规则、阈值、脚本版本与历史采集数据，幂等：
--   - 已经是 raw-rows → 命中 0 行
--   - 未标记 → 标记为 raw-rows

UPDATE collection_source
SET config_json = JSON_SET(config_json, '$.protocol', 'raw-rows')
WHERE code = 'compliance_detail'
  AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.protocol')), '') <> 'raw-rows';
