#!/bin/bash
# metric-code 升级包安装脚本（生产机）
#
# 用法：
#   bash bin/apply-upgrade.sh                        # 自动定位已部署目录
#   bash bin/apply-upgrade.sh --app-home /opt/metric-code-standalone
#   bash bin/apply-upgrade.sh --dry-run              # 只打印将执行的动作
#   bash bin/apply-upgrade.sh --yes                  # 免交互确认
#   bash bin/apply-upgrade.sh --skip-backup          # 跳过数据库备份（不建议）
#   bash bin/apply-upgrade.sh --db-password 'xxx'    # 临时指定数据库密码（不落盘、不回显）
#
# JDK：脚本顶部有 JAVA17_BIN 变量，直接写 JDK17 里 java 的绝对路径最省事；
#   留空则依次用 $JAVA_HOME/bin/java、PATH 里的 java。例如：
#   JAVA17_BIN=/usr/lib/jvm/java-17-openjdk/bin/java bash bin/apply-upgrade.sh --skip-backup
#
# MySQL：脚本顶部有 MYSQL_BIN / MYSQLDUMP_BIN 变量，写 mysql 与 mysqldump 的绝对路径；
#   留空则自动探测（PATH → /usr/local/mysql/bin → component/mysql*/bin）。例如：
#   MYSQL_BIN=/home/appuser/besinsight/component/mysql/bin/mysql \
#   MYSQLDUMP_BIN=/home/appuser/besinsight/component/mysql/bin/mysqldump \
#     bash bin/apply-upgrade.sh
#
# 数据库密码：默认读 $APP_HOME/config/app.env 的 DB_PASSWORD（现指向 67 本机库，DB_URL 应为 127.0.0.1:3306；66 已下线）。
#   密码只用于升级前备份与迁移版本查询，通过环境变量 MYSQL_PWD 传递，不打印、不写入任何文件。
#   也可用环境变量 METRIC_DB_PASSWORD 覆盖，避免在命令行留下痕迹。
#
# 设计约束：
#   - 只替换程序文件（jar / 前端 / worker），不动 config/app.env、不动 files 采集目录与已采集数据
#   - 停后端之前先校验数据库连通性与备份工具，密码不对就直接中止，不会先把服务停掉
#   - 数据库机与应用机分离（67 无 mysql 客户端）时：先在数据库机备份，再带 --skip-backup 升级
#   - 表结构变更由后端启动时 Flyway 自动补齐（jar 内已含全部迁移）
#   - 启动失败自动回滚到升级前的文件并重新拉起
set -u

PKG_HOME="$(cd "$(dirname "$0")/.." && pwd)"

# ==========================================================================
# 【现场可改】JDK 17 的 java 可执行文件路径
#   已写入现场的 JDK17 路径，通常不用改；换机器时改成下面任一路径即可：
#     /home/appuser/besinsight/component/jdk17/bin/java
#     /usr/lib/jvm/java-17-openjdk/bin/java
#     /usr/lib/jvm/jre-17/bin/java
#     /opt/jdk-17.0.12/bin/java
#   该路径不存在时会告警并自动回退：$JAVA_HOME/bin/java → PATH 里的 java
#   也可以用环境变量临时覆盖：JAVA17_BIN=/xxx/bin/java bash bin/apply-upgrade.sh ...
# ==========================================================================
JAVA17_BIN="${JAVA17_BIN:-/home/appuser/besinsight/component/jdk17/bin/java}"

# ==========================================================================
# 【现场可改】MySQL 客户端路径
#   写 mysql 与 mysqldump 的绝对路径，例如：
#     MYSQL_BIN=/home/appuser/besinsight/component/mysql/bin/mysql
#     MYSQLDUMP_BIN=/home/appuser/besinsight/component/mysql/bin/mysqldump
#   留空则自动探测：PATH → /usr/local/mysql/bin → /home/appuser/besinsight/component/mysql*/bin
#   也可以用环境变量临时覆盖：MYSQL_BIN=/xxx/bin/mysql bash bin/apply-upgrade.sh ...
# ==========================================================================
MYSQL_BIN="${MYSQL_BIN:-}"
MYSQLDUMP_BIN="${MYSQLDUMP_BIN:-}"
# 记住脚本顶部的显式取值：后面 load_app_env 之后再把它应用回去，
# 保证「改脚本顶部就能生效」，不被 config/app.env 里的同名变量覆盖。
MYSQL_BIN_EDIT="$MYSQL_BIN"
MYSQLDUMP_BIN_EDIT="$MYSQLDUMP_BIN"

