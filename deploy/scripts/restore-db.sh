#!/usr/bin/env bash
# ============================================================
# 生产 MySQL 数据库恢复脚本
#   - 恢复开发环境备份到生产 MySQL（覆盖已有数据）
#   - 支持 .sql 和 .sql.gz 格式
#
# ⚠️ 警告: 此脚本会删除目标数据库中所有现有表！
#
# 用法:
#   bash restore-db.sh /tmp/db-backup-20260617.sql.gz
#   bash restore-db.sh /tmp/db-backup-20260617.sql.gz -h 192.168.1.100 -u root -p xxx
# ============================================================

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "❌ 用法: $0 <备份文件.sql或.sql.gz> [-h host] [-P port] [-u user] [-p pass]"
    exit 1
fi

BACKUP_FILE="$1"
shift

# 默认值
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_USER="root"
DB_PASS=""
DB_NAME="system_operations_metrics"

# 解析剩余参数
while getopts "h:P:u:p:" opt; do
    case $opt in
        h) DB_HOST="$OPTARG" ;;
        P) DB_PORT="$OPTARG" ;;
        u) DB_USER="$OPTARG" ;;
        p) DB_PASS="$OPTARG" ;;
        *) echo "用法: $0 <备份文件> [-h host] [-P port] [-u user] [-p pass]"; exit 1 ;;
    esac
done

# 检查文件
if [ ! -f "$BACKUP_FILE" ]; then
    echo "❌ 备份文件不存在: $BACKUP_FILE"
    exit 1
fi

# 构建 mysql 命令（用环境变量传密码，避免特殊字符被 shell 解析）
export MYSQL_PWD="$DB_PASS"
MYSQL_CMD="mysql -h $DB_HOST -P $DB_PORT -u $DB_USER"
if [ -z "$DB_PASS" ]; then
    unset MYSQL_PWD
    MYSQL_CMD="$MYSQL_CMD -p"
fi

echo "================================================"
echo " ⚠️  数据库恢复（覆盖模式）"
echo "================================================"
echo " 备份文件: $BACKUP_FILE"
echo " 目标主机: $DB_HOST:$DB_PORT"
echo " 目标数据库: $DB_NAME"
echo "================================================"
echo
echo "⚠️  警告: 此操作将删除 $DB_NAME 中所有现有表并用备份数据替换！"
echo

# 非交互模式跳过确认（CI/CD），交互模式需要确认
if [ -t 0 ]; then
    read -p "确认继续？(输入 YES): " CONFIRM
    if [ "$CONFIRM" != "YES" ]; then
        echo "已取消"
        exit 0
    fi
fi

# 1. 删除旧数据库（如果存在）
echo "[1/3] 删除旧数据库..."
$MYSQL_CMD -e "DROP DATABASE IF EXISTS \`$DB_NAME\`;"
echo "      ✅ 旧数据库已删除"

# 2. 恢复备份
echo "[2/3] 导入备份数据..."
if [[ "$BACKUP_FILE" == *.gz ]]; then
    gunzip -c "$BACKUP_FILE" | $MYSQL_CMD
else
    $MYSQL_CMD < "$BACKUP_FILE"
fi
echo "      ✅ 数据导入完成"

# 3. 验证
echo "[3/3] 验证恢复结果..."
TABLE_COUNT=$($MYSQL_CMD -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME';")
echo "      数据库: $DB_NAME"
echo "      表数量: $TABLE_COUNT"

# 检查关键表
for table in metric_system metric_category metric_item alarm gongdan issue; do
    COUNT=$($MYSQL_CMD -N -e "SELECT COUNT(*) FROM \`$DB_NAME\`.\`$table\`;" 2>/dev/null || echo "N/A")
    printf "      %-20s %s 条记录\n" "$table" "$COUNT"
done

echo
echo "================================================"
echo " ✅ 恢复完成"
echo "================================================"
echo " 数据库 $DB_NAME 已恢复到 $BACKUP_FILE 的状态"
echo " Flyway 会在应用首次启动时检查并执行增量迁移"
echo "================================================"
