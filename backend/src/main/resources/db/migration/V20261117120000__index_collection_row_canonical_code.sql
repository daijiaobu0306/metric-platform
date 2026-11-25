-- 2026-11-17 观察表读取超时修复（第二阶段）：按索引等值匹配指标编码。
-- 原匹配写成 BINARY m.metric_code=BINARY JSON_UNQUOTE(JSON_EXTRACT(r.data_json,'$.metric_code'))，
-- 145k 行 × 244 个指标逐行解析 JSON 实测 81 秒；数据汇聚/运营报表的 20 秒超时由此而来。
-- 历史别名 importantAlarmRate 必须与 importantAlarmRatio 归一到同一摘要，否则只能写成 OR/IN，
-- 而 OR/IN 会让优化器放弃索引（实测 128～411 秒）。故新增归一化后的摘要生成列与索引，读取侧用单一等值。
ALTER TABLE collection_row
    ADD COLUMN metric_code_canonical_digest BINARY(32) GENERATED ALWAYS AS (
        UNHEX(SHA2(IF(
            BINARY JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.metric_code')) = 'importantAlarmRate',
            'importantAlarmRatio',
            JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.metric_code'))
        ), 256))
    ) VIRTUAL,
    ADD INDEX idx_collection_row_canonical_code (metric_code_canonical_digest);
