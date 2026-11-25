#!/bin/bash
# metric-code 非容器化部署 —— 部署前预检（只读，不改任何文件）
#
# 用法：
#   bash bin/preflight-check.sh db      # 数据库预检（单机 67 本机执行）
#   bash bin/preflight-check.sh app     # 应用预检（单机 67 本机执行）
#   bash bin/preflight-check.sh         # 不指定角色，只检查公共项
#
# 目的：把所有「脚本中途才发现缺变量 / 缺命令 / 版本不对」的问题提前暴露，
# 输出 PASS / WARN / FAIL 清单，有 FAIL 时退出码非 0。
set -u

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$APP_HOME/config/app.env"
ROLE="${1:-all}"

# shellcheck source=standalone-lib.sh
. "$APP_HOME/bin/standalone-lib.sh"

pass_count=0
warn_count=0
fail_count=0
pass() { pass_count=$((pass_count + 1)); echo "[PASS] $*"; }
warn() { warn_count=$((warn_count + 1)); echo "[WARN] $*"; }
fail() { fail_count=$((fail_count + 1)); echo "[FAIL] $*"; }

echo "===== metric-code 部署预检（角色: $ROLE）====="
echo "部署目录: $APP_HOME"

echo
echo "--- 1. 主机环境 ---"
echo "      内核架构: $(uname -m) / $(uname -s -r)"
if [ -r /etc/os-release ]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  echo "      发行版: ${PRETTY_NAME:-未知}"
fi
AVAIL_KB=$(df -Pk "$APP_HOME" 2>/dev/null | awk 'NR==2 {print $4}')
if [ -n "${AVAIL_KB:-}" ] && [ "$AVAIL_KB" -gt 2097152 ]; then
  pass "剩余磁盘 $((AVAIL_KB / 1024 / 1024))GB（>2GB）"
else
  warn "剩余磁盘不足 2GB，jar+备份可能放不下（当前 ${AVAIL_KB:-未知}KB）"
fi

echo
echo "--- 2. 包完整性 ---"
[ -f "$APP_HOME/backend/metric-code.jar" ] && pass "后端 jar 存在" || fail "缺 backend/metric-code.jar"
[ -f "$APP_HOME/frontend/index.html" ] && pass "前端 index.html 存在" || fail "缺 frontend/index.html"
[ -d "$APP_HOME/repo/backend/src/main/resources/db/migration" ] && pass "迁移脚本目录存在" || fail "缺 repo/.../db/migration"
[ -f "$APP_HOME/repo/deploy/db_export/bootstrap/manifest.json" ] && pass "安装基线存在" || fail "缺安装基线 bootstrap"
[ -f "$APP_HOME/nginx/nginx-1.26.3.tar.gz" ] && pass "nginx 源码在包内" || warn "包内无 nginx 源码，install-nginx.sh 需联网下载"

echo
echo "--- 3. config/app.env ---"
if [ ! -f "$ENV_FILE" ]; then
  fail "缺 config/app.env（cp config/app.env.example config/app.env 后填写）"
