-- 将 system_run_* 和 metric_error_* 表的 data_time 字段改名为 insert_time
-- 用于统一使用入库时间字段名，与 system_jhd 和 inner_* 表保持一致

-- 1. metric_system_run_summary 表
ALTER TABLE metric_system_run_summary
    CHANGE COLUMN data_time insert_time DATETIME NOT NULL COMMENT '入库时间',
    DROP INDEX idx_data_time,
    ADD INDEX idx_insert_time (insert_time);

-- 2. metric_system_run_detail 表
ALTER TABLE metric_system_run_detail
    CHANGE COLUMN data_time insert_time DATETIME NOT NULL COMMENT '入库时间',
    DROP INDEX idx_data_time,
    ADD INDEX idx_insert_time (insert_time);

-- 3. metric_error_interface 表
ALTER TABLE metric_error_interface
    CHANGE COLUMN data_time insert_time DATETIME NOT NULL COMMENT '入库时间',
    DROP INDEX idx_data_time,
    ADD INDEX idx_insert_time (insert_time);

-- 4. metric_error_instance 表
ALTER TABLE metric_error_instance
    CHANGE COLUMN data_time insert_time DATETIME NOT NULL COMMENT '入库时间',
    DROP INDEX idx_data_time,
    ADD INDEX idx_insert_time (insert_time);

-- 5. metric_error_js 表
ALTER TABLE metric_error_js
    CHANGE COLUMN data_time insert_time DATETIME NOT NULL COMMENT '入库时间',
    DROP INDEX idx_data_time,
    ADD INDEX idx_insert_time (insert_time);

-- 6. metric_error_biz_return 表
ALTER TABLE metric_error_biz_return
    CHANGE COLUMN data_time insert_time DATETIME NOT NULL COMMENT '入库时间',
    DROP INDEX idx_data_time,
    ADD INDEX idx_insert_time (insert_time);

-- 7. metric_error_return_code 表
ALTER TABLE metric_error_return_code
    CHANGE COLUMN data_time insert_time DATETIME NOT NULL COMMENT '入库时间',
    DROP INDEX idx_data_time,
    ADD INDEX idx_insert_time (insert_time);
