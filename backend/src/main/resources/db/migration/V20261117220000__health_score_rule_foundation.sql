-- =============================================================================
-- 健康度算法改造 P1：指标健康度规则表 + 变更审计表 + 初始规则种子
--
-- 背景：2026-11-17 需求对齐（doc/健康度算法重构需求与设计.md）。
-- 原算分是三段硬编码（系统性能/系统异常/系统稽核）+ 通用阈值公式，
-- 白名单、方向、基线全部不可配置。本迁移建立唯一规则表 metric_health_rule，
-- 一个指标一行，三态（不计分 / 人工基线 / 动态基线）由规则自动推导：
--   exempt=1                        → 不计分（白名单）
--   baseline_status='confirmed' 且有 baseline_value → 人工基线
--   其余                            → 动态基线（近 window_days 天稳健统计）
--
-- 初始种子只做两件事：
--   1) 稽核类（合规管理 34 个）与数量类中性指标直接 exempt=1（业务确认口径）；
--   2) 把历史 metric_score_baseline 的值与方向迁进来，但标记
--      baseline_status='suggested'（历史上未逐条业务确认，不能直接当正式基线），
--      确认前不参与算分，页面点「采纳」后才生效。
--
-- 幂等性：INSERT ... SELECT 使用 NOT EXISTS 保护，重复执行不会造重复行。
-- =============================================================================

