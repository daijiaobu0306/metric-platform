#!/bin/bash
# metric-code 数据库初始化 —— 单机环境（数据库与平台同在 67，库走 127.0.0.1）
#
# 用法（在 67 服务器上执行）：
#   bash bin/init-database-server.sh
#
# 原 66 独立数据库服务器已于 2026-11-21 停用下线；脚本名保留以兼容既有文档与升级包。
#
# 只做数据库的事，不部署应用：
#   1. 确认/创建目标库
#   2. 校验目标库为空（绝不覆盖既有数据）
#   3. 用安装基线导入结构与已确认配置
#   4. 由 Flyway 按序执行基线之后的迁移
#   5. 记录备份台账
#
# 需要本机有：mysql 客户端、mysqldump、python3、JDK 17+。
set -u

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$APP_HOME/config/app.env"

# shellcheck source=standalone-lib.sh
. "$APP_HOME/bin/standalone-lib.sh"
load_app_env "$ENV_FILE" || exit 1

require_vars DB_URL DB_USER DB_PASSWORD
reject_placeholders DB_PASSWORD

REPO="$APP_HOME/repo"
MIGRATIONS="$REPO/backend/src/main/resources/db/migration"
BOOTSTRAP="$REPO/deploy/db_export/bootstrap"
LEDGER="$REPO/doc/数据库版本记录.md"

# Flyway 运行时要从 jar 里提取依赖，database_support.py 按仓库布局找
# backend/target/metric-code.jar。部署包里 jar 只有一份，这里就位一份副本。
mkdir -p "$REPO/backend/target"
cp -f "$APP_HOME/backend/metric-code.jar" "$REPO/backend/target/metric-code.jar"

if ! command -v python3 >/dev/null 2>&1; then
  echo "缺少 python3，无法初始化数据库（yum install -y python3）" >&2
  exit 1
fi

# 67 上系统默认 JDK8 不够用；这里提前校验，避免 Flyway 阶段才报错
resolve_java

locate_mysql_client
if [ -z "$MYSQL_CLIENT" ] || [ -z "$MYSQLDUMP_CLIENT" ]; then
  echo "找不到 mysql / mysqldump 客户端。" >&2
  echo "若确实已安装，请定位其路径后加进 PATH 再重试：" >&2
  echo "    find / -maxdepth 6 -type f -name mysql -perm -u+x 2>/dev/null" >&2
  echo "    export PATH=<上面找到的目录>:\$PATH" >&2
  echo "或直接指定路径后重试：" >&2
  echo '    MYSQL_BIN=/path/to/mysql MYSQLDUMP_BIN=/path/to/mysqldump bash bin/init-database-server.sh' >&2
  exit 1
fi

# 让下层的 Python 工具也用同一个客户端：把它的目录加进 PATH
export PATH="$(dirname "$MYSQL_CLIENT"):$(dirname "$MYSQLDUMP_CLIENT"):$PATH"
echo "使用 mysql 客户端: $MYSQL_CLIENT"
echo "使用 mysqldump 客户端: $MYSQLDUMP_CLIENT"

if [ ! -d "$MIGRATIONS" ] || [ ! -d "$BOOTSTRAP" ]; then
  echo "部署包不完整：缺少迁移目录或安装基线" >&2
  exit 1
fi

parse_db_url "$DB_URL"
export MYSQL_PWD="$DB_PASSWORD"

echo "目标库: $DB_HOST:$DB_PORT/$DB_NAME"

