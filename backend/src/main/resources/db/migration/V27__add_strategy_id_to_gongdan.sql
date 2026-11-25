-- gongdan表增加strategy_id字段，用于关联告警策略
ALTER TABLE gongdan ADD COLUMN strategy_id BIGINT COMMENT '关联告警策略ID';
