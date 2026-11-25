CREATE TABLE IF NOT EXISTS collection_source (
 code VARCHAR(50) PRIMARY KEY,
 name VARCHAR(100) NOT NULL,
 config_json JSON NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 next_scan_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_scan_at DATETIME NULL,
 last_error TEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS collection_file (
 id VARCHAR(36) PRIMARY KEY,
 source_code VARCHAR(50) NOT NULL,
 file_hash CHAR(64) NOT NULL,
 business_date DATE NOT NULL,
 source_file VARCHAR(200) NOT NULL,
 archive_file VARCHAR(500) NOT NULL,
 row_count INT NOT NULL,
 collected_at DATETIME NOT NULL,
 archived BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY uk_collection_content (source_code,file_hash,business_date),
 KEY idx_collection_source_time (source_code,collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS collection_row (
 batch_id VARCHAR(36) NOT NULL,
 data_row_number INT NOT NULL,
 data_json JSON NOT NULL,
 PRIMARY KEY (batch_id,data_row_number),
 CONSTRAINT fk_collection_row_batch FOREIGN KEY (batch_id) REFERENCES collection_file(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS collection_attempt (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 source_code VARCHAR(50) NOT NULL,
 source_file VARCHAR(200) NOT NULL,
 status VARCHAR(30) NOT NULL,
 message TEXT NULL,
 attempted_at DATETIME NOT NULL,
 KEY idx_collection_attempt (source_code,attempted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO collection_source(code,name,config_json,enabled)
VALUES ('satisfaction_report','满意度及投诉服务体验',
 JSON_OBJECT('code','satisfaction_report','name','满意度及投诉服务体验',
 'importPath','raw_data/import/satisfaction_report','archivePath','raw_data/archive/satisfaction_report',
 'intervalSeconds',60,'settleSeconds',30,'parser','satisfaction-report','format','xlsx',
 'filePattern','*.xlsx','dateMode','filename_or_sidecar','dateRegex','^([0-9]{4}-[0-9]{2}-[0-9]{2})[_-].*',
 'dateFormat','uuuu-MM-dd','enabled',true,'sheet','','delimiter',',','encoding','UTF-8','fields',JSON_ARRAY()),true)
ON DUPLICATE KEY UPDATE code=code;
