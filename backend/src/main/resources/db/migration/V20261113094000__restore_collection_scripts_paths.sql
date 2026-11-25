-- 回滚 V20261112160500 的目录拍平。
--
-- 原因：拍平后 data_quality 任务的 raw_data/import/data_quality 与旧通道
-- （metric_import_config 里 dataVolatility / dataConsistency / fieldAccuracy 等 6 条，
--  共用 raw_data/import/data_quality/）路径重叠，CollectionDirectoryGuard 启动期校验
-- 直接抛「目录与旧采集通道 xxx 重叠」，应用起不来。
--
-- 处置：import 全部恢复 scripts/ 中间层；archive 只恢复原本就带 scripts 的 3 个任务
-- （compliance / data_quality / internal_staff），其余保持原状。
--
-- 幂等：仅当当前值等于拍平后的形式时才改回，重复执行无副作用。
-- 说明：目录层级保持不变是刻意的——采集目录必须与其他任务、旧通道互不重叠。

UPDATE collection_source
SET config_json = JSON_SET(config_json, '$.importPath',
        CONCAT('raw_data/import/scripts/', code))
WHERE code IN ('business_value', 'compliance', 'data_quality', 'external_customer',
               'internal_staff', 'resource_input', 'running_efficiency', 'satisfaction_report')
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.importPath')) = CONCAT('raw_data/import/', code);

UPDATE collection_source
SET config_json = JSON_SET(config_json, '$.archivePath',
        CONCAT('raw_data/archive/scripts/', code))
WHERE code IN ('compliance', 'data_quality', 'internal_staff')
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.archivePath')) = CONCAT('raw_data/archive/', code);