APP_HOME="${APP_HOME:-}"
DRY_RUN=0
ASSUME_YES=0
SKIP_BACKUP=0
DB_PASSWORD_OVERRIDE="${METRIC_DB_PASSWORD:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --app-home) APP_HOME="${2:-}"; shift 2 ;;
    --app-home=*) APP_HOME="${1#*=}"; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --skip-backup) SKIP_BACKUP=1; shift ;;
    --db-password) DB_PASSWORD_OVERRIDE="${2:-}"; shift 2 ;;
    --db-password=*) DB_PASSWORD_OVERRIDE="${1#*=}"; shift ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 1 ;;
  esac
done

log()  { echo "[$(date '+%H:%M:%S')] $*"; }
die()  { echo "错误: $*" >&2; exit 1; }
step() { echo; echo "===== $* ====="; }

run() {
  if [ "$DRY_RUN" = "1" ]; then
    echo "  [dry-run] $*"
  else
    "$@"
  fi
}

# ---------- 0. 定位已部署目录 ----------
step "0. 定位已部署环境"
if [ -z "$APP_HOME" ]; then
  for candidate in /opt/metric-code-standalone /opt/metric-code /srv/metric-code; do
    if [ -f "$candidate/config/app.env" ]; then APP_HOME="$candidate"; break; fi
  done
fi
if [ -z "$APP_HOME" ] || [ ! -f "$APP_HOME/config/app.env" ]; then
  die "未找到已部署环境。请用 --app-home 指定安装目录（应包含 config/app.env）"
fi
APP_HOME="$(cd "$APP_HOME" && pwd)"
[ -f "$APP_HOME/backend/metric-code.jar" ] || die "$APP_HOME 不像已部署目录：缺 backend/metric-code.jar"
log "目标环境: $APP_HOME"

# shellcheck source=standalone-lib.sh
. "$PKG_HOME/bin/standalone-lib.sh"
load_app_env "$APP_HOME/config/app.env" || die "读取 config/app.env 失败"
APP_PORT="${APP_PORT:-18090}"

# 脚本顶部的显式路径优先于 config/app.env（现场改脚本顶部即可生效）
[ -n "${MYSQL_BIN_EDIT:-}" ] && MYSQL_BIN="$MYSQL_BIN_EDIT"
[ -n "${MYSQLDUMP_BIN_EDIT:-}" ] && MYSQLDUMP_BIN="$MYSQLDUMP_BIN_EDIT"
export MYSQL_BIN MYSQLDUMP_BIN
# 定位 mysql / mysqldump：脚本顶部变量 → config/app.env → PATH → 常见目录
locate_mysql_client

# 数据库密码：命令行/环境变量优先于 config/app.env；只在本进程内生效，不写回文件。
PASSWORD_SOURCE="config/app.env"
if [ -n "${DB_PASSWORD_OVERRIDE:-}" ]; then
  DB_PASSWORD="$DB_PASSWORD_OVERRIDE"
  export METRIC_DB_PASSWORD="$DB_PASSWORD_OVERRIDE"
  PASSWORD_SOURCE="命令行/环境变量覆盖"
fi
export MYSQL_PWD="${DB_PASSWORD:-}"
unset DB_PASSWORD_OVERRIDE

# ---------- 1. 预检 ----------
step "1. 升级前预检"
# java 解析顺序：JAVA17_BIN（本脚本顶部变量）→ JAVA_HOME/bin/java → PATH 里的 java
JAVA_BIN=""
if [ -n "${JAVA17_BIN:-}" ] && [ -x "$JAVA17_BIN" ]; then
  JAVA_BIN="$JAVA17_BIN"
