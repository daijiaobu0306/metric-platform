-- 合规采集脚本换新版本号（compliance-20261113-004 → compliance-20261123-001）
-- 原因：004 已发布且不可变，数据日期改用文件名日期的改动应发布为新版本，
-- 否则升级时 installBundled / apply-upgrade.sh 会因「同名不同内容」拒绝安装。
-- 幂等：仅当绑定仍指向 004 时才更新。
UPDATE collection_source
SET config_json = JSON_SET(config_json, '$.worker.version', 'compliance-20261123-001')
WHERE code = 'compliance'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version')) = 'compliance-20261113-004';
