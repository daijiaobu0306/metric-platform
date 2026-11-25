-- 对端真实推送文件：MetricDataYYYYMMDD.csv，€€ 分隔，单行 114 列，列位对应《满意度指标清单》。
-- 只切换脚本版本与文件匹配规则；不改阈值、评分基准、调度与历史数据。
UPDATE collection_source
SET config_json=JSON_SET(config_json,
        '$.worker.version','satisfaction_report-20261109-001',
        '$.filePattern','MetricData*.csv'),
    next_scan_at=NOW()
WHERE code='satisfaction_report'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.parser'))='python-worker'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.categoryCode'))='satisfaction';
