# 指标目录与增量筛选

以 `metric_weight_review_baseline` 为目录入口。用 `category_id` 和 `sub_category_name` 确定小类，以 `metric_id` 关联数据。小类名为 NULL 时仍是一组。保留组内所有启用指标的 `baseline_token`，审批将逐项核对。

```sql
SELECT v.*, c.category_name
FROM metric_weight_review_baseline v
JOIN metric_category c ON c.id = v.category_id
WHERE (? IS NULL OR v.system_id = ?)
ORDER BY v.category_id, v.sub_category_name, v.metric_id;
```

每个占位符都通过 MCP 参数绑定。读取已审核与在途明细时，只查该大类及小类。

```sql
SELECT d.metric_id, d.definition_token, d.review_status, d.approved_share,
       d.reason, d.evidence, r.generated_at, r.expires_at, r.id AS run_id,
       COALESCE((
         SELECT JSON_EXTRACT(wv.weights_after, '$.tokens')
         FROM metric_weight_version wv
         WHERE wv.run_id = r.id ORDER BY wv.id DESC LIMIT 1
       ), r.baseline) AS review_baseline
FROM metric_weight_recommendation d
JOIN metric_weight_recommendation_run r ON r.id = d.run_id
WHERE r.recommendation_type = 'expert_base' AND r.run_status = 'ready'
  AND r.run_key NOT LIKE 'demo:%' AND r.run_key NOT LIKE 'acceptance:%'
  AND r.category_id = ? AND r.sub_category_name <=> ?
  AND d.review_status IN ('applied', 'pending', 'rejected')
ORDER BY d.metric_id, COALESCE(d.reviewed_at, r.generated_at) DESC, d.id DESC;
```

每个指标以最近已应用记录为专家基线。待审核记录同时检查 `expires_at` 和 `review_baseline`，按指标 ID 和 token 比较整个 JSON 对象。相同定义的最新建议已驳回时跳过，除非用户明确要求重新生成。过期或快照失效的待审记录可重新评估，并按写入契约保留替代关系。证据中保存定义、单位、统计口径与小类快照，方便后续区分实质变更和文字修订。若现有已审核记录没有细节，逐项查业务资料，证据不足时暂缓重新赋权。