DB_EXISTS=$("$MYSQL_CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --batch --skip-column-names \
  -e "SELECT count(*) FROM information_schema.schemata WHERE schema_name='$DB_NAME';" 2>/dev/null)
if [ -z "$DB_EXISTS" ]; then
  echo "无法连接数据库服务器 $DB_HOST:$DB_PORT，请检查 app.env 中的 DB_URL / DB_USER / DB_PASSWORD" >&2
  exit 1
fi
if [ "$DB_EXISTS" = "0" ]; then
  echo "目标库不存在，创建空库 $DB_NAME ..."
  if ! "$MYSQL_CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" \
       -e "CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"; then
    echo "创建数据库失败。请确认 $DB_USER 有 CREATE 权限。" >&2
    exit 1
  fi
  echo "已创建。"
fi

TABLE_COUNT=$("$MYSQL_CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --batch --skip-column-names \
  "$DB_NAME" -e "SELECT count(*) FROM information_schema.tables WHERE table_schema=DATABASE();" 2>/dev/null)
if [ -z "$TABLE_COUNT" ]; then
  echo "无法读取目标库表数量，请检查数据库连接与账号权限。" >&2
  exit 1
fi
if [ "$TABLE_COUNT" -ne 0 ]; then
  echo "目标库已有 $TABLE_COUNT 张表，不是空库。拒绝初始化，以免覆盖既有数据。" >&2
  echo "若是存量库需要升级，请改用 prepare-database.py upgrade。" >&2
  exit 1
fi
echo "目标库为空，可以初始化。"

# 库已存在时不会重建，其默认字符集可能仍是 latin1。
# 后续迁移里若有不显式声明字符集的表，会继承库默认值，中文直接变乱码。
# 空库阶段做一次幂等归一，没有任何数据风险。
DB_CHARSET=$("$MYSQL_CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --batch --skip-column-names \
  -e "SELECT DEFAULT_CHARACTER_SET_NAME FROM information_schema.schemata WHERE schema_name='$DB_NAME';" 2>/dev/null)
if [ -n "$DB_CHARSET" ] && [ "$DB_CHARSET" != "utf8mb4" ]; then
  echo "目标库默认字符集是 $DB_CHARSET，空库阶段归一为 utf8mb4 ..."
  if ! "$MYSQL_CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" \
       -e "ALTER DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"; then
    echo "归一字符集失败，请确认 $DB_USER 有 ALTER 权限。" >&2
    exit 1
  fi
  echo "已归一为 utf8mb4 / utf8mb4_unicode_ci。"
fi

# 首个管理员口令：建库时用来创建 admin 账号
if [ -z "${METRIC_ADMIN_PASSWORD:-}" ]; then
  echo
  echo "请设置首个管理员 admin 的口令（至少 10 位，不要用 change-me）:"
  read -r -s METRIC_ADMIN_PASSWORD
  echo
fi
METRIC_ADMIN_USERNAME="${METRIC_ADMIN_USERNAME:-admin}"
export METRIC_ADMIN_PASSWORD METRIC_ADMIN_USERNAME
if [ "${#METRIC_ADMIN_PASSWORD}" -lt 10 ] || echo "$METRIC_ADMIN_PASSWORD" | grep -qi 'change-me'; then
  echo "管理员口令太弱（至少 10 位且不含 change-me）" >&2
  exit 1
fi

# 备份版本号：按台账与已有目录取下一个未占用编号
OCCUPIED=$(grep -oE '^## V[0-9]+' "$LEDGER" 2>/dev/null | grep -oE '[0-9]+' || true)
for dir in "$REPO"/deploy/db_export/V[0-9]*; do
  [ -d "$dir" ] && OCCUPIED="$OCCUPIED $(basename "$dir" | tr -dc '0-9')"
done
NEXT=1
for n in $OCCUPIED; do
  [ "$n" -ge "$NEXT" ] && NEXT=$((n + 1))
done
BACKUP_VERSION="V$NEXT"
echo "使用备份版本 $BACKUP_VERSION"

ENV_TMP="$APP_HOME/logs/db-init.env"
mkdir -p "$APP_HOME/logs"
# 注意：JAVA_HOME 必须写进这份 env，database_support.py 只从该文件读 JDK 路径；
# 少了它，Python 侧会退回系统 JDK8，Flyway 执行必然失败。
JAVA_HOME_DERIVED="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
{
  echo "SPRING_DATASOURCE_URL=$DB_URL"
  echo "SPRING_DATASOURCE_USERNAME=$DB_USER"
  echo "SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD"
  echo "JAVA_HOME=$JAVA_HOME_DERIVED"
  echo "METRIC_ADMIN_PASSWORD=$METRIC_ADMIN_PASSWORD"
  echo "METRIC_ADMIN_USERNAME=$METRIC_ADMIN_USERNAME"
} > "$ENV_TMP"
chmod 600 "$ENV_TMP"

cd "$REPO" || exit 1
python3 deploy/scripts/prepare-database.py init \
  --env-file "$ENV_TMP" \
  --expected-database "$DB_NAME" \
  --backup-version "$BACKUP_VERSION" \
  --create-empty
RESULT=$?
rm -f "$ENV_TMP"

if [ $RESULT -ne 0 ]; then
  echo "数据库初始化失败。请查看上面的报错与备份 $BACKUP_VERSION。" >&2
  exit 1
fi

echo
echo "数据库初始化完成（备份版本 $BACKUP_VERSION）。"
echo "表数量: $("$MYSQL_CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --batch --skip-column-names \
  "$DB_NAME" -e "SELECT count(*) FROM information_schema.tables WHERE table_schema=DATABASE();")"
echo "可以在 67 服务器上部署应用了。"