elif [ -n "${JAVA17_BIN:-}" ]; then
  # 顶部写死的是现场路径，换机器时不一定存在：告警后继续按 JAVA_HOME/PATH 找
  echo "警告: JAVA17_BIN 指向的文件不存在或不可执行: $JAVA17_BIN（继续按 JAVA_HOME / PATH 查找）" >&2
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
  else
    JAVA_BIN="$(command -v java || true)"
  fi
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="$(command -v java || true)"
fi
[ -n "$JAVA_BIN" ] || die "找不到 java：请在脚本顶部设置 JAVA17_BIN，或设置 JAVA_HOME，或把 java 放进 PATH"
JAVA_VER="$("$JAVA_BIN" -version 2>&1 | head -1)"
JAVA_MAJOR="$(printf '%s' "$JAVA_VER" | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)"
if [ "${JAVA_MAJOR:-0}" = "1" ]; then
  JAVA_MAJOR="$(printf '%s' "$JAVA_VER" | sed -nE 's/.*version "1\.([0-9]+).*/\1/p' | head -1)"
fi
[ "${JAVA_MAJOR:-0}" -ge 17 ] 2>/dev/null || die "java 版本需 17+，当前: $JAVA_VER（来自 $JAVA_BIN）
   请在脚本顶部把 JAVA17_BIN 指向 JDK17 的 java，例如 /usr/lib/jvm/java-17-openjdk/bin/java"
log "java: $JAVA_VER（$JAVA_BIN）"
# 让后续启动脚本（standalone-start-backend.sh 的 resolve_java）复用同一个 java，
# 否则它可能又挑到 PATH 上的 JDK8
export PATH="$(dirname "$JAVA_BIN"):$PATH"
[ -f "$PKG_HOME/backend/metric-code.jar" ] || die "升级包损坏：缺 backend/metric-code.jar"
PKG_JAR_SIZE="$(stat -c %s "$PKG_HOME/backend/metric-code.jar" 2>/dev/null || echo 0)"
[ "$PKG_JAR_SIZE" -gt 10000000 ] || die "升级包内 jar 体积异常（$PKG_JAR_SIZE 字节）"
log "升级包 jar: $((PKG_JAR_SIZE/1024/1024)) MB"
AVAIL_KB="$(df -Pk "$APP_HOME" | awk 'NR==2{print $4}')"
[ "${AVAIL_KB:-0}" -gt 524288 ] || die "磁盘可用空间不足 512MB（$((AVAIL_KB/1024)) MB）"
log "磁盘可用: $((AVAIL_KB/1024)) MB"

# 备份客户端是否存在，统一放到 1.5 里判断（那里能给出「去数据库机上备份」的完整指引）

# ---------- 1.5 数据库连通性预检（必须在停后端之前） ----------
# 密码只保存在进程环境变量里（MYSQL_PWD），不打印、不落盘。
#
# 典型拓扑（2026-11-21 更新）：67 同时运行数据库与平台，本机已装 mysql/mysqldump，
# 脚本内备份可正常执行；66 数据库机已停用下线。
# 只有在没装客户端的机器上才需要先到有客户端的机器备份，再带 --skip-backup 升级。
if [ "$SKIP_BACKUP" != "1" ]; then
  step "1.5 校验数据库连通性"
  parse_db_url "${DB_URL:-}"
  DB_TARGET="$DB_HOST:${DB_PORT:-3306}/$DB_NAME"
  if [ "$DRY_RUN" = "1" ]; then
    log "[dry-run] 将校验 $DB_TARGET 连通性（用户 ${DB_USER:-root}，密码来源：$PASSWORD_SOURCE）"
  else
    if [ -z "${MYSQL_CLIENT:-}" ] || [ -z "${MYSQLDUMP_CLIENT:-}" ]; then
      DUMP_STAMP="$(date +%Y%m%d_%H%M%S)"
      cat >&2 <<EOF
错误: 本机没有 mysql / mysqldump 客户端，无法在本机完成升级前备份。

数据库在 ${DB_TARGET}（通常是独立的数据库服务器）。请按下面两步做：

  1) 在数据库服务器（有 mysqldump 的那台）上备份：
       mysqldump -h ${DB_HOST} -P ${DB_PORT:-3306} -u ${DB_USER:-root} -p \\
         --single-transaction --quick --routines --events --triggers \\
         --default-character-set=utf8mb4 ${DB_NAME} | gzip > ${DB_NAME}_before_upgrade_${DUMP_STAMP}.sql.gz
     备份文件确认非空、且含 'Dump completed' 再继续。

  2) 回到本机，跳过备份执行升级：
       bash bin/apply-upgrade.sh --skip-backup

