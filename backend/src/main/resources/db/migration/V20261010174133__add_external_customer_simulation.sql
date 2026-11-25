CREATE TABLE IF NOT EXISTS mock_external_customer_data (
  business_date DATE NOT NULL,
  metric_code VARCHAR(100) NOT NULL,
  sub_category_name VARCHAR(100) NOT NULL,
  metric_name VARCHAR(200) NOT NULL,
  metric_value DECIMAL(18,4) NOT NULL,
  unit VARCHAR(20) NOT NULL,
  source_file VARCHAR(200) NOT NULL,
  source_hash CHAR(64) NOT NULL,
  imported_at DATETIME NOT NULL,
  PRIMARY KEY (business_date, metric_code),
  KEY idx_mock_external_category_date (sub_category_name, business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地外部客户模拟文件采集结果，不参与正式告警';
