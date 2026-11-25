-- 合规管理回写专表：月清单脚本升级（带原始列）+ 新增工单明细采集任务。
--
-- 依据：用户 2026-11-13 授权「数据汇聚页继续读专表，采集仍走脚本+采集任务」。
-- 脚本带出原始列，后端在提交批次时回写 system_jhd / system_jhd_detail 专表。
-- 1) 月清单任务 compliance 脚本升级到 -004（records 额外带 27 列原始字段）。
--    -003 的原始列位有错位（关单量起整体偏移一位、缺整改及时率、缺稽核数据情况/核查情况），
--    修正后另发 -004，-003 保持不可变且不被引用。
-- 2) 新增 compliance_detail 任务：读 system_jhd_detail_*.txt，raw-rows 协议，回写工单明细专表。
-- 不改表结构、不改指标规则与阈值、不删历史采集数据。幂等。

-- 1) 月清单脚本升级到 -004
UPDATE collection_source
SET last_error = NULL,
    next_scan_at = NOW(),
    config_json = JSON_SET(
        config_json,
        '$.worker.version', 'compliance-20261113-004'
    )
WHERE code = 'compliance'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.version')) IN ('compliance-20261113-002', 'compliance-20261113-003');

-- 2) 新增工单明细采集任务（raw-rows，不产生指标值，只回写工单明细专表）
INSERT INTO collection_source(code, name, config_json, enabled, next_scan_at)
SELECT 'compliance_detail', '合规管理工单明细采集',
  JSON_OBJECT(
    'code','compliance_detail',
    'name','合规管理工单明细采集',
    'parser','python-worker',
    'format','txt',
    'filePattern','system_jhd_detail_[0-9]*.txt',
    'enabled',true,
    'importPath','raw_data/import/compliance_detail',
    'archivePath','raw_data/archive/compliance_detail',
    'intervalSeconds',300,
    'settleSeconds',30,
    'dateMode','data_time',
    'dateRegex',NULL,
    'dateFormat',NULL,
    'sheet','',
    'delimiter','@',
    'encoding','UTF-8',
    'fields',JSON_ARRAY(),
    'worker',JSON_OBJECT('version','compliance-detail-20261113-001','categoryCode','metric_g1','timeoutSeconds',300)
  ),
  true, NOW()
WHERE NOT EXISTS (SELECT 1 FROM collection_source WHERE code = 'compliance_detail');
