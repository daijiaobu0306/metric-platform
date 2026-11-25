-- 补齐 baseline 环境缺失的基础指标体系元数据。
-- 仅补不存在的分类/小类/指标，兼容已有 staging 数据重复执行。

INSERT INTO metric_category (id, system_id, category_code, category_name, category_type, description, weight, status)
VALUES
  (8, 1, 'metric_g1', '对外部客户', 'customer', '与外部客户体验相关的指标', 1.00, 1),
  (9, 1, 'metric_g2', '对内部人员', 'staff', '与一线人员使用体验相关的指标', 1.00, 1),
  (10, 1, 'system_run', '系统运行', 'system', '系统本身的性能、异常指标', 1.00, 1),
  (11, 1, 'metric_g4', '计划执行', 'plan', '对工单、问题的处理统计指标', 1.00, 1)
ON DUPLICATE KEY UPDATE
  category_name = VALUES(category_name), description = VALUES(description), status = VALUES(status);

INSERT INTO metric_sub_category (category_id, sub_category_code, sub_category_name, description, status)
VALUES
  (8, 'jihe', '系统稽核', '来自稽核系统的统计数据', 'enabled'),
  (9, 'crm', '办理类-单业务', '老 CRM 页面简单业务', 'enabled'),
  (9, 'query', '查询类', '查询类业务指标', 'enabled'),
  (9, 'chbn', '办理类-融合业务', 'CHBN 融合业务', 'enabled'),
  (10, 'performance', '系统性能', '前端页面、后端服务性能指标', 'enabled'),
  (10, 'error', '系统异常', '前后端应用异常指标', 'enabled'),
  (11, 'gongdan', '工单', '工单处理相关指标', 'enabled'),
  (11, 'issue', '问题', '问题处理相关指标', 'enabled')
ON DUPLICATE KEY UPDATE
  sub_category_name = VALUES(sub_category_name), description = VALUES(description), status = VALUES(status);

INSERT INTO metric_item (
  category_id, sub_category_name, sub_category_code, metric_code, metric_name,
  metric_type, metric_definition, unit, data_source, collect_frequency,
  alarm_threshold, alarm_threshold_type, gongdan_threshold, gongdan_threshold_type,
  trend_direction, status, create_time, update_time
)
VALUES
  (8, '缴费开机', 'payActive', 'PAY001', '缴费上账平均时长', 'duration', '缴费工单从生成到完工的平均时长', '秒', 'CRM', '1h', 5, '>', 5, '>', 'down', 1, NOW(), NOW()),
  (8, '缴费开机', 'payActive', 'PAY002', '1分钟上账及时率', 'rate', '1分钟内完成上账的工单占比', '%', 'CRM', '1h', 99, '<', 95, '<', 'up', 1, NOW(), NOW()),
  (8, '缴费开机', 'payActive', 'PAY003', '对账差异率', 'rate', '对账差异记录占比', '%', 'CRM', '1h', 1, '>', 3, '>', 'down', 1, NOW(), NOW()),
  (8, '缴费开机', 'payActive', 'XK001', '信控开机工单处理平均时长', 'duration', '信控开机工单平均处理时长', '秒', 'CRM', '1h', 5, '>', 5, '>', 'down', 1, NOW(), NOW()),
  (8, '缴费开机', 'payActive', 'XK002', '1分钟处理及时率', 'rate', '1分钟内完成处理的工单占比', '%', 'CRM', '1h', 99, '<', 95, '<', 'up', 1, NOW(), NOW()),
  (9, '查询类', 'query', 'INNER_QUERY_PAGE_LOAD', '查询页面加载时长', 'duration', '查询页面加载平均耗时', '秒', 'inner', '1h', 3, '>', 3, '>', 'down', 1, NOW(), NOW()),
  (9, '查询类', 'query', 'INNER_QUERY_DURATION', '查询时长', 'duration', '查询请求平均耗时', '秒', 'inner', '1h', 3, '>', 3, '>', 'down', 1, NOW(), NOW()),
  (9, '办理类-单业务', 'crm', 'INNER_CRM_PAGE_LOAD', '办理页面加载时长', 'duration', '办理页面加载平均耗时', '秒', 'inner', '1h', 3, '>', 3, '>', 'down', 1, NOW(), NOW()),
  (9, '办理类-单业务', 'crm', 'INNER_CRM_STEP_INTERVAL', '办理步骤间隔耗时', 'duration', '办理步骤间隔平均耗时', '秒', 'inner', '1h', 60, '>', 60, '>', 'down', 1, NOW(), NOW()),
  (9, '办理类-单业务', 'crm', 'INNER_CRM_STEP_RESPONSE', '办理步骤响应耗时', 'duration', '办理步骤响应平均耗时', '秒', 'inner', '1h', 3, '>', 3, '>', 'down', 1, NOW(), NOW()),
  (10, '系统性能', 'performance', 'SR_API_SLOW_RATIO', '大于1s的接口访问量占比', 'rate', '响应时长大于1秒的接口访问量占比', '%', 'performance', '5m', 10, '>', 30, '>', 'down', 1, NOW(), NOW()),
  (10, '系统性能', 'performance', 'SR_SQL_SLOW_RATIO', '大于1s的SQL访问量占比', 'rate', '响应时长大于1秒的SQL访问量占比', '%', 'performance', '5m', 1, '>', 3, '>', 'down', 1, NOW(), NOW()),
  (10, '系统异常', 'error', 'error_interface_failure_count', '单个接口的失败次数', 'count', '失败次数大于阈值的接口数量', '个', 'error', '1h', 1, '>', 3, '>', 'down', 1, NOW(), NOW()),
  (10, '系统异常', 'error', 'error_interface_success_rate', '单个接口的成功率', 'rate', '成功率小于阈值的接口数量', '%', 'error', '1h', 1, '<', 3, '<', 'up', 1, NOW(), NOW()),
  (10, '系统异常', 'error', 'error_instance_count', '实例异常次数', 'count', '应用实例异常次数', '次', 'error', '1h', 1, '>', 3, '>', 'down', 1, NOW(), NOW()),
  (10, '系统异常', 'error', 'error_js_type_count', 'JS异常类型数', 'count', 'JS异常类型去重数量', '种', 'error', '1h', NULL, NULL, NULL, NULL, 'down', 1, NOW(), NOW()),
  (10, '系统异常', 'error', 'error_biz_return_count', '业务自定义返回异常', 'count', '匹配关键词的业务返回异常数', '条', 'error', '1h', 1, '>', 3, '>', 'down', 1, NOW(), NOW()),
  (10, '系统异常', 'error', 'error_return_code_count', '业务自定义返回码的个数', 'count', '业务返回码去重数量', '个', 'error', '1h', NULL, NULL, NULL, NULL, 'down', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  metric_name = VALUES(metric_name), sub_category_name = VALUES(sub_category_name),
  sub_category_code = VALUES(sub_category_code), status = VALUES(status);
