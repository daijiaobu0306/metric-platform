CREATE TABLE IF NOT EXISTS satisfaction_report_batch (
  id CHAR(36) PRIMARY KEY,
  file_hash CHAR(64) NOT NULL,
  source_file VARCHAR(200) NOT NULL,
  business_date DATE NOT NULL,
  date_basis VARCHAR(200) NOT NULL,
  imported_at DATETIME NOT NULL,
  UNIQUE KEY uk_satisfaction_report_file_date (file_hash, business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS satisfaction_report_raw (
  batch_id CHAR(36) NOT NULL,
  source_type VARCHAR(100) NOT NULL,
  source_row INT NOT NULL,
  metric_name VARCHAR(100) NOT NULL,
  raw_text VARCHAR(100) NOT NULL,
  metric_value DECIMAL(18,4) NULL,
  unit VARCHAR(20) NOT NULL,
  source_rule TEXT,
  PRIMARY KEY (batch_id, source_type, metric_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS satisfaction_report_result (
  batch_id CHAR(36) NOT NULL,
  metric_code VARCHAR(60) NOT NULL,
  source_row INT NOT NULL,
  group_name VARCHAR(100) NOT NULL,
  source_scope VARCHAR(500) NOT NULL,
  metric_name VARCHAR(100) NOT NULL,
  metric_value DECIMAL(18,4) NULL,
  unit VARCHAR(20) NOT NULL,
  value_status VARCHAR(30) NOT NULL,
  calculation_note TEXT,
  source_cell VARCHAR(100) NOT NULL,
  definition TEXT,
  statistical_rule TEXT,
  threshold_note TEXT,
  PRIMARY KEY (batch_id, metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
