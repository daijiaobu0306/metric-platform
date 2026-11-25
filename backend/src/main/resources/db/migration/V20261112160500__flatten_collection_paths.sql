-- 拍平脚本采集任务的导入/归档目录，去掉多余的 scripts 中间层。
--
-- 原：raw_data/import/scripts/<任务code>    →  raw_data/import/<任务code>
-- 原：raw_data/archive/scripts/<任务code>   →  raw_data/archive/<任务code>
--
-- 只改配置里的路径，不动数据库结构、不动任务启停状态、不动任何指标规则。
-- 幂等：仅当路径以旧前缀开头时才更新，重复执行无副作用。
-- 磁盘上的历史文件不会被这条迁移移动，需按部署说明里的 mv 命令同步搬迁。

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
