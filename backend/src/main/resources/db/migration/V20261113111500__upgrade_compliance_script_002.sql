-- 合规采集脚本升级到 compliance-20261113-002，并按接口说明放宽文件匹配。
--
-- 变更原因（2026-11-13，用户授权范围内）：
-- 1. 指标白名单改为以《运营指标体系梳理-8个维度》「5、合规管理」104 项为准，
--    与平台 metric_g1 已启用指标双向一致；原白名单误取自被模拟数据污染的云端库。
-- 2. 接口说明规定文件名为 system_jhd_YYYYMMDD-D1.txt，原 filePattern 只认 8 位日期，
--    放宽为 system_jhd_[0-9]*.txt（仍排除 system_jhd_detail_*.txt）。
-- 3. reader 增加整行重复导出的去重（数值一致按一条处理；数值不同则明确报错），
--    不引入求和等派生规则。
-- 只改协议与脚本版本；不改表结构、不改指标规则与阈值、不删历史采集数据。
-- 幂等：仅当脚本版本仍为 compliance-20261113-001 时更新。

UPDATE collection_source
SET last_error = NULL,
    next_scan_at = NOW(),
    config_json = JSON_SET(
        config_json,
        '$.filePattern', 'system_jhd_[0-9]*.txt',
        '$.worker.version', 'compliance-20261113-002'
    )
WHERE code = 'compliance'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version')) = 'compliance-20261113-001';
