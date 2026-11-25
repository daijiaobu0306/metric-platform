-- ============================================================================
-- 修正合规管理（系统稽核）指标单位：户 / 元 / 条 -> 个
--
-- 原因：合规管理采集的指标值统一取源文件「异常量」列（config.json 的
--       abnormalCountIndex: 9），即每个稽核点只输出一个「异常记录数量」，
--       口径只有两类——数量（个）与占比（%）。此前 V20261121122426 依据
--       梳理表「预警阈值」文本中的「用户量 / 费用 / 条数」等告警条件词，
--       把这些指标的单位推断成了「户 / 元 / 条」，属误判：阈值文本描述的
--       是告警条件，不是指标值单位。
--
-- 范围：仅合规管理（category_id = 8）下当前单位仍为 户 / 元 / 条 的 68 条
--       （户 54 + 元 12 + 条 2）。幂等：只在单位仍为目标旧值时回填，不覆盖
--       人工已改的值。
-- 不改：指标编码、名称、小类、阈值、告警策略、采集配置、历史数据。
-- ============================================================================

START TRANSACTION;

-- 户 -> 个（54 条）
UPDATE metric_item
   SET unit = '个', update_time = NOW(6)
 WHERE category_id = 8
   AND unit = '户'
   AND metric_code IN (
    'BOSS01',
    'BOSS06',
    'BOSS07',
    'BOSS10',
    'BOSS101',
    'BOSS102',
    'BOSS104',
    'BOSS108',
    'BOSS109',
    'BOSS11',
    'BOSS110',
    'BOSS112',
    'BOSS115',
    'BOSS119',
    'BOSS120',
    'BOSS124',
    'BOSS125',
    'BOSS126',
    'BOSS127',
    'BOSS129',
    'BOSS130',
    'BOSS14',
    'BOSS146',
    'BOSS147',
    'BOSS150',
    'BOSS151',
    'BOSS152',
    'BOSS153',
    'BOSS155',
    'BOSS158',
    'BOSS161',
    'BOSS162',
    'BOSS163',
    'BOSS164',
    'BOSS166',
    'BOSS167',
    'BOSS168',
    'BOSS169',
    'BOSS170',
    'BOSS171',
    'BOSS36',
    'BOSS37',
    'BOSS42',
    'BOSS43',
    'BOSS47',
    'BOSS49',
    'BOSS51',
    'F158',
    'IT_SR_008',
    'IT_SR_011',
    'IT_SR_012',
    'IT_SR_11_12',
    'ZT_ZQ_009',
    'ZT_ZW_016');

-- 元 -> 个（12 条）
UPDATE metric_item
   SET unit = '个', update_time = NOW(6)
 WHERE category_id = 8
   AND unit = '元'
   AND metric_code IN (
    'BOSS15',
    'BOSS38',
    'BOSS39',
    'BOSS40',
    'BOSS41',
    'BOSS44',
    'BOSS145',
    'ZT_ZQ_001',
    'ZT_ZQ_003',
    'ZT_ZQ_006',
    'ZT_ZQ_007',
    'ZT_ZW_020');

-- 条 -> 个（2 条）
UPDATE metric_item
   SET unit = '个', update_time = NOW(6)
 WHERE category_id = 8
   AND unit = '条'
   AND metric_code IN (
    'BOSS103',
    'BOSS142');

COMMIT;
