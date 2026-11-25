-- =============================================================================
-- 面向人员（岗位）按业务梳理表对齐
-- =============================================================================
-- 来源：运营指标体系梳理-8个维度-20260929.xlsx，各维度 sheet 的「面向人员」列（E 列）。
-- 归一规则（2026-11-17 用户确认）：
--   业务维护人员 / 维护人员            → 维护人员 maintainer
--   营业员                            → 营业员 salesperson
--   业务主管 / 服务运营人员            → 管理人员 manager
--   投诉分析人员 / 投诉指标管理人员     → 管理人员 manager
--
-- 说明：
--   * 表里「面向人员」只填了 93 行（其余为空），空值不在本迁移的覆盖范围，
--     沿用 V20261117110000 按大类/小类回填的初版口径。
--   * 按指标编码匹配；编码为空的行按指标名称匹配。表里多填的组合值
--     （如「投诉分析人员、投诉指标管理人员」）取第一个岗位。
--   * 本迁移只改数据，不改结构；重复执行结果一致（幂等）。
-- =============================================================================

UPDATE metric_item i
JOIN (
  SELECT 'payCreditAvgTime' AS k,'maintainer' AS aud
  UNION ALL SELECT 'credit1minTimelyRate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'accountCheckDiffRate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'creditStartOrderAvgTime' AS k,'maintainer' AS aud
  UNION ALL SELECT 'handle1minTimelyRate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'billCollectAvgTime' AS k,'maintainer' AS aud
  UNION ALL SELECT 'collect1minTimelyRate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'billDecodeAvgTime' AS k,'maintainer' AS aud
  UNION ALL SELECT 'decode1minTimelyRate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'billPricingAvgTime' AS k,'maintainer' AS aud
  UNION ALL SELECT 'pricing1minTimelyRate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'remindFileAvgTime' AS k,'maintainer' AS aud
  UNION ALL SELECT 'remindFile1minTimelyRate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'remindOrderAvgTime' AS k,'maintainer' AS aud
  UNION ALL SELECT 'remindOrder1minTimelyRate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'smsHandleAvgTime' AS k,'maintainer' AS aud
  UNION ALL SELECT 'smsHandle1minTimelyRate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'smsSendAvgTime' AS k,'maintainer' AS aud
  UNION ALL SELECT 'smsSend1minTimelyRate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'INNER_QUERY_PAGE_LOAD' AS k,'salesperson' AS aud
  UNION ALL SELECT 'INNER_QUERY_DURATION' AS k,'salesperson' AS aud
  UNION ALL SELECT 'INNER_CRM_PAGE_LOAD' AS k,'salesperson' AS aud
  UNION ALL SELECT 'INNER_CRM_STEP_INTERVAL' AS k,'salesperson' AS aud
  UNION ALL SELECT 'INNER_CRM_STEP_RESPONSE' AS k,'salesperson' AS aud
  UNION ALL SELECT 'INNER_CRM_BUTTON_COUNT' AS k,'salesperson' AS aud
  UNION ALL SELECT 'INNER_CRM_FULL_DURATION' AS k,'salesperson' AS aud
  UNION ALL SELECT 'INNER_CHBN_PAGE_LOAD' AS k,'salesperson' AS aud
  UNION ALL SELECT 'INNER_CHBN_STEP_INTERVAL' AS k,'salesperson' AS aud
  UNION ALL SELECT 'INNER_CHBN_STEP_RESPONSE' AS k,'salesperson' AS aud
  UNION ALL SELECT 'INNER_CHBN_BUTTON_COUNT' AS k,'salesperson' AS aud
  UNION ALL SELECT 'INNER_CHBN_FULL_DURATION' AS k,'salesperson' AS aud
  UNION ALL SELECT 'firstLineHandleTimelyRate' AS k,'manager' AS aud
  UNION ALL SELECT 'firstLineProblemSolveRate' AS k,'manager' AS aud
  UNION ALL SELECT 'thirdLineHandleTimelyRate' AS k,'manager' AS aud
  UNION ALL SELECT 'firstLineHandleAvgTime' AS k,'manager' AS aud
  UNION ALL SELECT 'greenChannelComplainAvgTime' AS k,'manager' AS aud
  UNION ALL SELECT 'urgeOrderOver2TimesComplainAvgTime' AS k,'manager' AS aud
  UNION ALL SELECT 'claimUpgradeComplainOrderHandleTime' AS k,'manager' AS aud
  UNION ALL SELECT 'helpDeskHandleTime' AS k,'manager' AS aud
  UNION ALL SELECT 'helpDeskSolveRate' AS k,'manager' AS aud
  UNION ALL SELECT 'helpDeskSupportTimelyRate' AS k,'manager' AS aud
  UNION ALL SELECT 'registrantBackRate' AS k,'manager' AS aud
  UNION ALL SELECT 'oneStopServiceAssignApiSuccessRate' AS k,'manager' AS aud
  UNION ALL SELECT 'oneStopServiceAssignApiTimeoutRate' AS k,'manager' AS aud
  UNION ALL SELECT 'serviceFirstLineAvgTime' AS k,'manager' AS aud
  UNION ALL SELECT 'serviceFirstLineTimelyRate' AS k,'manager' AS aud
  UNION ALL SELECT 'serviceComplainRepeat2TimesRate' AS k,'manager' AS aud
  UNION ALL SELECT 'serviceComplainRepeat3TimesRate' AS k,'manager' AS aud
  UNION ALL SELECT 'businessHallSatisfaction' AS k,'manager' AS aud
  UNION ALL SELECT 'govAccountManagerSatisfaction' AS k,'manager' AS aud
  UNION ALL SELECT 'onlineCompanySatisfaction' AS k,'manager' AS aud
  UNION ALL SELECT 'gridStaffSatisfaction' AS k,'manager' AS aud
  UNION ALL SELECT 'businessDeptSatisfaction' AS k,'manager' AS aud
  UNION ALL SELECT 'financeDeptSatisfaction' AS k,'manager' AS aud
  UNION ALL SELECT 'SR_PG_LOAD_SLOW_CNT' AS k,'maintainer' AS aud
  UNION ALL SELECT 'SR_PG_ACCESS_SLOW_RATIO' AS k,'maintainer' AS aud
  UNION ALL SELECT 'SR_REQ_SLOW_CNT' AS k,'maintainer' AS aud
  UNION ALL SELECT 'SR_REQ_ACCESS_SLOW_RATIO' AS k,'maintainer' AS aud
  UNION ALL SELECT 'SR_APP_RESP_TIME' AS k,'maintainer' AS aud
  UNION ALL SELECT 'SR_APP_FULLGC_TIME' AS k,'maintainer' AS aud
  UNION ALL SELECT 'SR_API_RESP_TIME' AS k,'maintainer' AS aud
  UNION ALL SELECT 'SR_SQL_RESP_TIME' AS k,'maintainer' AS aud
  UNION ALL SELECT 'SR_API_SLOW_RATIO' AS k,'maintainer' AS aud
  UNION ALL SELECT 'SR_SQL_SLOW_RATIO' AS k,'maintainer' AS aud
  UNION ALL SELECT 'error_interface_failure_count' AS k,'maintainer' AS aud
  UNION ALL SELECT 'error_interface_success_rate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'error_interface_overall_rate' AS k,'maintainer' AS aud
  UNION ALL SELECT 'error_instance_count' AS k,'maintainer' AS aud
  UNION ALL SELECT 'error_js_type_count' AS k,'maintainer' AS aud
  UNION ALL SELECT 'error_js_single_count' AS k,'maintainer' AS aud
  UNION ALL SELECT 'error_biz_return_count' AS k,'maintainer' AS aud
  UNION ALL SELECT 'error_return_code_count' AS k,'maintainer' AS aud
  UNION ALL SELECT 'alarmCount' AS k,'manager' AS aud
  UNION ALL SELECT 'alarmHandleRate' AS k,'manager' AS aud
  UNION ALL SELECT 'importantAlarmRatio' AS k,'manager' AS aud
  UNION ALL SELECT 'orderCount' AS k,'manager' AS aud
  UNION ALL SELECT 'orderCloseRate' AS k,'manager' AS aud
  UNION ALL SELECT 'problemCount' AS k,'manager' AS aud
  UNION ALL SELECT 'problemSolveRate' AS k,'manager' AS aud
  UNION ALL SELECT 'problemIndexCount' AS k,'manager' AS aud
  UNION ALL SELECT 'optimizeIndexCount' AS k,'manager' AS aud
  UNION ALL SELECT 'repeatProblemCount' AS k,'manager' AS aud
  UNION ALL SELECT 'indexIncludeCount' AS k,'manager' AS aud
  UNION ALL SELECT 'dimensionIncludeCount' AS k,'manager' AS aud
  UNION ALL SELECT 'requirementCount' AS k,'manager' AS aud
  UNION ALL SELECT 'avgRequirementDuration' AS k,'manager' AS aud
  UNION ALL SELECT 'totalRequirementDuration' AS k,'manager' AS aud
  UNION ALL SELECT 'dataVolatility' AS k,'maintainer' AS aud
  UNION ALL SELECT 'businessIndicatorAccuracy' AS k,'maintainer' AS aud
  UNION ALL SELECT 'fieldAccuracy' AS k,'maintainer' AS aud
  UNION ALL SELECT 'enumValueAbnormalTrigger' AS k,'maintainer' AS aud
  UNION ALL SELECT 'crossSystemDataTimeliness' AS k,'maintainer' AS aud
  UNION ALL SELECT 'dataConsistency' AS k,'maintainer' AS aud
) m ON BINARY m.k = BINARY i.metric_code
JOIN metric_audience a ON a.audience_code = m.aud
SET i.audience_code = a.audience_code, i.responsible_person = a.audience_name;

