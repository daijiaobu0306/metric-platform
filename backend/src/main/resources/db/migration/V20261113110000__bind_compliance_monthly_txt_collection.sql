-- 合规管理改为采集真实生产报文 system_jhd_YYYYMMDD.txt（月数据清单）。
--
-- 依据：用户 2026-11-13 授权修改合规管理采集逻辑，沿用既有采集任务+脚本形式。
-- 口径：指标编码=稽核点编号(check_code)，指标值=异常量(abnormal_count)，
--       数据时间=异常日期(abnormal_date)，均直接取源列，不做派生计算。
-- 只改既有任务 compliance 的协议与脚本版本；不改表结构、不改指标规则与阈值、不删历史数据。
-- 幂等：仅当当前脚本版本仍为 compliance-20261027-001 时更新，重复执行无副作用。

UPDATE collection_source
SET enabled = 1,
    last_error = NULL,
    next_scan_at = NOW(),
    config_json = '{"code":"compliance","name":"合规管理脚本采集","parser":"python-worker","format":"txt","filePattern":"system_jhd_[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9].txt","enabled":true,"importPath":"raw_data/import/compliance","archivePath":"raw_data/archive/compliance","intervalSeconds":300,"settleSeconds":30,"dateMode":"data_time","dateRegex":null,"dateFormat":null,"sheet":"","delimiter":"@","encoding":"UTF-8","fields":[{"source":"稽核点编号","target":"metric_code","type":"string","required":true},{"source":"异常量","target":"metric_value","type":"decimal","required":false},{"source":"异常日期","target":"data_time","type":"datetime","required":true}],"worker":{"version":"compliance-20261113-001","categoryCode":"metric_g1","timeoutSeconds":300}}'
WHERE code = 'compliance'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version')) = 'compliance-20261027-001';