else
  PERM=$(stat -c %a "$ENV_FILE" 2>/dev/null || echo "?")
  [ "$PERM" = "600" ] && pass "app.env 权限 600" || warn "app.env 权限是 $PERM，建议 chmod 600"
  if grep -qU $'\r' "$ENV_FILE"; then
    warn "app.env 含 CRLF 换行（脚本已兼容，但建议转成 LF）"
  fi
  load_app_env "$ENV_FILE" || fail "app.env 解析失败"

  echo "--- 4. 变量与占位符 ---"
  common_missing=""
  for var in DB_URL DB_USER DB_PASSWORD JAVA_HOME; do
    eval "[ -n \"\${$var:-}\" ]" || common_missing="$common_missing $var"
  done
  if [ -n "$common_missing" ]; then
    fail "缺少必填变量:$common_missing"
  else
    pass "公共必填变量齐全（DB_URL/DB_USER/DB_PASSWORD/JAVA_HOME）"
  fi

  if [ "$ROLE" = "app" ] || [ "$ROLE" = "all" ]; then
    resolve_nginx_port
    pass "NGINX_PORT=$NGINX_PORT（对外唯一入口，前端与 API 都走它）"
    for var in APP_PORT; do
      eval "[ -n \"\${$var:-}\" ]" && pass "$var=${!var:-}" || warn "未设置 $var（$ROLE 角色需要）"
    done
  fi

  # 占位符 / 弱口令
  for var in DB_PASSWORD; do
    eval "value=\"\${$var:-}\""
    case "$value" in
      *'<'*'>'*) fail "$var 仍是模板占位符，必须改成真实口令" ;;
      change-me*) fail "$var 仍是默认弱口令" ;;
      *) [ -n "$value" ] && pass "$var 已填写" ;;
    esac
  done

  if [ -n "${SSO_OIDC_CLIENT_ID:-}" ]; then
    pass "SSO 已配置 client-id（登录走统一认证）"
  else
    pass "SSO 未配置：按未启用处理，不影响启动（空值会被自动当作未配置）"
  fi

  if [ -n "${DB_URL:-}" ]; then
    # 先在子 shell 里试解析（parse_db_url 失败会 exit，不污染主流程）
    if ( parse_db_url "$DB_URL" ) >/dev/null 2>&1; then
      parse_db_url "$DB_URL"
      pass "DB_URL 解析: $DB_HOST:$DB_PORT/$DB_NAME"
    else
      fail "DB_URL 格式不对: ${DB_URL:-}"
    fi
  fi

  echo
  echo "--- 5. JDK ---"
  # JAVA_BIN 来自 standalone-lib.sh 顶部的可编辑变量，其次 JAVA_HOME，最后 PATH
  if [ -n "${JAVA_BIN:-}" ] && [ -x "$JAVA_BIN" ]; then
    JAVA_BIN_TEST="$JAVA_BIN"
  elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN_TEST="$JAVA_HOME/bin/java"
  else
    JAVA_BIN_TEST="$(command -v java 2>/dev/null)"
  fi
  if [ -n "${JAVA_BIN_TEST:-}" ]; then
    JAVA_VER=$("$JAVA_BIN_TEST" -version 2>&1 | head -1)
    JAVA_MAJOR=$(echo "$JAVA_VER" | sed -nE 's/.*version "(1\.)?([0-9]+).*/\2/p')
    if [ -n "${JAVA_MAJOR:-}" ] && [ "$JAVA_MAJOR" -ge 17 ]; then
      pass "JDK 17+ 可用: $JAVA_BIN_TEST ($JAVA_VER)"
    else
      fail "JDK 版本不足 17: $JAVA_BIN_TEST ($JAVA_VER)"
    fi
  else
    [ "$ROLE" = "app" ] && fail "找不到 java（JAVA_HOME 未指向 JDK17）" || warn "找不到 java（初始化数据库需要 JDK17+）"
  fi
fi

echo
echo "--- 6. 客户端与依赖 ---"
locate_mysql_client
if [ -n "$MYSQL_CLIENT" ]; then
  pass "mysql 客户端: $MYSQL_CLIENT ($("$MYSQL_CLIENT" --version 2>&1 | head -1))"
else
  [ "$ROLE" = "db" ] && fail "找不到 mysql 客户端" || warn "本机找不到 mysql 客户端（只影响空库/连通性检查）"
fi
if [ -n "$MYSQLDUMP_CLIENT" ]; then
  pass "mysqldump: $MYSQLDUMP_CLIENT"
else
  [ "$ROLE" = "db" ] && fail "找不到 mysqldump（备份与初始化都需要）" || warn "找不到 mysqldump"
fi
if command -v python3 >/dev/null 2>&1; then
  pass "python3: $(python3 --version 2>&1)"
else
  [ "$ROLE" = "db" ] && fail "缺 python3（数据库初始化需要）" || warn "缺 python3"
fi