如果客户端装了但不在 PATH，直接改本脚本顶部的变量：
  MYSQL_BIN=/home/appuser/besinsight/component/mysql/bin/mysql
  MYSQLDUMP_BIN=/home/appuser/besinsight/component/mysql/bin/mysqldump

说明：--skip-backup 只跳过「本机备份」和「本机连通性校验」。后端启动时用 JDBC 连库
（凭据仍取 config/app.env），能起来即说明连通；起不来会自动回滚程序文件。
本次未停止后端，服务仍在线。
EOF
      exit 1
    fi
    case "${DB_PASSWORD:-}" in
      "") die "数据库密码为空（config/app.env 的 DB_PASSWORD）。请填写 $DB_TARGET 的真实密码，或用 --db-password '密码' 临时指定" ;;
      RealPass12345) die "数据库密码仍是模板示例值 RealPass12345（config/app.env）。请改成 $DB_TARGET 的真实密码，或用 --db-password '密码' 临时指定" ;;
    esac
    DB_ERR="$(MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CLIENT" --no-defaults -h"$DB_HOST" -P"${DB_PORT:-3306}" -u"${DB_USER:-root}" -N -e "SELECT 1" 2>&1 >/dev/null)"
    if [ -n "$DB_ERR" ]; then
      echo "$DB_ERR" >&2
      die "连不上 $DB_TARGET（密码来源：$PASSWORD_SOURCE）。
   请核对该库密码后重试：修改 config/app.env 的 DB_PASSWORD，或加 --db-password '密码'。
   本次未停止后端，服务仍在线。"
    fi
    TABLE_N="$(MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CLIENT" --no-defaults -h"$DB_HOST" -P"${DB_PORT:-3306}" -u"${DB_USER:-root}" -N \
      -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME'" 2>/dev/null)"
    [ -n "${TABLE_N:-}" ] || die "已连上 $DB_TARGET，但读不到库 $DB_NAME（库名或账号权限有问题），本次未停后端"
    log "数据库连通正常: $DB_TARGET（用户 ${DB_USER:-root}，密码来源：$PASSWORD_SOURCE，表数 $TABLE_N）"
    log "mysql 客户端: $MYSQL_CLIENT"
    log "mysqldump 客户端: $MYSQLDUMP_CLIENT"
  fi
fi

# ---------- 2. 确认 ----------
OLD_VERSION="$(sed -nE 's/^BUILD_ID[=:][[:space:]]*([^[:space:]]+).*/\1/p' "$APP_HOME/BUILD_INFO.txt" 2>/dev/null | head -1)"
NEW_VERSION="$(sed -nE 's/^BUILD_ID[=:][[:space:]]*([^[:space:]]+).*/\1/p' "$PKG_HOME/BUILD_INFO.txt" 2>/dev/null | head -1)"
echo
log "当前版本: ${OLD_VERSION:-未知}"
log "升级版本: ${NEW_VERSION:-未知}"
log "后端端口: $APP_PORT"
if [ "$ASSUME_YES" != "1" ] && [ "$DRY_RUN" != "1" ]; then
  printf "确认升级？会先停后端、备份数据库，然后替换程序文件 [y/N] "
  read -r answer
  case "$answer" in y|Y|yes|YES) ;; *) echo "已取消"; exit 0 ;; esac
fi

# ---------- 3. 停后端 ----------
step "3. 停止后端"
if [ -f "$APP_HOME/logs/app.pid" ]; then
  run bash "$APP_HOME/bin/standalone-stop-backend.sh" || die "停止后端失败"
else
  log "后端未在运行，跳过"
fi

# ---------- 4. 备份现有程序文件 ----------
TS="$(date '+%Y%m%d%H%M%S')"
ROLLBACK="$APP_HOME/rollback/$TS"
step "4. 备份现有程序文件到 $ROLLBACK"
if [ "$DRY_RUN" != "1" ]; then
  mkdir -p "$ROLLBACK"
  for part in backend frontend worker bin; do
    [ -e "$APP_HOME/$part" ] && cp -a "$APP_HOME/$part" "$ROLLBACK/$part"
  done
  log "已备份: $(du -sh "$ROLLBACK" 2>/dev/null | cut -f1)"
