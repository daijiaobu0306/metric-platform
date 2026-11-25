#!/bin/bash

echo "===== 指标体系平台 数据库初始化脚本 ====="

cd "$(dirname "$0")/.."
source config/env.conf

echo "正在创建数据库..."
mysql -h $DB_HOST -u $DB_USER -p$DB_PASSWORD < sql/init_schema.sql

if [ $? -eq 0 ]; then
    echo "===== 数据库初始化完成 ====="
else
    echo "===== 数据库初始化失败 ====="
    exit 1
fi