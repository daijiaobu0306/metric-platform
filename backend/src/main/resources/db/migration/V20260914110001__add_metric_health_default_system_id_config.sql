-- V20260914110001: 新增 system_config 配置项 metric.health.default-system-id
--
-- 背景：
--   ObservationController.getSystemHealthScore 和 HealthSnapshotAggregationService.aggregateSystemLevelToHourly
--   把 systemId 硬编码成 8L（WeightConfigRepository.findBySystemIdAndWeightType(8L, "category")）。
--   这是历史数据 drift 导致的"取巧"，不是业务需求 —— weight_config 表里 121 行 system_id=NULL，
--   只有 4 行 category 类型 weight 显式 system_id=8，所以硬编码 8 才能取到数据。
--
-- 修复：
--   把硬编码的 systemId 改为读 system_config 配置项，支持运维侧动态调整。
--   默认值 = 1L（system_info 表里 CRM 系统的 system_id=1，符合业务现状）。
--
-- 幂等：
--   使用 INSERT ... ON DUPLICATE KEY UPDATE id=id（与 V56 一致）。
--   已有值的环境不会被覆盖（description 更新除外）。

INSERT INTO system_config (config_key, config_value, config_type, description, status, create_time, update_time) VALUES
('metric.health.default-system-id', '1', 'metric', '健康度/权重默认 systemId（修复前硬编码 8L 是历史 data drift，现改为 1L = CRM system_id）', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE description = VALUES(description);