else
  echo "  [dry-run] 备份 backend/ frontend/ worker/ bin/ 到 $ROLLBACK"
fi

# ---------- 4.5 先刷新 bin 脚本 ----------
# 备份脚本在本轮就要用：新版本才支持 METRIC_DB_PASSWORD 覆盖密码，
# 因此必须在“备份数据库”之前更新，且只覆盖包内同名脚本，不删除现场的 start/stop 等脚本。
step "4.5 更新 bin 脚本"
if [ "$DRY_RUN" != "1" ]; then
  if [ -d "$PKG_HOME/bin" ]; then
    mkdir -p "$APP_HOME/bin"
    UPDATED=""
    for f in "$PKG_HOME"/bin/*.sh; do
      [ -f "$f" ] || continue
      cp -f "$f" "$APP_HOME/bin/$(basename "$f")"
      UPDATED="$UPDATED $(basename "$f")"
    done
    log "已更新:$UPDATED"
  fi
else
  echo "  [dry-run] 用升级包 bin/*.sh 覆盖 $APP_HOME/bin/ 同名脚本"
fi

# ---------- 4.6 安装采集脚本（外置，不进 jar） ----------
# 取数/采集脚本以独立目录随包分发，装到 $UPLOAD_BASE_PATH/collection-scripts/releases/<版本>/。
# 后端启动时直接读磁盘，所以改脚本不用重打 jar；版本目录不可变，同名不同内容一律拒绝。
#
# 校验只用 sha256sum + sed，不依赖 python：应用机上未必有 python，
# 而 systemd 的 PATH 与登录 shell 不同，用 python 做校验容易在升级中途失败。
manifest_entries() {
  # 输出 manifest 里 "文件名": "<64位十六进制>" 的每一对，格式：<文件名><TAB><sha256>
  sed -nE 's/^[[:space:]]*"([^"]+)"[[:space:]]*:[[:space:]]*"([0-9a-fA-F]{64})"[[:space:]]*,?[[:space:]]*$/\1\t\2/p' "$1/manifest.json"
}
manifest_version() {
  sed -nE 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*$/\1/p' "$1/manifest.json" | head -1
}
sha256_of() { sha256sum "$1" 2>/dev/null | awk '{print $1}'; }
verify_release() {
  local dir="$1" name expected actual rc=0
  [ -f "$dir/manifest.json" ] || { echo "  缺 manifest.json"; return 1; }
  while IFS=$'\t' read -r name expected; do
    [ -n "$name" ] || continue
    if [ ! -f "$dir/$name" ]; then echo "  缺文件: $name"; rc=1; continue; fi
    actual="$(sha256_of "$dir/$name")"
    [ "$actual" = "$expected" ] || { echo "  哈希不符: $name"; rc=1; }
  done <<EOF
$(manifest_entries "$dir")
EOF
  return $rc
}
same_release() {
  local src="$1" dst="$2" name expected actual
  [ -f "$dst/manifest.json" ] || return 1
  [ "$(manifest_version "$src")" = "$(manifest_version "$dst")" ] || return 1
  while IFS=$'\t' read -r name expected; do
    [ -n "$name" ] || continue
    [ -f "$dst/$name" ] || return 1
    actual="$(sha256_of "$dst/$name")"
    [ "$actual" = "$expected" ] || return 1
  done <<EOF
$(manifest_entries "$src")
EOF
  return 0
}
step "4.6 安装采集脚本到脚本根目录"
RAW_BASE="${UPLOAD_BASE_PATH:-${SCRIPT_STORAGE_PATH:-}}"
case "$RAW_BASE" in
  "")  FILE_ROOT="$APP_HOME/files" ;;
  /*)  FILE_ROOT="$RAW_BASE" ;;
  *)   FILE_ROOT="$APP_HOME/$RAW_BASE" ;;
esac
RELEASES_DIR="$FILE_ROOT/collection-scripts/releases"
if [ -d "$PKG_HOME/collection-scripts/releases" ]; then
  if [ "$DRY_RUN" = "1" ]; then
    echo "  [dry-run] 安装到 $RELEASES_DIR: $(cd "$PKG_HOME/collection-scripts/releases" && ls -1 | tr '\n' ' ')"
  else
    mkdir -p "$RELEASES_DIR"
    for vdir in "$PKG_HOME"/collection-scripts/releases/*/; do
      [ -d "$vdir" ] || continue
      v="$(basename "$vdir")"
      target="$RELEASES_DIR/$v"
      if [ -e "$target" ]; then
        same_release "$vdir" "$target" || die "脚本版本 $v 在目标机已存在但内容不同。版本不可变，请发布新版本号而不是覆盖：$target"
        log "脚本版本 $v 已存在且一致，跳过"
      else
        tmp="$RELEASES_DIR/.install-$v-$$"
        rm -rf "$tmp"; mkdir -p "$tmp"
        cp -a "$vdir/." "$tmp/"
        verify_release "$tmp" || { rm -rf "$tmp"; die "脚本版本 $v 校验失败，已清理临时目录"; }
        mv "$tmp" "$target" || die "安装脚本版本 $v 失败"
        log "已安装脚本版本 $v -> $target"
      fi
    done
  fi
