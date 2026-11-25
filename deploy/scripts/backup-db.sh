#!/usr/bin/env bash
# ============================================================
# 开发环境数据库备份脚本
#   - 导出 system_operations_metrics 库全量数据（结构+数据）
#   - 生成 SQL 文件，可直接在生产 MySQL 上恢复
#
# 用法:
#   bash deploy/scripts/backup-db.sh                    # 默认本机 MySQL
#   bash deploy/scripts/backup-db.sh -h 127.0.0.1 -P 3306 -u root -p xxx
# ============================================================

set -euo pipefail

# 默认值
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_USER="root"
DB_PASS=""
DB_NAME="system_operations_metrics"

# 解析参数
while getopts "h:P:u:p:d:" opt; do
    case $opt in
        h) DB_HOST="$OPTARG" ;;
        P) DB_PORT="$OPTARG" ;;
        u) DB_USER="$OPTARG" ;;
        p) DB_PASS="$OPTARG" ;;
        d) DB_NAME="$OPTARG" ;;
        *) echo "用法: $0 [-h host] [-P port] [-u user] [-p pass] [-d dbname]"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)/deploy"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTPUT_FILE="$OUTPUT_DIR/db-backup-${TIMESTAMP}.sql"

mkdir -p "$OUTPUT_DIR"

echo "================================================"
echo " 数据库备份"
echo "================================================"
echo " 数据库:   $DB_NAME"
echo " 主机:     $DB_HOST:$DB_PORT"
echo " 用户:     $DB_USER"
echo " 输出文件: $OUTPUT_FILE"
echo "================================================"

# 构建 mysqldump 命令（用环境变量传密码，避免特殊字符被 shell 解析）
export MYSQL_PWD="$DB_PASS"
DUMP_CMD="mysqldump -h $DB_HOST -P $DB_PORT -u $DB_USER"
if [ -z "$DB_PASS" ]; then
    unset MYSQL_PWD
    DUMP_CMD="$DUMP_CMD -p"
fi

# --routines: 包含存储过程/函数
# --triggers: 包含触发器
# --single-transaction: InnoDB 一致性备份（不锁表）
# --set-gtid-purged=OFF: 避免 GTID 兼容问题
# --skip-lock-tables: 不锁表
$DUMP_CMD \
    --routines \
    --triggers \
    --single-transaction \
    --set-gtid-purged=OFF \
    --skip-lock-tables \
    --databases "$DB_NAME" \
    > "$OUTPUT_FILE"
unset MYSQL_PWD

# 压缩
gzip "$OUTPUT_FILE"
COMPRESSED="${OUTPUT_FILE}.gz"
SIZE=$(du -h "$COMPRESSED" | awk '{print $1}')

echo
echo "================================================"
echo " ✅ 备份完成"
echo "================================================"
echo " 文件: $COMPRESSED"
echo " 大小: $SIZE"
echo
echo " 传输到生产 MySQL 服务器:"
echo "   scp $COMPRESSED user@prod-mysql:/tmp/"
echo
echo " 在生产 MySQL 服务器上恢复:"
echo "   bash deploy/scripts/restore-db.sh /tmp/db-backup-${TIMESTAMP}.sql.gz"
echo "================================================"
