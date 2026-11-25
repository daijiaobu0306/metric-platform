-- Demo shares and scenario presets use catalog codes, never environment-specific metric IDs.
START TRANSACTION;

SET @weight_seed_time = UTC_TIMESTAMP(6) + INTERVAL 8 HOUR;

INSERT INTO metric_weight_recommendation_run (
    run_key, recommendation_type, category_id, sub_category_name, skill_name, skill_version,
    generated_at, expires_at, run_status, baseline, summary, raw_analysis
)
SELECT 'demo:expert-base:pay-active:v1', 'expert_base', v.category_id, v.sub_category_name,
       '指标平台-专家基础权重（演示）', 'demo-v1',
       @weight_seed_time, @weight_seed_time + INTERVAL 365 DAY, 'ready',
       JSON_OBJECTAGG(CAST(v.metric_id AS CHAR), v.baseline_token),
       '演示如何查看权重差异、展开证据和预览整组变化。',
       '此批次使用真实指标目录和当前基础占比。建议值及业务理由均为演示样例，未使用实际告警或运营数据，仅供界面预览。'
FROM metric_weight_review_baseline v
JOIN metric_category c ON c.id = v.category_id
WHERE c.category_code = 'external_customer' AND v.sub_category_code = 'payActive'
  AND NOT EXISTS (
      SELECT 1 FROM metric_weight_recommendation_run WHERE run_key = 'demo:expert-base:pay-active:v1'
  )
GROUP BY v.system_id, v.category_id, v.sub_category_name
HAVING COUNT(*) = 5 AND COUNT(DISTINCT v.metric_code) = 5
   AND SUM(v.metric_code IN ('payCreditAvgTime', 'credit1minTimelyRate', 'accountCheckDiffRate',
                             'creditStartOrderAvgTime', 'handle1minTimelyRate')) = 5
   AND MAX(v.configured_count) <= 1 AND MIN(v.base_weight) >= 0 AND MAX(v.base_weight) <= 1
ORDER BY v.system_id, v.category_id
LIMIT 1;

SET @weight_demo_run = IF(ROW_COUNT() = 1, LAST_INSERT_ID(), NULL);

INSERT INTO metric_weight_recommendation (
    run_id, metric_id, metric_name, definition_token, current_share, suggested_share,
    assessment, reason, evidence, quality
)
WITH members AS (
    SELECT v.*, CAST(v.base_weight * 100 AS SIGNED) AS units
    FROM metric_weight_review_baseline v
    JOIN metric_weight_recommendation_run r
      ON r.category_id = v.category_id AND r.sub_category_name <=> v.sub_category_name
    WHERE r.id = @weight_demo_run AND v.sub_category_code = 'payActive'
), totals AS (
    SELECT members.*, SUM(units) OVER () AS total_units, COUNT(*) OVER () AS member_count
    FROM members
), fractions AS (
    SELECT totals.*,
           IF(total_units = 0, 100, units * 100) AS numerator,
           IF(total_units = 0, member_count, total_units) AS denominator
    FROM totals
), ranked AS (
    SELECT fractions.*,
           numerator DIV denominator AS rounded_down,
           ROW_NUMBER() OVER (ORDER BY MOD(numerator, denominator) DESC, metric_id) AS remainder_rank
    FROM fractions
), shares AS (
    SELECT ranked.*, (rounded_down + (remainder_rank <= 100 - SUM(rounded_down) OVER ())) / 100 AS current_share
    FROM ranked
)
SELECT @weight_demo_run, metric_id, metric_name, definition_token, current_share,
       CASE metric_code
           WHEN 'payCreditAvgTime' THEN 0.30
           WHEN 'credit1minTimelyRate' THEN 0.25
           ELSE 0.15
       END,
       'business_importance',
       CASE metric_code
           WHEN 'payCreditAvgTime' THEN '演示设定：客户缴费后的到账速度与服务体验直接相关，建议优先关注。'
           WHEN 'credit1minTimelyRate' THEN '演示设定：关注是否在一分钟内完成到账，建议提高占比。'
           WHEN 'accountCheckDiffRate' THEN '演示设定：保留对账准确性的独立观察，在本示例中适度下调。'
           WHEN 'creditStartOrderAvgTime' THEN '演示设定：此项反映后续管控开通链路，在本示例中保留跟踪。'
           ELSE '演示设定：保留一分钟处理及时性的观察，在本示例中给到账指标分配更多占比。'
       END,
       JSON_OBJECT('演示', TRUE, '业务组', sub_category_name, '指标编码', metric_code,
                   '依据', '用于检查页面展示的人工构造示例'),
       '界面演示'
FROM shares ORDER BY metric_id;

INSERT INTO metric_weight_scenario (
    revision, name, enabled, priority, schedule_type, starts_on, ends_on, metric_ids, multiplier
)
SELECT 1, '国庆稳定保障', FALSE, 100, 'annual_range', '2000-10-01', '2000-10-07',
       JSON_ARRAYAGG(metric_id), 1.25
FROM (
    SELECT DISTINCT v.metric_id
    FROM metric_weight_review_baseline v JOIN metric_category c ON c.id = v.category_id
    WHERE c.category_code = 'system_run'
      AND v.metric_code IN ('error_interface_failure_count', 'error_interface_success_rate',
                            'error_instance_count', 'SR_API_SLOW_RATIO')
) targets
HAVING COUNT(*) > 0
  AND NOT EXISTS (SELECT 1 FROM metric_weight_scenario WHERE name = '国庆稳定保障')
  AND NOT EXISTS (
      SELECT 1 FROM metric_weight_scenario_revision
      WHERE JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.presetKey')) = 'national_day_stability'
  );

SET @weight_national_scenario = IF(ROW_COUNT() = 1, LAST_INSERT_ID(), NULL);

INSERT INTO metric_weight_scenario (
    revision, name, enabled, priority, schedule_type, first_days, last_days, metric_ids, multiplier
)
SELECT 1, '月初月末忙时', FALSE, 50, 'month_edges', 5, 5,
       JSON_ARRAYAGG(metric_id), 1.20
FROM (
    SELECT DISTINCT v.metric_id
    FROM metric_weight_review_baseline v JOIN metric_category c ON c.id = v.category_id
    WHERE c.category_code = 'system_run'
      AND v.metric_code IN ('EFF_GREEN_003', 'EFF_GREEN_005', 'EFF_GREEN_007',
                            'EFF_GREEN_021', 'EFF_GREEN_022')
) targets
HAVING COUNT(*) > 0
  AND NOT EXISTS (SELECT 1 FROM metric_weight_scenario WHERE name = '月初月末忙时')
  AND NOT EXISTS (
      SELECT 1 FROM metric_weight_scenario_revision
      WHERE JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.presetKey')) = 'month_edges_experience'
  );

SET @weight_busy_scenario = IF(ROW_COUNT() = 1, LAST_INSERT_ID(), NULL);

INSERT INTO metric_weight_scenario_revision (scenario_id, revision, operator_id, changed_at, snapshot)
SELECT id, revision, 0, @weight_seed_time,
       JSON_OBJECT(
           'presetKey', IF(id = @weight_national_scenario, 'national_day_stability', 'month_edges_experience'),
           'name', name, 'enabled', FALSE, 'priority', priority, 'scheduleType', schedule_type,
           'startsOn', starts_on, 'endsOn', ends_on, 'firstDays', first_days, 'lastDays', last_days,
           'metricIds', metric_ids, 'multiplier', multiplier
       )
FROM metric_weight_scenario
WHERE id IN (@weight_national_scenario, @weight_busy_scenario);

COMMIT;
