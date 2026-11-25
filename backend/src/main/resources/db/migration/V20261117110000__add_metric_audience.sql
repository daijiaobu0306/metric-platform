-- =============================================================================
-- 角色（面向人员）维度：指标打岗位标签
-- =============================================================================
-- 背景：验收规范要求「指标覆盖对象需面向网格下属岗位人员……服务健康度指标需
--       至少体现出其中 2 类人员的整体健康度以及分人员健康度」。
--
-- 现状：metric_item.responsible_person 是 2026-10-27 新增的自由文本列，
--       存的是「面向人员」原文；自由文本无法稳定聚合、无法做下拉过滤，
--       且实测线上 172 条指标该列全为 NULL。
--
-- 本迁移做三件事：
--   1) 新建岗位字典 metric_audience（编码 + 名称 + 排序 + 状态）；
--   2) metric_item 增加 audience_code（一个指标只挂一个岗位，与业务确认）；
--   3) 按「指标所属大类/小类」先回填一版合理岗位，供开发和演示使用；
--      正式值以业务梳理表「面向人员」列为准，页面可直接改，后续导入覆盖。
--
-- 注意：
--   * 岗位 ≠ sys_role。sys_role 是平台权限角色（决定能看哪些菜单/专屏），
--     岗位是业务标签，只用于指标分组与健康度聚合，不参与鉴权。
--   * responsible_person 保留为展示字段，回填后与 audience 名称保持一致，
--     避免出现「两个面向人员」互相矛盾。
--   * 本迁移不改历史迁移、不删数据、不做范围删除。
-- =============================================================================

-- 1) 岗位字典
CREATE TABLE IF NOT EXISTS `metric_audience` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `audience_code` VARCHAR(50)  NOT NULL COMMENT '岗位编码（稳定标识，用于聚合和过滤）',
  `audience_name` VARCHAR(100) NOT NULL COMMENT '岗位名称（页面展示）',
  `sort_order`    INT          NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
  `status`        INT          NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_metric_audience_code` (`audience_code`),
  UNIQUE KEY `uk_metric_audience_name` (`audience_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='岗位字典（面向人员）';

-- 2) 岗位种子：集团规范点名的 4 个网格岗位 + 梳理表已出现的维护/管理人员
--    命名按业务口径统一：业务维护人员/维护人员 → 维护人员（2026-11-17 用户确认）
INSERT INTO `metric_audience` (`audience_code`, `audience_name`, `sort_order`, `status`) VALUES
  ('grid_leader',   '网格长',      10, 1),
  ('salesperson',   '营业员',      20, 1),
  ('account_manager','客户经理',   30, 1),
  ('direct_sales',  '直销人员',    40, 1),
  ('maintainer',    '维护人员',    50, 1),
  ('manager',       '管理人员',    60, 1)
ON DUPLICATE KEY UPDATE
  `audience_name` = VALUES(`audience_name`),
  `sort_order`    = VALUES(`sort_order`),
  `status`        = VALUES(`status`);

-- 3) 指标表增加岗位编码
ALTER TABLE `metric_item`
  ADD COLUMN `audience_code` VARCHAR(50) NULL COMMENT '面向人员（岗位）编码，指向 metric_audience.audience_code',
  ADD KEY `idx_metric_item_audience` (`audience_code`);

-- 4) 回填岗位（按大类/小类归属，口径见 doc/角色人员维度说明.md）
--    4.1 合规管理（系统稽核）→ 维护人员
UPDATE `metric_item` i
  JOIN `metric_category` c ON c.id = i.category_id
   SET i.audience_code = 'maintainer'
 WHERE c.category_name = '合规管理';

--    4.2 对内部人员（查询类/办理类，一线人员使用体验）→ 营业员
UPDATE `metric_item` i
  JOIN `metric_category` c ON c.id = i.category_id
   SET i.audience_code = 'salesperson'
 WHERE c.category_name = '对内部人员';

--    4.3 计划执行（页面加载/响应耗时，营业侧办理）→ 管理人员
UPDATE `metric_item` i
  JOIN `metric_category` c ON c.id = i.category_id
   SET i.audience_code = 'manager'
 WHERE c.category_name = '计划执行';

--    4.4 满意度及投诉服务体验（工单流程闭环，管理人员看）→ 管理人员
UPDATE `metric_item` i
  JOIN `metric_category` c ON c.id = i.category_id
   SET i.audience_code = 'manager'
 WHERE c.category_name = '满意度及投诉服务体验';

--    4.5 业务价值：运营成效（告警/工单/问题成效）→ 网格长；报表看板 → 管理人员
UPDATE `metric_item` i
  JOIN `metric_category` c ON c.id = i.category_id
   SET i.audience_code = CASE WHEN i.sub_category_name = '报表看板' THEN 'manager' ELSE 'grid_leader' END
 WHERE c.category_name = '业务价值';

--    4.6 资源投入（需求开发）→ 管理人员
UPDATE `metric_item` i
  JOIN `metric_category` c ON c.id = i.category_id
   SET i.audience_code = 'manager'
 WHERE c.category_name = '资源投入';

--    4.7 数据质量 → 维护人员
UPDATE `metric_item` i
  JOIN `metric_category` c ON c.id = i.category_id
   SET i.audience_code = 'maintainer'
 WHERE c.category_name = '数据质量';

--    4.8 对外部客户：缴费开机 → 营业员；流量提醒（短信/提醒处理）→ 直销人员
UPDATE `metric_item` i
  JOIN `metric_category` c ON c.id = i.category_id
   SET i.audience_code = CASE WHEN i.sub_category_name = '缴费开机' THEN 'salesperson' ELSE 'direct_sales' END
 WHERE c.category_name = '对外部客户';

--    4.9 运行效率：系统性能/系统异常/对账差错 → 维护人员
UPDATE `metric_item` i
  JOIN `metric_category` c ON c.id = i.category_id
   SET i.audience_code = 'maintainer'
 WHERE c.category_name = '运行效率'
   AND (i.sub_category_name IN ('系统性能', '系统异常', '运行效率-异常指标-对账差错'));

--    4.10 运行效率：线上支付、欠费销账 → 客户经理
UPDATE `metric_item` i
  JOIN `metric_category` c ON c.id = i.category_id
   SET i.audience_code = 'account_manager'
 WHERE c.category_name = '运行效率'
   AND (i.sub_category_name LIKE '%线上支付' OR i.sub_category_name LIKE '%欠费销账');

--    4.11 运行效率：其余（渠道缴费、信控开机、功能开通、无小类）→ 营业员
UPDATE `metric_item` i
  JOIN `metric_category` c ON c.id = i.category_id
   SET i.audience_code = 'salesperson'
 WHERE c.category_name = '运行效率'
   AND i.audience_code IS NULL;

-- 5) 面向人员展示字段与岗位名称对齐（原值为空或自由文本，统一成字典名称）
UPDATE `metric_item` i
  JOIN `metric_audience` a ON a.audience_code = i.audience_code
   SET i.responsible_person = a.audience_name
 WHERE i.audience_code IS NOT NULL
   AND (i.responsible_person IS NULL OR i.responsible_person <> a.audience_name);
