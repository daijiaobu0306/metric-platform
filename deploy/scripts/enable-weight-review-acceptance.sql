-- Explicit development/test setup. This converts the untouched catalog-backed
-- fixture into a normal review batch. Run only against the intended test database.
-- The script itself never applies weights or creates a weight version.
START TRANSACTION;

SET @acceptance_now = UTC_TIMESTAMP(6) + INTERVAL 8 HOUR;
SET @acceptance_run = (
    SELECT id FROM metric_weight_recommendation_run
    WHERE run_key = 'demo:expert-base:pay-active:v1'
);

UPDATE metric_weight_recommendation_run r
SET r.run_key = 'acceptance:expert-base:pay-active:v1',
    r.skill_name = '开发验收（人工录入）',
    r.skill_version = 'acceptance-v1',
    r.generated_at = @acceptance_now,
    r.expires_at = @acceptance_now + INTERVAL 30 DAY,
    r.summary = '基于缴费开通的现有指标和基础占比录入。可验证审批结果、待审数量和生效权重版本。',
    r.raw_analysis = '建议优先关注缴费到账平均时长和一分钟到账及时率。批准时使用该小类的现行权重配置，审核通过后实际写入权重和版本。'
WHERE r.id = @acceptance_run
  AND r.run_key = 'demo:expert-base:pay-active:v1'
  AND r.run_status = 'ready'
  AND r.baseline = (
      SELECT JSON_OBJECTAGG(CAST(v.metric_id AS CHAR), v.baseline_token)
      FROM metric_weight_review_baseline v
      WHERE v.category_id = r.category_id AND v.sub_category_name <=> r.sub_category_name
  )
  AND (SELECT COUNT(*) FROM metric_weight_recommendation d WHERE d.run_id = r.id) = 5
  AND NOT EXISTS (
      SELECT 1 FROM metric_weight_recommendation d
      WHERE d.run_id = r.id AND d.review_status <> 'pending'
  );

SET @acceptance_changed = ROW_COUNT();

UPDATE metric_weight_recommendation d
JOIN metric_weight_review_baseline v ON v.metric_id = d.metric_id
SET d.reason = CASE v.metric_code
        WHEN 'payCreditAvgTime' THEN '缴费到账平均时长反映客户缴费后实际等待时间，建议提高到 30%。'
        WHEN 'credit1minTimelyRate' THEN '一分钟内到账的比例反映及时性，建议提高到 25%。'
        WHEN 'accountCheckDiffRate' THEN '对账差异率反映对账准确性，保留 15% 的占比。'
        WHEN 'creditStartOrderAvgTime' THEN '管控开通工单处理平均时长反映到账后的处理链路，建议保留 15% 的占比。'
        ELSE '一分钟处理及时率用于观察处理环节是否及时，建议保留 15% 的占比。'
    END,
    d.evidence = JSON_OBJECT('来源', '开发验收人工录入', '指标编码', v.metric_code,
                             '业务小类', v.sub_category_name, '指标定义', v.metric_definition,
                             '生成时基础占比', d.current_share, '建议占比', d.suggested_share),
    d.quality = '开发验收'
WHERE @acceptance_changed = 1 AND d.run_id = @acceptance_run;

COMMIT;

SELECT @acceptance_changed AS converted_batches;
