-- Simulation-only records are separate from production metric definitions/results.
CREATE TABLE IF NOT EXISTS mock_satisfaction_catalog (
  metric_code VARCHAR(50) PRIMARY KEY,
  source_row INT NOT NULL,
  group_name VARCHAR(100) NOT NULL,
  metric_name VARCHAR(100) NOT NULL,
  source_scope VARCHAR(500) NOT NULL,
  definition TEXT,
  statistical_rule TEXT,
  frequency VARCHAR(50) NOT NULL,
  threshold_note TEXT,
  unit VARCHAR(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mock_satisfaction_batch (
  file_hash CHAR(64) PRIMARY KEY,
  source_file VARCHAR(200) NOT NULL,
  business_date DATE NOT NULL,
  record_count INT NOT NULL,
  received_at DATETIME NOT NULL,
  imported_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mock_satisfaction_data (
  metric_code VARCHAR(50) NOT NULL,
  business_date DATE NOT NULL,
  period_kind VARCHAR(10) NOT NULL COMMENT 'DAY or MTD; never aggregate percentages by summation',
  metric_value DECIMAL(18,4) NOT NULL,
  unit VARCHAR(20) NOT NULL,
  file_hash CHAR(64) NOT NULL,
  imported_at DATETIME NOT NULL,
  PRIMARY KEY (metric_code, business_date, period_kind),
  KEY idx_mock_satisfaction_date (business_date, period_kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
