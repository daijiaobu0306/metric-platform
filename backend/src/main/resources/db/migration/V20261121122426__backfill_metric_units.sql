-- ============================================================================
-- 补齐指标单位（用户 2026-11-21 确认：全部无单位指标一起补）
--
-- 依据：运营指标体系梳理-8个维度-补充编码.xlsx 的“指标名称/预警阈值”文本，
--       以及同小类已有单位的指标（如 EFF_GREEN_003=秒、EFF_GREEN_012=%）。
--       梳理表本身没有“单位”列，单位由名称语义 + 阈值文本中的单位后缀判定，
--       逐条依据见 doc/指标单位补齐建议清单-20261121.csv。
--
-- 范围：metric_item.unit 为空的 176 条（合规管理 104、运行效率 60、对内部人员 12）。
-- 不改：指标编码、名称、小类、阈值、告警策略、采集配置、历史数据。
-- 幂等：仅在 unit 为空时回填，已有单位不覆盖。
--
-- 待确认未回填（4 条，梳理表阈值为小数 0.05/0.1，单位口径不确定）：
--   SR_API_SLOW_RATIO / SR_PG_ACCESS_SLOW_RATIO / SR_REQ_ACCESS_SLOW_RATIO / SR_SQL_SLOW_RATIO
-- ============================================================================

START TRANSACTION;

-- 户（54 条）
UPDATE metric_item
   SET unit = '户', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
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

-- 个（47 条）
UPDATE metric_item
   SET unit = '个', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
   AND metric_code IN (
    'BOSS02',
    'BOSS03',
    'BOSS08',
    'BOSS105',
    'BOSS106',
    'BOSS107',
    'BOSS111',
    'BOSS113',
    'BOSS114',
    'BOSS116',
    'BOSS117',
    'BOSS118',
    'BOSS121',
    'BOSS122',
    'BOSS123',
    'BOSS128',
    'BOSS133',
    'BOSS136',
    'BOSS148',
    'BOSS15',
    'BOSS154',
    'BOSS34',
    'BOSS38',
    'BOSS39',
    'BOSS40',
    'BOSS41',
    'BOSS44',
    'BOSS46',
    'BOSS48',
    'BOSS52',
    'F162',
    'F169',
    'IT_SR_005',
    'IT_SR_006',
    'IT_SR_010',
    'ZT_ZQ_002',
    'ZT_ZQ_004',
    'ZT_ZQ_005',
    'ZT_ZQ_006',
    'ZT_ZQ_007',
    'ZT_ZQ_008',
    'ZT_ZW_015',
    'INNER_CHBN_BUTTON_COUNT',
    'INNER_CRM_BUTTON_COUNT',
    'error_return_code_count',
    'SR_PG_LOAD_SLOW_CNT',
    'SR_REQ_SLOW_CNT');

-- %（25 条）
UPDATE metric_item
   SET unit = '%', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
   AND metric_code IN (
    'BOSS09',
    'BOSS29',
    'EFF_GREEN_013',
    'EFF_GREEN_014',
    'EFF_GREEN_015',
    'EFF_GREEN_016',
    'EFF_GREEN_017',
    'EFF_GREEN_018',
    'EFF_GREEN_020',
    'EFF_GREEN_024',
    'EFF_GREEN_025',
    'EFF_GREEN_026',
    'EFF_GREEN_027',
    'EFF_GREEN_033',
    'EFF_GREEN_035',
    'EFF_GREEN_041',
    'EFF_GREEN_042',
    'EFF_GREEN_043',
    'EFF_GREEN_049',
    'EFF_GREEN_050',
    'EFF_GREEN_051',
    'EFF_GREEN_052',
    'EFF_GREEN_053',
    'error_interface_overall_rate',
    'error_interface_success_rate');

-- 条（2 条）
UPDATE metric_item
   SET unit = '条', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
   AND metric_code IN (
    'BOSS103',
    'BOSS142');

-- 元（5 条）
UPDATE metric_item
   SET unit = '元', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
   AND metric_code IN (
    'BOSS145',
    'ZT_ZQ_001',
    'ZT_ZQ_003',
    'ZT_ZW_020',
    'EFF_GREEN_029');

-- 秒（32 条）
UPDATE metric_item
   SET unit = '秒', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
   AND metric_code IN (
    'INNER_CHBN_FULL_DURATION',
    'INNER_CHBN_PAGE_LOAD',
    'INNER_CHBN_STEP_INTERVAL',
    'INNER_CHBN_STEP_RESPONSE',
    'INNER_CRM_FULL_DURATION',
    'INNER_CRM_PAGE_LOAD',
    'INNER_CRM_STEP_INTERVAL',
    'INNER_CRM_STEP_RESPONSE',
    'INNER_QUERY_DURATION',
    'INNER_QUERY_PAGE_LOAD',
    'EFF_GREEN_004',
    'EFF_GREEN_005',
    'EFF_GREEN_006',
    'EFF_GREEN_007',
    'EFF_GREEN_008',
    'EFF_GREEN_009',
    'EFF_GREEN_011',
    'EFF_GREEN_021',
    'EFF_GREEN_022',
    'EFF_GREEN_023',
    'EFF_GREEN_030',
    'EFF_GREEN_031',
    'EFF_GREEN_032',
    'EFF_GREEN_037',
    'EFF_GREEN_038',
    'EFF_GREEN_039',
    'EFF_GREEN_044',
    'EFF_GREEN_045',
    'EFF_GREEN_046',
    'EFF_GREEN_047',
    'EFF_GREEN_048',
    'SR_APP_FULLGC_TIME');

-- 笔（1 条）
UPDATE metric_item
   SET unit = '笔', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
   AND metric_code IN (
    'EFF_GREEN_028');

-- 次（4 条）
UPDATE metric_item
   SET unit = '次', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
   AND metric_code IN (
    'EFF_GREEN_205',
    'error_instance_count',
    'error_interface_failure_count',
    'error_js_single_count');

-- 分钟（1 条）
UPDATE metric_item
   SET unit = '分钟', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
   AND metric_code IN (
    'EFF_GREEN_206');

-- 类（2 条）
UPDATE metric_item
   SET unit = '类', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
   AND metric_code IN (
    'error_biz_return_count',
    'error_js_type_count');

-- 毫秒（3 条）
UPDATE metric_item
   SET unit = '毫秒', update_time = NOW(6)
 WHERE (unit IS NULL OR TRIM(unit) = '')
   AND metric_code IN (
    'SR_API_RESP_TIME',
    'SR_APP_RESP_TIME',
    'SR_SQL_RESP_TIME');

COMMIT;
