#!/bin/bash
# metric-code 非容器化部署 —— 生产库变更前完整备份（生产机）
#
# 用法：
#   bash bin/standalone-backup-database.sh <备份版本号> "<变更说明>"
#   例：bash bin/standalone-backup-database.sh V56 "合并 main 后发布前完整备份"
#
# 约束（来自 AGENTS.md 数据库变更规范）：
#   - 备份版本号 V1/V2... 递增，不覆盖已有版本
#   - 备份失败必须停止，不得继续变更
#   - 产物落在 db_export/<版本>/，并登记到台账 db_export/ledger.md
set -u

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$APP_HOME/config/app.env"

VERSION="${1:-}"
DESCRIPTION="${2:-}"

if [ -z "$VERSION" ] || [ -z "$DESCRIPTION" ]; then
  echo "用法: bash bin/backup-database.sh <V版本号> \"<变更说明>\"" >&2
  exit 1
fi
if ! echo "$VERSION" | grep -Eq '^V[1-9][0-9]*$'; then
  echo "备份版本号格式错误: $VERSION（应为 V1, V2, ...）" >&2
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "缺少配置文件: $ENV_FILE" >&2
  exit 1
fi

# shellcheck source=standalone-lib.sh
. "$APP_HOME/bin/standalone-lib.sh"
load_app_env "$ENV_FILE" || exit 1

# 允许升级脚本用 METRIC_DB_PASSWORD 临时覆盖密码（不写回文件、不回显），
# 用于现场密码与 app.env 不一致、又不便改动配置文件的场景。
if [ -n "${METRIC_DB_PASSWORD:-}" ]; then
  DB_PASSWORD="$METRIC_DB_PASSWORD"
fi

require_vars DB_URL DB_USER DB_PASSWORD
reject_placeholders DB_PASSWORD

parse_db_url "$DB_URL"
EXPORT_ROOT="$APP_HOME/db_export"
LEDGER="$EXPORT_ROOT/ledger.md"
TARGET="$EXPORT_ROOT/$VERSION"

if [ -e "$TARGET" ]; then
  echo "备份版本 $VERSION 已存在，禁止覆盖。请改用更大的编号。" >&2
  exit 1
fi

locate_mysql_client
if [ -z "$MYSQLDUMP_CLIENT" ] || [ -z "$MYSQL_CLIENT" ]; then
  echo "找不到 mysql / mysqldump 客户端，无法备份；请勿继续变更数据库" >&2
  exit 1
fi

mkdir -p "$TARGET"

STAMP=$(date +%Y%m%d_%H%M%S)
DUMP="$TARGET/${DB_NAME}_${VERSION}_${STAMP}.sql"
PARTIAL="$DUMP.partial"

echo "备份 $DB_HOST:$DB_PORT/$DB_NAME → $VERSION ($DESCRIPTION)"
export MYSQL_PWD="$DB_PASSWORD"

# --column-statistics 在 MariaDB 客户端上不存在，先探测再决定是否传
DUMP_OPTS=(--single-transaction --quick --routines --events --triggers --hex-blob --no-tablespaces)
if "$MYSQLDUMP_CLIENT" --no-defaults --help 2>/dev/null | grep -q "column-statistics"; then
  DUMP_OPTS+=(--column-statistics=0)
fi
if "$MYSQLDUMP_CLIENT" --no-defaults --help 2>/dev/null | grep -q "set-gtid-purged"; then
  DUMP_OPTS+=(--set-gtid-purged=OFF)
fi

if ! "$MYSQLDUMP_CLIENT" --no-defaults --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" \
     --default-character-set=utf8mb4 "${DUMP_OPTS[@]}" --result-file="$PARTIAL" "$DB_NAME"; then
  echo "mysqldump 失败，不完整输出保留在 $PARTIAL；请勿继续变更数据库" >&2
  rmdir "$TARGET" 2>/dev/null || true
  exit 1
fi

# 校验：完成标记 + CREATE TABLE 数量 + 表清单比对
if ! tail -c 4096 "$PARTIAL" | grep -q "Dump completed on"; then
  echo "备份缺少完成标记，备份不完整；请勿继续变更数据库" >&2
  rmdir "$TARGET" 2>/dev/null || true
  exit 1
fi
CREATE_COUNT=$(grep -c "^CREATE TABLE" "$PARTIAL" || true)
if [ "$CREATE_COUNT" -eq 0 ]; then
  echo "备份中没有任何 CREATE TABLE 语句，备份不完整；请勿继续变更数据库" >&2
  rmdir "$TARGET" 2>/dev/null || true
  exit 1
fi
# 视图以 /*!50001 CREATE VIEW ... */ 形式导出（版本化注释），不在行首，需单独匹配
VIEW_COUNT=$(grep -c "CREATE.*VIEW" "$PARTIAL" || true)
EXPECTED_TABLES=$("$MYSQL_CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --batch --skip-column-names \
  "$DB_NAME" -e "SELECT count(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE';")
EXPECTED_VIEWS=$("$MYSQL_CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --batch --skip-column-names \
  "$DB_NAME" -e "SELECT count(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='VIEW';")
if [ "$CREATE_COUNT" -ne "$EXPECTED_TABLES" ] || [ "$VIEW_COUNT" -ne "$EXPECTED_VIEWS" ]; then
  echo "备份表数($CREATE_COUNT/$EXPECTED_TABLES) 视图数($VIEW_COUNT/$EXPECTED_VIEWS) 与目标库不一致，备份不完整；请勿继续变更数据库" >&2
  rm -f "$PARTIAL"
  rmdir "$TARGET" 2>/dev/null || true
  exit 1
fi

mv "$PARTIAL" "$DUMP"
SHA=$(sha256sum "$DUMP" | awk '{print $1}')
BYTES=$(stat -c %s "$DUMP")

# 台账
mkdir -p "$EXPORT_ROOT"
[ -f "$LEDGER" ] || printf '# 备份台账\n' > "$LEDGER"
cat >> "$LEDGER" <<EOF

## $VERSION：$DESCRIPTION

- 时间：$(date -Iseconds)
- 目标：$DB_HOST:$DB_PORT/$DB_NAME
- 文件：\`db_export/$VERSION/$(basename "$DUMP")\`
- 范围：完整结构与数据；表 $CREATE_COUNT 张、视图 $VIEW_COUNT 个；大小 $BYTES 字节
- SHA-256：\`$SHA\`
- 验证：mysqldump 成功、完成标记与表数比对通过；未做恢复演练。
EOF

echo "备份完成: $DUMP"
echo "  表数 $CREATE_COUNT, 大小 $BYTES 字节"
echo "  SHA-256: $SHA"
echo "台账已更新: $LEDGER"
