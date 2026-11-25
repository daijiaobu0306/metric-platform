-- 拍平脚本采集任务的投递/归档目录（最终口径）。
--
-- 原：raw_data/import/scripts/<code>   →  raw_data/import/<code>
-- 原：raw_data/archive/scripts/<code>  →  raw_data/archive/<code>
--
-- 与 V20261112160500 的区别：那次拍平后被 V20261113094000 回滚了，因为
-- metric_import_config 里 6 条已停用的旧通道（dataVolatility 等）也占用
-- raw_data/import/data_quality/，目录守卫判为重叠导致启动失败。
-- 本次同时把守卫口径改为「只校验 enabled=1 的旧通道」，停用通道不再参与冲突判定，
-- 因此拍平可以落地。
--
-- 只改配置里的路径：不动表结构、不动任务启停状态、不动指标规则。
-- 幂等：仅当路径仍带 scripts/ 中间层时才更新，重复执行无副作用。
-- 磁盘文件需按部署说明同步搬迁（mv，不要删）。

UPDATE collection_source
SET config_json = JSON_SET(
        config_json,
        '$.importPath',
        REPLACE(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.importPath')),
                'raw_data/import/scripts/', 'raw_data/import/'))
WHERE JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.importPath')) LIKE 'raw_data/import/scripts/%';

UPDATE collection_source
SET config_json = JSON_SET(
        config_json,
        '$.archivePath',
        REPLACE(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.archivePath')),
                'raw_data/archive/scripts/', 'raw_data/archive/'))
WHERE JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.archivePath')) LIKE 'raw_data/archive/scripts/%';
