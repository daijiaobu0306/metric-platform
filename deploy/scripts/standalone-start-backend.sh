#!/bin/bash
# metric-code 非容器化部署 —— 后端启动脚本（生产机）
#
# 用法：
#   cd /opt/metric-code-standalone
#   bash bin/start-backend.sh
#
# 设计约束：
#   - 纯 jar 运行，不依赖 docker，不动机器上其他应用
#   - 端口独立（默认 18090），不占用生产既有 18080
#   - 所有连接参数来自 config/app.env，不写死在本脚本里
#   - 目标库已是最新版本，迁移由库自身状态决定，不在此处干预
set -u

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$APP_HOME/config/app.env"

# shellcheck source=standalone-lib.sh
. "$APP_HOME/bin/standalone-lib.sh"
load_app_env "$ENV_FILE" || exit 1
# app.env 里把 SSO_* 写成空值会让 OAuth2 校验在启动期直接失败，这里按「未配置」处理
drop_empty_optional_vars

require_vars APP_PORT DB_URL DB_USER DB_PASSWORD
require_numeric APP_PORT
reject_placeholders DB_PASSWORD

JAR="$APP_HOME/backend/metric-code.jar"
LOG_DIR="$APP_HOME/logs"
PID_FILE="$LOG_DIR/app.pid"

if [ ! -f "$JAR" ]; then
  echo "缺少后端 JAR: $JAR" >&2
  exit 1
fi

# 文件根目录（采集扫描根 + 脚本 + 附件）。
# 注意：命令行参数 --upload.base-path 的优先级高于环境变量，所以不能无条件传，
# 否则 app.env 里的 UPLOAD_BASE_PATH（以及数据库 system_config 覆盖）全部失效。
# 只有用户完全没配时才退回包内 files/。
FILE_ROOT="${UPLOAD_BASE_PATH:-${SCRIPT_STORAGE_PATH:-}}"
if [ -z "$FILE_ROOT" ]; then
  FILE_ROOT="$APP_HOME/files"
  echo "提示: 未配置 UPLOAD_BASE_PATH，文件根目录用包内 $FILE_ROOT（升级包目录会丢，建议改到独立目录）" >&2
fi
if ! mkdir -p "$LOG_DIR" "$FILE_ROOT" 2>/dev/null || [ ! -w "$FILE_ROOT" ]; then
  echo "错误: 文件根目录不可写: $FILE_ROOT（检查 UPLOAD_BASE_PATH 与目录权限）" >&2
  exit 1
fi
UPLOAD_ARGS=()
[ -z "${UPLOAD_BASE_PATH:-}" ] && UPLOAD_ARGS=(--upload.base-path="$FILE_ROOT")

if [ -f "$PID_FILE" ] && ps -p "$(cat "$PID_FILE")" >/dev/null 2>&1; then
  echo "后端已在运行, PID: $(cat "$PID_FILE")"
  exit 0
fi

# 端口占用检查：宁可不启动，也不要和既有服务撞端口
if port_busy "$APP_PORT"; then
  echo "端口 $APP_PORT 已被占用，请先释放或修改 app.env 的 APP_PORT" >&2
  ss -lntp 2>/dev/null | grep ":$APP_PORT " >&2 || true
  exit 1
fi

# ---------------------------------------------------------------------------
# 空库防护
#
# V1__init_schema.sql 内含 CREATE DATABASE + 9 组无条件 DROP TABLE。
# 若目标库是空库，Flyway 会从 V1 开始执行 —— 对已有库而言就是清库。
# 这里只拦「空库」这一种情形：目标库非空时正常启动，由应用自带的
# Flyway 守卫负责其余校验（库状态与代码不匹配时它会明确报错）。
# ---------------------------------------------------------------------------
locate_mysql_client
if [ -n "$MYSQL_CLIENT" ]; then
  parse_db_url "$DB_URL"
  export MYSQL_PWD="$DB_PASSWORD"
  TABLE_COUNT=$("$MYSQL_CLIENT" --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" --batch --skip-column-names \
    "$DB_NAME" -e "SELECT count(*) FROM information_schema.tables WHERE table_schema=DATABASE();" 2>/dev/null)
  if [ -z "$TABLE_COUNT" ]; then
    echo "无法读取目标库表数量（连接失败？）。拒绝启动。" >&2
    exit 1
  fi
  if [ "$TABLE_COUNT" -eq 0 ]; then
    echo "目标库 $DB_NAME 是空库，Flyway 会从 V1 开始执行。" >&2
    echo "V1 含 CREATE DATABASE 与 9 组无条件 DROP TABLE。请确认这是全新空库后再继续。" >&2
    read -r -p "确认目标库为空且可从 V1 重建？输入 YES 继续: " CONFIRM
    [ "$CONFIRM" = "YES" ] || { echo "未确认，放弃启动"; exit 1; }
  else
    echo "目标库 $DB_NAME 现有 $TABLE_COUNT 张表，非空。"
  fi
else
  echo "警告: 无 mysql 客户端，跳过空库检查。" >&2
fi

resolve_java

echo "启动 metric-code 后端: port=$APP_PORT db=$DB_URL"
echo "文件根目录: $FILE_ROOT"
nohup "$JAVA_BIN" ${JAVA_OPTS:--Xms512m -Xmx2g} \
  -jar "$JAR" \
  --spring.datasource.url="$DB_URL" \
  --spring.datasource.username="$DB_USER" \
  --spring.datasource.password="$DB_PASSWORD" \
  --server.port="$APP_PORT" \
  ${UPLOAD_ARGS[@]+"${UPLOAD_ARGS[@]}"} \
  --logging.file.path="$LOG_DIR" \
  --logging.file.name="$LOG_DIR/metric-code.log" \
  > "$LOG_DIR/stdout.log" 2>&1 &

echo $! > "$PID_FILE"
echo "已启动, PID: $(cat "$PID_FILE")"
echo "日志: $LOG_DIR/metric-code.log / $LOG_DIR/stdout.log"

# 等待端口就绪
if ! command -v curl >/dev/null 2>&1; then
  echo "警告: 未找到 curl，跳过就绪探测。请用 tail -f $LOG_DIR/stdout.log 观察启动。" >&2
  exit 0
fi
for i in $(seq 1 120); do
  if curl -sf "http://127.0.0.1:$APP_PORT/api/metric/statistics" >/dev/null 2>&1; then
    echo "后端就绪 (${i}s)"
    exit 0
  fi
  if ! ps -p "$(cat "$PID_FILE")" >/dev/null 2>&1; then
    echo "后端进程已退出，请查看 $LOG_DIR/stdout.log" >&2
    tail -40 "$LOG_DIR/stdout.log" >&2
    exit 1
  fi
  sleep 1
done

echo "等待 120s 后后端仍未就绪，请查看 $LOG_DIR/stdout.log" >&2
exit 1
