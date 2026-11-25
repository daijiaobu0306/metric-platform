# 月度数据读取口径

先通过 `metric_weight_review_baseline` 找到启用指标和整组基础快照。按 `metric_id` 查询下面的数据。所有时间以北京时间为准，查询用参数绑定。

## 健康度与告警

```sql
WITH ranked AS (
  SELECT id, metric_id, current_value, health_score, status, record_time, data_time,
         ROW_NUMBER() OVER (
           PARTITION BY metric_id, COALESCE(data_time, record_time)
           ORDER BY record_time DESC, id DESC
         ) AS revision
  FROM metric_health_history
  WHERE metric_id = ?
    AND COALESCE(data_time, record_time) >= ?
    AND COALESCE(data_time, record_time) < ?
)
SELECT metric_id, current_value, health_score, status, record_time, data_time
FROM ranked WHERE revision = 1 ORDER BY COALESCE(data_time, record_time);
```

保留零值。记录用记录时间替代数据时间的数量。只采值的指标可能完全没有健康度历史，此时从平台的采集数据口径读值。单独的 `metric_realtime` 行仅是最新快照，无法代表整月。

```sql
SELECT id, strategy_id, alarm_level, alarm_type, alarm_time,
       process_status, process_time, shield_status
FROM alarm
WHERE metric_id = ? AND alarm_time >= ? AND alarm_time < ?
ORDER BY alarm_time, id;
```

优先使用实际业务事件键去重。没有可信事件键时，保留告警记录数和策略口径，分开报告已处理时长与未处理持续时间。结合屏蔽状态解释数据，禁止自行设置 SLA 或由处理速度推断重要性。

## 文件采集指标

若连接器可调用平台接口，使用 `GET /api/report/weekly/observations`，通过网关时沿用 `/metric-code` 前缀。传入真实 `metricId`、`startDate`、`endDate`、`page`、`size`，每页最多 100 条。接口含起止两日，所以 `endDate` 使用下月首日的前一天。以响应中的 `validValue` 和 `conflict` 识别有效数据。这一接口沿用项目的 `MetricPeriodQueryService.observations` 数据口径。它按指标身份验证、采集来源、数据时间与批次顺序选择有效记录。原始来源是 `collection_row.data_json` 与 `collection_file`，满意度另有 `satisfaction_report_result` 和 `satisfaction_report_batch`。直接通过数据库取数时，需用 `MetricPeriodQueryService` 和 `CollectedMetricService` 已有查询口径核实指标编码和大类来源，不能只按名称或不带大类的编码拼接。仅能调用数据库 MCP 时，下面的查询覆盖带标准大类标记的采集行。先检查该大类编码和指标编码的组合在启用目录中唯一。已有值但来源或单位不一致时记为冲突，勿改写原数据。没有大类标记的旧批次须经平台接口或可信来源配置另行核实。


```sql
WITH scoped AS (
  SELECT m.id, m.metric_code, m.metric_name, m.unit, m.sub_category_name,
         c.category_code, c.category_name
  FROM metric_item m JOIN metric_category c ON c.id = m.category_id
  WHERE m.id = ? AND m.status = 1
    AND NOT EXISTS (
      SELECT 1 FROM metric_item other JOIN metric_category oc ON oc.id = other.category_id
      WHERE other.id <> m.id AND other.status = 1
        AND BINARY oc.category_code = BINARY c.category_code
        AND BINARY other.metric_code = BINARY m.metric_code
    )
), observations AS (
  SELECT CAST(REPLACE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.data_time')), 'null'),
                      'T', ' ') AS DATETIME) AS data_time,
         NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.metric_value')), 'null') AS metric_value,
         f.id AS batch_id, f.collected_at, f.source_code, r.data_row_number,
         CASE WHEN
           (JSON_EXTRACT(r.data_json, '$.metric_name') IS NOT NULL
             AND BINARY JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.metric_name')) <> BINARY m.metric_name)
           OR (JSON_EXTRACT(r.data_json, '$.unit') IS NOT NULL
             AND NOT (BINARY NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.unit')), 'null') <=> BINARY m.unit))
           OR (JSON_EXTRACT(r.data_json, '$.category_name') IS NOT NULL
             AND BINARY JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.category_name')) <> BINARY m.category_name)
           OR (JSON_EXTRACT(r.data_json, '$.sub_category_name') IS NOT NULL
             AND NOT (BINARY NULLIF(JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.sub_category_name')), 'null')
                      <=> BINARY m.sub_category_name))
         THEN 1 ELSE 0 END AS conflict
  FROM collection_row r
  JOIN collection_file f ON f.id = r.batch_id
  JOIN scoped m ON BINARY JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$._collection.category_code'))
                   = BINARY m.category_code
    AND (BINARY JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.metric_code')) = BINARY m.metric_code
         OR (BINARY m.category_code = BINARY 'businessValue'
             AND BINARY m.metric_code = BINARY 'importantAlarmRatio'
             AND BINARY JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.metric_code')) = BINARY 'importantAlarmRate'))
), ranked AS (
  SELECT *, ROW_NUMBER() OVER (
      PARTITION BY data_time ORDER BY collected_at DESC, batch_id DESC, data_row_number DESC
    ) AS revision
  FROM observations WHERE data_time >= ? AND data_time < ?
)
SELECT data_time, metric_value, source_code, batch_id, data_row_number, conflict
FROM ranked WHERE revision = 1 ORDER BY data_time;
```

对返回值按十进制格式校验，保留有效零值。仅在指标归属于满意度专用报表、且指标编码在该来源目录唯一时，才按相同编码从 `satisfaction_report_result` 联接 `satisfaction_report_batch` 的 `id = batch_id` 读取 `business_date`、`metric_value`、`value_status`、`source_row`。核对名称和单位，按业务日期取 `imported_at`、批次 ID、来源行号最新的修订。其余无法确认的数据记录为缺口。

保留型指标可按 `metric_value_history.metric_id`、`data_time` 查询 `metric_value`、`unit` 和 `record_time`。若同指标另有健康度记录，按数据时间核对后分开统计值与健康度，避免把同一观测重复计数。
