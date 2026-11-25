-- alarm表增加strategy_id字段，用于关联告警策略
ALTER TABLE alarm ADD COLUMN strategy_id BIGINT COMMENT '关联告警策略ID';
ALTER TABLE alarm ADD INDEX idx_strategy_id (strategy_id);
