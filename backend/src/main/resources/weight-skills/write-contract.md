# 指标平台 MySQL 中转写入契约

通过 Star Agent 已配置的数据库 MCP 连接目标项目。使用绑定参数和受限身份。允许读取本 Skill 用到的指标目录、历史和中转记录，只向以下两张中转表的列级白名单插入。数据库连接凭据由 MCP 提供。没有事务写入工具时，输出待提交的结构化批次并停止。

## 整组快照与占比

```sql
SELECT metric_id, metric_name, definition_token, baseline_token,
       base_weight, configured_count
FROM metric_weight_review_baseline
WHERE category_id = ? AND sub_category_name <=> ?
ORDER BY metric_id;
```

- 一组是同一大类内同一小类，NULL 小类单独成组。没有启用指标、`configured_count > 1` 或原始权重不在 0 到 1 时，停止提交该组。
- `baseline` 是全部返回行的 JSON 对象，键为字符串化的 `metric_id`，值为原样的 `baseline_token`。每条建议还须保存对应行原样的 `definition_token`。
- 当前占比与平台一致。先用十进制算每个 `base_weight × 100 / Σbase_weight`。若整组权重全为零，先按成员数均分。取整数部分，剩下百分点按小数余数从大到小每项补一个，同余数取较小指标 ID。最后除以 100 入库，例如 35% 写入 `0.35`。建议也使用 0 到 1 的两位小数。
- 建议值是目标占比。先把本批所有 `business_importance` 明细作为选中项预演。风险条目始终留在未选组。用 100 减去已选百分点得到余量，按未选指标的当前原始权重分配，全部为零时均分，最后用同样的余数规则取整。若仍有正权重成员未选，余量须大于零。全组入选时须恰好合计 100%。部分批准时，平台会按实际选中项重算。

## 写入一个批次

时间以北京时间表示，数据库字段为无时区时间。月度批次使用 `period_start`（含）及 `period_end`（不含），且结束时间不得晚于生成时间。专家批次可传 NULL。建议至少有一条明细。全组无建议时仅返回跳过原因。

```sql
START TRANSACTION;

INSERT INTO metric_weight_recommendation_run (
  run_key, recommendation_type, category_id, sub_category_name,
  skill_name, skill_version, agent_model, generated_at, period_start, period_end,
  expires_at, run_status, baseline, summary, raw_analysis
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ready', ?, ?, ?);

-- 在同一连接读取 INSERT 返回的 ID，或用 SELECT LAST_INSERT_ID()。
-- 将该 ID 和以下字段绑定给每条建议的 INSERT。
INSERT INTO metric_weight_recommendation (
  run_id, metric_id, metric_name, definition_token, current_share, suggested_share,
  assessment, reason, evidence, quality
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

COMMIT;
```

- 类型使用 `expert_base` 或 `monthly_dynamic`，评估类型使用 `business_importance` 或 `current_risk`。后者只供人工查看，建议占比与当前占比相同。审核字段由平台填充。
- 以建议类型、真实组键、周期、Skill 版本及有序快照生成 ASCII `run_key`。例如将这些输入标准化并计算 SHA-256，然后使用 `expert_base:<hash>`。相同输入重试必须使用同一键。批次键冲突时回滚，读取并确认已有记录，返回已有 ID。禁止用更新语句覆盖原建议。每个批次内每个指标最多一条建议。
- 若原键已对应过期或快照失效的待审批次，或用户明确要求重新生成已驳回批次，使用原输入加被替代批次的 ID 计算新键。重复运行从原键沿替代关系查找，遇到有效批次返回其 ID；遇到并发冲突重新读取。保留原记录，在新批次分析中标记替代关系。没有重提要求时，已驳回建议保留原审核结果。
- `baseline` 和 `evidence` 用有效 JSON 作为绑定参数。证据示例为 `{"sources":[],"data_gaps":[],"definition":{},"calculation":{}}`，用查询实测结果和可查业务资料替换示例内容。没有可靠质量量化时，将 `quality` 留空。
- 任一明细写入失败时整批回滚。提交后返回批次 ID、指标组、建议条数、统计时间和证据缺口。平台管理员通过「指标体系配置 → 检查 AI 权重建议」审核。Skill 到中转写入为止。
