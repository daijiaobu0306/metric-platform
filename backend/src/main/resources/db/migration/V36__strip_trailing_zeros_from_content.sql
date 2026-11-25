-- V36: 清理历史告警/工单内容字段的数字尾随零
--
-- 背景：
--   1) 旧版 MetricMonitorService (c414e9f) 使用 String.format("%s", bigDecimal) 拼接工单内容，
--      其中 %s 会调用 BigDecimal.toString()，完整保留 scale。当时指标的 BigDecimal 携带 scale=4，
--      导致生成的 gongdan_content 出现 "告警阈值: 1.0000 个" / "工单阈值: 3.0000 个" 等带尾随零的格式。
--      该方法已在 808e7a2 中 @Deprecated 化（方法体为空），新数据不会再产生该格式。
--   2) 现存 gongdan 表的 4 行、alarm 表的 176 行仍带尾随零。
--   3) AlertGenerateService 中 4 处 toPlainString() 调用已统一改为 stripTrailingZeros().toPlainString()，
--      根因 2 已被修复（未来新数据不再产生尾随零），本迁移仅清理历史数据。
--
-- 模式：([0-9]+)\.0+
--   - 匹配整数 + 小数点 + 至少一个 0
--   - 替换为整数部分（去掉 ".0000" 等零后缀）
--   - 不会误伤 "1.5" 这种有非零小数位的真小数（没有 0+ 结尾）
--   - 不会误伤日期/IP 等其他数字（这些场景没有 ".0+" 形态）

-- 工单内容清理：4 行受影响
UPDATE gongdan
SET gongdan_content = REGEXP_REPLACE(gongdan_content, '([0-9]+)\\.0+', '$1')
WHERE gongdan_content REGEXP '[0-9]\\.0+';

-- 告警内容清理：176 行受影响
UPDATE alarm
SET alarm_content = REGEXP_REPLACE(alarm_content, '([0-9]+)\\.0+', '$1')
WHERE alarm_content REGEXP '[0-9]\\.0+';