-- 1. 指标健康度规则表（一指标一行）
CREATE TABLE metric_health_rule (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  metric_id       BIGINT       NOT NULL COMMENT '指标 ID，指向 metric_item.id',
  exempt          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=白名单不计分，不参与任何层聚合',
  exempt_reason   VARCHAR(200) NULL COMMENT '不计分原因（必填，便于审计复盘）',
  direction       VARCHAR(20)  NOT NULL DEFAULT 'lower_better'
                  COMMENT 'lower_better/higher_better/range/zero_tolerance，变差方向',
  direction_source VARCHAR(20) NOT NULL DEFAULT 'auto' COMMENT 'auto=系统推导，manual=人工确认',
  baseline_value  DECIMAL(15,4) NULL COMMENT '人工基线；为空走动态基线',
  tolerance       DECIMAL(15,4) NOT NULL DEFAULT 0 COMMENT '宽容区，此范围内不扣分',
  penalty_value   DECIMAL(15,4) NULL COMMENT '扣到 0 分对应的值（人工基线必填）',
  range_min       DECIMAL(15,4) NULL COMMENT '区间型目标区间下限',
  range_max       DECIMAL(15,4) NULL COMMENT '区间型目标区间上限',
  tiers           JSON         NULL COMMENT '扣分档位表，零容忍/分段扣分用（页面可配，不写死代码）',
  window_days     INT          NOT NULL DEFAULT 90 COMMENT '动态基线窗口天数',
  min_samples     INT          NOT NULL DEFAULT 30 COMMENT '动态基线冷启动最少样本数',
  seasonality     VARCHAR(20)  NOT NULL DEFAULT 'none' COMMENT 'none/workday_weekend/weekday',
  baseline_status VARCHAR(20)  NOT NULL DEFAULT 'none'
                  COMMENT 'none=无建议；suggested=建议值待确认（不参与算分）；confirmed=已确认',
  status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
  version         INT          NOT NULL DEFAULT 1 COMMENT '规则版本，每次变更 +1，用于趋势断点',
  updated_by      VARCHAR(50)  NULL COMMENT '最后操作人',
  update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_health_rule_metric (metric_id),
  KEY idx_health_rule_exempt (exempt, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标健康度规则（一指标一行，配置驱动）';

-- 2. 规则变更审计表（沿用 metric_weight_version 的做法）
CREATE TABLE metric_health_rule_version (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  scope_type     VARCHAR(20)  NOT NULL COMMENT 'rule=单指标规则 / batch=批量白名单',
  metric_id      BIGINT       NULL COMMENT 'scope_type=rule 时指向指标；批量时为空',
  source         VARCHAR(20)  NOT NULL DEFAULT 'manual' COMMENT 'manual/migration',
  operator_id    BIGINT       NULL,
  operator_name  VARCHAR(50)  NULL,
  before_json    JSON         NULL COMMENT '变更前规则（批量时为受影响指标列表）',
  after_json     JSON         NULL COMMENT '变更后规则',
  reason         VARCHAR(200) NULL COMMENT '变更原因（人工变更必填）',
  version        INT          NOT NULL DEFAULT 1 COMMENT '变更后规则版本',
  changed_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_health_rule_version_metric (metric_id, changed_at),
  KEY idx_health_rule_version_changed (changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康度规则变更审计';

-- 3. 初始规则种子：每个启用指标一行
--    方向优先级：历史基线条件（>` 表示要压住，`<` 表示要托住）> 指标名/单位启发式。
--    基线：历史 metric_score_baseline.baseline_value 原样迁入，但只标 suggested。
INSERT INTO metric_health_rule
  (metric_id, exempt, exempt_reason, direction, direction_source,
   baseline_value, tolerance, penalty_value, window_days, min_samples, seasonality,
   baseline_status, tiers, status, version, updated_by)
SELECT
  i.id,
  CASE
    WHEN c.category_name = '合规管理' THEN 1
    WHEN i.metric_type = 'count' AND (
         i.metric_name LIKE '%工单量%' OR i.metric_name LIKE '%累计量%' OR i.metric_name LIKE '%告警量%'
         OR i.metric_name LIKE '%问题数量%' OR i.metric_name LIKE '%指标数量%' OR i.metric_name LIKE '%维度数量%'
         OR i.metric_name LIKE '%纳入数量%' OR i.metric_name LIKE '%需求数量%')
      THEN 1
    ELSE 0
  END,
  CASE
    WHEN c.category_name = '合规管理' THEN '稽核类为合规结果清单，不计健康分（2026-11-17 业务确认）'
    WHEN i.metric_type = 'count' AND (
         i.metric_name LIKE '%工单量%' OR i.metric_name LIKE '%累计量%' OR i.metric_name LIKE '%告警量%'
         OR i.metric_name LIKE '%问题数量%' OR i.metric_name LIKE '%指标数量%' OR i.metric_name LIKE '%维度数量%'
         OR i.metric_name LIKE '%纳入数量%' OR i.metric_name LIKE '%需求数量%')
      THEN '数量类为中性业务量，不反映健康程度，不计分（2026-11-17 业务确认）'
    ELSE NULL
  END,
  CASE
    WHEN c.category_name = '合规管理' THEN 'zero_tolerance'
    WHEN b.baseline_condition = '>' AND b.baseline_value = 0 THEN 'zero_tolerance'
    WHEN b.baseline_condition = '>' THEN 'lower_better'
    WHEN b.baseline_condition IN ('<', '<=') THEN 'higher_better'
    WHEN i.metric_name LIKE '%率%' OR i.unit = '%' THEN 'higher_better'
    ELSE 'lower_better'
  END,
  'auto',
  b.baseline_value,
  0,
  CASE
    WHEN b.baseline_value IS NULL THEN NULL
    WHEN b.baseline_condition IN ('<', '<=') THEN GREATEST(b.baseline_value - 10, 0)
    ELSE GREATEST(b.baseline_value * 2, b.baseline_value + 1)
  END,
  90,
  30,
  'none',
  CASE WHEN b.baseline_value IS NULL THEN 'none' ELSE 'suggested' END,
  JSON_ARRAY(
    JSON_OBJECT('max', 0,      'score', 100),
    JSON_OBJECT('max', 1,      'score', 80),
    JSON_OBJECT('max', 5,      'score', 60),
    JSON_OBJECT('max', 20,     'score', 30),
    JSON_OBJECT('max', 100,    'score', 10),
    JSON_OBJECT('max', 100000, 'score', 0)
  ),
  1,
  1,
  'migration'
FROM metric_item i
JOIN metric_category c ON c.id = i.category_id
LEFT JOIN metric_score_baseline b ON b.metric_id = i.id
WHERE i.status = 1
  AND NOT EXISTS (SELECT 1 FROM metric_health_rule r WHERE r.metric_id = i.id);

-- 4. 算分口径配置（前后端共用，前端不再各自写 70/90）
INSERT INTO system_config (config_key, config_value, config_type, description, status)
VALUES
  ('metric.health.alarm-threshold',   '60',  'number', '健康度告警线：分数 < 该值判为 alarm（前后端共用，禁止前端另写）', 1),
  ('metric.health.warning-threshold', '80',  'number', '健康度关注线：分数 < 该值判为 warning，否则 normal', 1),
  ('metric.health.stale-days',        '7',   'number', '指标值超过该天数未更新视为「数据陈旧」，不参与算分与聚合', 1),
  ('metric.health.dynamic.min-days',  '3',   'number', '动态基线冷启动：历史样本时间跨度至少该天数，否则状态为「基线学习中」', 1),
  ('metric.health.dynamic.min-samples','30', 'number', '动态基线冷启动：历史样本数至少该值，否则状态为「基线学习中」', 1),
  ('metric.health.dynamic.mock-fallback','true','boolean','动态基线样本不足时是否允许使用样例数据（data_origin=mock）兜底；仅在样例库开启', 1),
  ('metric.health.aggregation.alarm-ratio',  '0.20', 'number', '聚合告警线：alarm 指标占比达到该值，或存在分数 < 40 的指标', 1),
  ('metric.health.aggregation.warning-ratio','0.30', 'number', '聚合关注线：warning 指标占比达到该值', 1)
ON DUPLICATE KEY UPDATE description = VALUES(description);