if [ "$ROLE" = "app" ]; then
  echo
  echo "--- 7. 端口占用 ---"
  resolve_nginx_port
  for p in "${NGINX_PORT:-0}" "${APP_PORT:-0}"; do
    [ "$p" = "0" ] && continue
    if port_busy "$p"; then
      holder=$(ss -lntp 2>/dev/null | grep ":$p " | head -3)
      case "$holder" in
        *nginx*)
          warn "端口 $p 已被 nginx 占用（像是已部署过的入口；重装前先 bash bin/deploy-nginx.sh uninstall）"
          ;;
        *)
          fail "端口 $p 已被占用"
          echo "$holder" | sed 's/^/       /'
          ;;
      esac
    else
      pass "端口 $p 空闲"
    fi
  done

  echo
  echo "--- 8. nginx 形态 ---"
  if command -v nginx >/dev/null 2>&1 || [ -x /usr/sbin/nginx ]; then
    SYS_NGINX_BIN=$(command -v nginx 2>/dev/null || echo /usr/sbin/nginx)
    pass "复用模式：系统 nginx 可用 ($("$SYS_NGINX_BIN" -v 2>&1 | head -1))"
    [ -d /etc/nginx/conf.d ] && pass "conf.d 目录存在" || warn "无 /etc/nginx/conf.d，需手工把 server 块加进 nginx.conf"
    if [ -f /etc/nginx/conf.d/metric-code.conf ]; then
      warn "conf.d/metric-code.conf 已存在（上次部署残留），install-nginx.sh 会先备份再覆盖"
    fi
    # nginx worker 非 root，需要能读到前端目录
    resolve_frontend_root >/dev/null 2>&1 && {
      p="$FRONTEND_ROOT"; perm_bad=""
      while [ "$p" != "/" ]; do
        m=$(stat -c %a "$p" 2>/dev/null)
        case "$m" in *[1357]) ;; *) perm_bad="$perm_bad $p($m)" ;; esac
        p=$(dirname "$p")
      done
      [ -z "$perm_bad" ] && pass "前端目录对 nginx worker 可读" \
        || warn "这些目录其他用户无 x/r 权限，nginx worker 可能读不到:$perm_bad"
    }
  else
    warn "独立模式：本机无 nginx，install-nginx.sh 需源码编译"
    resolve_nginx_prefix
    if mkdir -p "$NGINX_PREFIX" 2>/dev/null && [ -w "$NGINX_PREFIX" ]; then
      pass "nginx 安装前缀可写: $NGINX_PREFIX"
    else
      fail "nginx 安装前缀不可写: $NGINX_PREFIX（make install 会 Permission denied；改 app.env 的 NGINX_PREFIX 或先授权）"
    fi
    for tool in gcc make tar; do
      command -v "$tool" >/dev/null 2>&1 && pass "$tool 可用" || warn "缺 $tool（编译 nginx 需要）"
    done
    for header in /usr/include/pcre2.h /usr/include/zlib.h /usr/include/openssl/ssl.h; do
      [ -f "$header" ] && pass "头文件 $header" || warn "缺头文件 $header（yum install -y pcre2-devel zlib-devel openssl-devel）"
    done
  fi
fi

if [ "$ROLE" = "db" ] && [ -n "${MYSQL_CLIENT:-}" ] && [ -n "${DB_URL:-}" ]; then
  echo
  echo "--- 7. 数据库连通（只读） ---"
  if MYSQL_PWD="${DB_PASSWORD:-}" "$MYSQL_CLIENT" -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" \
       --connect-timeout=10 -e "SELECT VERSION();" --batch --skip-column-names >/dev/null 2>&1; then
    pass "可以连接 $DB_HOST:$DB_PORT"
  else
    fail "连不上 $DB_HOST:$DB_PORT（检查网络、账号、口令）"
  fi
fi

echo
echo "===== 预检结果: PASS $pass_count / WARN $warn_count / FAIL $fail_count ====="
if [ "$fail_count" -gt 0 ]; then
  echo "存在 FAIL 项，先修好再执行部署脚本。" >&2
  exit 1
fi
echo "未发现阻断项，可以继续下一步。"
