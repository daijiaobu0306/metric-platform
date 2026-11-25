-- Persistent worker leases; no existing metric rules or source tasks are modified.
-- Keep ordering of same-business-time revisions within the same second.
ALTER TABLE collection_file MODIFY COLUMN collected_at DATETIME(6) NOT NULL;
CREATE TABLE IF NOT EXISTS collection_worker_run (
 id VARCHAR(36) PRIMARY KEY,
 source_code VARCHAR(50) NOT NULL,
 worker_id VARCHAR(100) NOT NULL,
 lease_token VARCHAR(36) NOT NULL,
 state VARCHAR(20) NOT NULL,
 config_json JSON NOT NULL,
 config_hash CHAR(64) NOT NULL,
 script_hash CHAR(64) NOT NULL,
 lease_until DATETIME(6) NOT NULL,
 started_at DATETIME(6) NOT NULL,
 finished_at DATETIME(6) NULL,
 last_error TEXT NULL,
 KEY idx_worker_source_state (source_code,state,lease_until),
 KEY idx_worker_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
