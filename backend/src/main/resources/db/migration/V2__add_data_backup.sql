-- 备份表
DROP TABLE IF EXISTS data_backup;
CREATE TABLE data_backup (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    backup_time DATETIME NOT NULL COMMENT '备份时间',
    operator VARCHAR(50) COMMENT '操作人',
    system_count INT COMMENT '系统数量',
    category_count INT COMMENT '指标大类数量',
    item_count INT COMMENT '指标数量',
    file_path VARCHAR(500) COMMENT '备份文件路径',
    description VARCHAR(500) COMMENT '描述',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据备份表';
