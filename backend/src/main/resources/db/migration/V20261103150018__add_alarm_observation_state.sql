-- Additive state only. No backfill from historical scan counts or changes to thresholds/data.
CREATE TABLE IF NOT EXISTS alarm_observation_state (
 strategy_id BIGINT NOT NULL,
 scope_key VARCHAR(100) NOT NULL,
 state_json LONGTEXT NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(strategy_id,scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