else
  log "包内未包含外置采集脚本，跳过"
fi

# ---------- 5. 备份数据库 ----------
step "5. 备份数据库"
if [ "$SKIP_BACKUP" = "1" ]; then
  log "已按参数跳过备份（--skip-backup）"
  log "前置条件：已在数据库服务器上完成 mysqldump 备份，并确认备份文件非空且含 'Dump completed'"
else
  NEXT_V=1
  if [ -d "$APP_HOME/db_export" ]; then
    MAX_V="$(find "$APP_HOME/db_export" -maxdepth 1 -type d -name 'V[0-9]*' -printf '%f\n' 2>/dev/null \
             | sed -E 's/^V([0-9]+)$/\1/' | sort -n | tail -1)"
    [ -n "${MAX_V:-}" ] && NEXT_V=$((MAX_V + 1))
  fi
  BACKUP_DESC="升级到 ${NEW_VERSION:-新版本} 前完整备份"
  log "备份版本: V$NEXT_V（$BACKUP_DESC）"
  run bash "$APP_HOME/bin/standalone-backup-database.sh" "V$NEXT_V" "$BACKUP_DESC" \
    || die "数据库备份失败，已中止升级（后端保持停止，可用 bin/standalone-start-backend.sh 重新拉起）"
fi

# ---------- 6. 替换程序文件 ----------
step "6. 替换程序文件"
if [ "$DRY_RUN" != "1" ]; then
  mkdir -p "$APP_HOME/backend"
  cp -f "$PKG_HOME/backend/metric-code.jar" "$APP_HOME/backend/metric-code.jar"
  log "已更新 backend/metric-code.jar"
  if [ -d "$PKG_HOME/frontend" ]; then
    rm -rf "$APP_HOME/frontend"
    cp -a "$PKG_HOME/frontend" "$APP_HOME/frontend"
    log "已更新 frontend/（$(find "$APP_HOME/frontend" -type f | wc -l) 个文件）"
  fi
  if [ -d "$PKG_HOME/worker" ]; then
    rm -rf "$APP_HOME/worker"
    cp -a "$PKG_HOME/worker" "$APP_HOME/worker"
    log "已更新 worker/"
  fi
  cp -f "$PKG_HOME/BUILD_INFO.txt" "$APP_HOME/BUILD_INFO.txt"
else
  echo "  [dry-run] 覆盖 backend/metric-code.jar、frontend/、worker/、BUILD_INFO.txt"
fi

