-- V20260915150000: 整合 metric_import_config 路径
--
-- 背景：2026-09-15 用户反馈：
--   1) 数据质量 6 个子类的原始数据放在一个目录下即可（合并目录）
--   2) 满意度及投诉服务体验 3 个子类的路径名让人看不懂，合并到一个目录
--   3) 资源投入/需求开发 的目录名需要换成英文
--
-- 变更（只动 metric_import_config 表的路径，不动 metric_category / metric_sub_category 的中文名称）：
--   数据质量 6 个子类 → raw_data/import/data_quality/   +  raw_data/archive/data_quality/
--     - businessIndicatorAccuracy / fieldAccuracy / enumValueAbnormalTrigger
--     - crossSystemDataTimeliness / dataConsistency / dataVolatility
--
--   满意度 3 个子类   → raw_data/import/customer_feedback/ + raw_data/archive/customer_feedback/
--     - cityComplaint / innerSatisfaction / serviceComplaint
--
--   资源投入 1 个子类 → raw_data/import/investment/    +  raw_data/archive/investment/
--     - demandDev
--
-- 影响：
--   - 旧路径下未处理的 import 文件需要手动 mv 到新路径，否则调度器扫不到
--   - 旧路径下的 archive 文件同上
--   - 表里的 import_path / archive_path 都是相对路径，下次调度器读新路径自动生效
--
-- 幂等：重复执行结果一致（UPDATE WHERE 老值匹配）

UPDATE metric_import_config
SET import_path = 'raw_data/import/data_quality/'
WHERE sub_category_code IN (
    'businessIndicatorAccuracy', 'fieldAccuracy', 'enumValueAbnormalTrigger',
    'crossSystemDataTimeliness', 'dataConsistency', 'dataVolatility'
);

UPDATE metric_import_config
SET archive_path = 'raw_data/archive/data_quality/'
WHERE sub_category_code IN (
    'businessIndicatorAccuracy', 'fieldAccuracy', 'enumValueAbnormalTrigger',
    'crossSystemDataTimeliness', 'dataConsistency', 'dataVolatility'
);

UPDATE metric_import_config
SET import_path = 'raw_data/import/customer_feedback/'
WHERE sub_category_code IN (
    'cityComplaint', 'innerSatisfaction', 'serviceComplaint'
);

UPDATE metric_import_config
SET archive_path = 'raw_data/archive/customer_feedback/'
WHERE sub_category_code IN (
    'cityComplaint', 'innerSatisfaction', 'serviceComplaint'
);

UPDATE metric_import_config
SET import_path = 'raw_data/import/investment/'
WHERE sub_category_code = 'demandDev';

UPDATE metric_import_config
SET archive_path = 'raw_data/archive/investment/'
WHERE sub_category_code = 'demandDev';