# ---------- 7. 启动并等待就绪 ----------
step "7. 启动后端"
rollback_and_exit() {
  echo "启动未就绪，开始回滚程序文件..." >&2
  bash "$APP_HOME/bin/standalone-stop-backend.sh" >/dev/null 2>&1 || true
  if [ -d "$ROLLBACK/backend" ]; then
    rm -rf "$APP_HOME/backend" "$APP_HOME/frontend" "$APP_HOME/worker"
    cp -a "$ROLLBACK/backend" "$APP_HOME/backend"
    [ -d "$ROLLBACK/frontend" ] && cp -a "$ROLLBACK/frontend" "$APP_HOME/frontend"
    [ -d "$ROLLBACK/worker" ] && cp -a "$ROLLBACK/worker" "$APP_HOME/worker"
    if [ -d "$ROLLBACK/bin" ]; then
      rm -rf "$APP_HOME/bin"
      cp -a "$ROLLBACK/bin" "$APP_HOME/bin"
    fi
    echo "已回滚到升级前程序文件（含 bin/ 脚本）。" >&2
    bash "$APP_HOME/bin/standalone-start-backend.sh" >/dev/null 2>&1 || true
  fi
  echo "数据库如需回退，请使用升级前备份（db_export 下最新版本）手工恢复。" >&2
  echo "后端日志: $APP_HOME/logs/backend.err.log" >&2
  exit 1
}

if [ "$DRY_RUN" = "1" ]; then
  echo "  [dry-run] 启动后端并轮询 /api 就绪"
  echo "  [dry-run] 完成后打印迁移结果与回滚方法"
  echo
  log "dry-run 结束，未做任何变更"
  exit 0
fi

bash "$APP_HOME/bin/standalone-start-backend.sh" || die "启动脚本返回失败"

wait_ready() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsS -o /dev/null --max-time 2 "http://127.0.0.1:$APP_PORT/" 2>/dev/null
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$APP_PORT") 2>/dev/null
  fi
}

READY=0
for i in $(seq 1 90); do
  if wait_ready; then READY=1; break; fi
  if [ -f "$APP_HOME/logs/app.pid" ] && ! ps -p "$(cat "$APP_HOME/logs/app.pid")" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
[ "$READY" = "1" ] || rollback_and_exit

# ---------- 8. 结果 ----------
step "8. 重启采集执行器（若有）"
WORKER_UNIT="metric-collection-worker.service"
if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files "$WORKER_UNIT" >/dev/null 2>&1 \
   && systemctl list-unit-files "$WORKER_UNIT" | grep -q "$WORKER_UNIT"; then
  if systemctl restart "$WORKER_UNIT" 2>/dev/null; then
    log "已重启 $WORKER_UNIT（worker/ 代码已更新，旧进程不会自动加载新协议）"
  else
    echo "警告: $WORKER_UNIT 重启失败，请手工执行: sudo systemctl restart $WORKER_UNIT" >&2
  fi
else
  log "未检测到 $WORKER_UNIT，若采集执行器以其他方式运行，请手工重启"
fi

step "9. 升级完成"
log "版本: ${NEW_VERSION:-未知}"
log "后端端口: $APP_PORT（已就绪）"
if [ -n "${MYSQL_CLIENT:-}" ]; then
  DB_HOST="$(printf '%s' "${DB_URL:-}" | sed -nE 's#jdbc:mysql://([^:/]+).*#\1#p')"
  DB_PORT="$(printf '%s' "${DB_URL:-}" | sed -nE 's#jdbc:mysql://[^:/]+:([0-9]+).*#\1#p')"
  DB_NAME="$(printf '%s' "${DB_URL:-}" | sed -nE 's#jdbc:mysql://[^/]+/([^?]+).*#\1#p')"
  if [ -n "$DB_HOST" ] && [ -n "$DB_NAME" ]; then
    LATEST="$(MYSQL_PWD="${DB_PASSWORD:-}" "$MYSQL_CLIENT" -h"$DB_HOST" -P"${DB_PORT:-3306}" -u"${DB_USER:-}" -N -e \
      "SELECT version FROM $DB_NAME.flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1" 2>/dev/null)"
    [ -n "$LATEST" ] && log "数据库最新迁移版本: $LATEST"
  fi
fi
cat <<EOF

升级前程序文件备份: $ROLLBACK
回滚方法（程序）:
  bash $APP_HOME/bin/standalone-stop-backend.sh
  cp -a $ROLLBACK/backend $ROLLBACK/frontend $ROLLBACK/worker $APP_HOME/
  bash $APP_HOME/bin/standalone-start-backend.sh
回滚方法（数据库，谨慎）:
  使用 db_export 下升级前最新备份恢复；恢复前先停后端。
健康检查:
  bash $APP_HOME/bin/standalone-health-check.sh
EOF
log "完成"
