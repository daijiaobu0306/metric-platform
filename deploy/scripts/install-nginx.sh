#!/bin/bash
# metric-code 统一入口 nginx —— 安装/配置
#
# 用法：
#   bash bin/install-nginx.sh              # 自动判断：有系统 nginx 就复用它，没有就编译独立实例
#   bash bin/install-nginx.sh --compile    # 强制编译独立实例（不想动系统 nginx 时用）
#   bash bin/install-nginx.sh --apply      # 复用模式下自动 sudo 安装到 conf.d 并 reload
#
# 两种模式：
#   A. 复用模式（67 上已有 nginx，推荐）
#      - 不改系统 nginx 的 nginx.conf，只新增一个 server 块（独立端口，不影响既有站点）
#      - 一个端口同时提供前端 /metric-code/ 与后端 /metric-code/api/
#   B. 独立模式（机器上没有 nginx）
#      - 源码编译到 $APP_HOME/lib/nginx（可用 app.env 的 NGINX_PREFIX 改路径）
#      - 不注册 systemd、不写 /opt，普通用户即可完成，全程不需要 sudo
#
# 端口由 app.env 的 NGINX_PORT 决定（默认 8080），不要手工改生成的配置。
set -u

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$APP_HOME/config/app.env"
PREFIX=""   # 由 resolve_nginx_prefix 决定（默认 $APP_HOME/lib/nginx）
WORKDIR="${TMPDIR:-/tmp}/nginx-build-$$"
NGINX_VERSION="1.26.3"
TARBALL="$APP_HOME/nginx/nginx-${NGINX_VERSION}.tar.gz"
TEMPLATE="$APP_HOME/nginx/metric-code-proxy.conf.template"
RENDERED="$APP_HOME/nginx/metric-code.conf"
WWW_ROOT="$APP_HOME/www"
NGINX_CONF_D="/etc/nginx/conf.d"
NGINX_TARGET="$NGINX_CONF_D/metric-code.conf"

MODE="auto"
APPLY=0
for arg in "$@"; do
  case "$arg" in
    --compile) MODE="compile" ;;
    --apply)   APPLY=1 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg" >&2; exit 1 ;;
  esac
done

# shellcheck source=standalone-lib.sh
. "$APP_HOME/bin/standalone-lib.sh"

sep() { echo; echo "==== $* ===="; }

sep "1. 检查配置"
[ -f "$ENV_FILE" ] || { echo "缺少配置文件 $ENV_FILE，请先 cp config/app.env.example config/app.env 并填写。" >&2; exit 1; }
load_app_env "$ENV_FILE" || exit 1
require_vars APP_PORT
require_numeric APP_PORT
resolve_nginx_port
resolve_nginx_prefix
PREFIX="$NGINX_PREFIX"
resolve_frontend_root
echo "对外代理端口: $NGINX_PORT"
echo "后端端口:     $APP_PORT"
echo "前端目录:     $FRONTEND_ROOT"

# 端口占用：如果已被占用，先判断是不是本应用的上一次残留
if port_busy "$NGINX_PORT"; then
  echo
  echo "端口 $NGINX_PORT 已被占用：" >&2
  ss -lntp 2>/dev/null | grep ":$NGINX_PORT " | sed 's/^/  /' >&2
  echo "请改 app.env 的 NGINX_PORT，或先释放该端口。" >&2
  exit 1
fi

# nginx worker 需要能读到前端目录；用 root 起的 master 会把 worker 降权成 nginx 用户
check_readable_by_others() {
  local p="$1" m
  while [ "$p" != "/" ]; do
    m=$(stat -c %a "$p" 2>/dev/null)
    case "$m" in
      *[1357]) ;;
      *) echo "警告: 目录 $p 权限 $m，nginx worker（非 root）可能读不到，建议 chmod o+rX" >&2 ;;
    esac
    p=$(dirname "$p")
  done
}

render_conf() {
  local log_dir="$1"
  if [ ! -f "$TEMPLATE" ]; then
    echo "错误: 缺配置模板 $TEMPLATE（包不完整）" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$RENDERED")"
  sed -e "s#__NGINX_PORT__#$NGINX_PORT#g" \
      -e "s#__APP_PORT__#$APP_PORT#g" \
      -e "s#__WWW_ROOT__#$WWW_ROOT#g" \
      -e "s#__LOG_DIR__#$log_dir#g" \
      "$TEMPLATE" > "$RENDERED"
  echo "已生成: $RENDERED"
}

# nginx 用 root 映射 URL 前缀：$WWW_ROOT/metric-code 软链到包内 frontend/，
# 这样升级只替换 frontend/，软链与配置都不用动。
# 不用 alias：alias 配 try_files 在深层路由会内部重定向循环，返回 500。
prepare_www() {
  local link="$WWW_ROOT/metric-code"
  mkdir -p "$WWW_ROOT"
  if [ "$FRONTEND_ROOT" != "$link" ]; then
    # 旧软链每次重建；旧的真实目录（上次复制降级留下的）先清掉
    [ -L "$link" ] && rm -f "$link"
    [ -d "$link" ] && [ ! -L "$link" ] && rm -rf "$link"
    if ln -sfn "$FRONTEND_ROOT" "$link" 2>/dev/null && [ -f "$link/index.html" ]; then
      echo "静态根目录: $WWW_ROOT（metric-code → $FRONTEND_ROOT）"
    else
      # 文件系统不支持软链（部分挂载/受限账号）时降级为复制，代价是升级后要重跑本脚本
      echo "软链不可用，降级为复制静态文件（升级 frontend/ 后需重跑 install-nginx.sh）"
      mkdir -p "$link"
      cp -a "$FRONTEND_ROOT"/. "$link"/
    fi
  fi
  [ -f "$link/index.html" ] || {
    echo "错误: $link/index.html 不可访问，检查目录与权限" >&2
    exit 1
  }
}

# ---------------------------------------------------------------- 模式 A
SYS_NGINX=""
if command -v nginx >/dev/null 2>&1; then
  SYS_NGINX="$(command -v nginx)"
elif [ -x /usr/sbin/nginx ]; then
  SYS_NGINX="/usr/sbin/nginx"
fi

if [ "$MODE" != "compile" ] && [ -n "$SYS_NGINX" ]; then
  sep "2. 复用已有 nginx"
  echo "检测到系统 nginx: $SYS_NGINX"
  "$SYS_NGINX" -v 2>&1 | sed 's/^/  /'
  echo "只新增 conf.d/metric-code.conf，不改动 nginx.conf 与既有站点。"

  LOG_DIR="/var/log/nginx"
  [ -d "$LOG_DIR" ] || LOG_DIR="$APP_HOME/logs"
  mkdir -p "$LOG_DIR" 2>/dev/null
  prepare_www
  render_conf "$LOG_DIR"
  check_readable_by_others "$WWW_ROOT"
  check_readable_by_others "$FRONTEND_ROOT"

  # 语法预校验：放到临时 conf.d 里试，失败立刻删掉，不污染系统配置
  sep "3. 校验配置"
  CAN_TEST=0
  if [ "$(id -u)" = "0" ] || sudo -n true 2>/dev/null; then CAN_TEST=1; fi

  if [ "$CAN_TEST" = "0" ]; then
    echo "当前用户无 sudo 免密权限，无法自动校验与安装。请在 67 上手工执行：" >&2
    echo "  sudo cp $RENDERED $NGINX_TARGET" >&2
    echo "  sudo nginx -t && sudo nginx -s reload" >&2
    echo
    echo "生成好的配置：$RENDERED"
    exit 0
  fi

  SUDO=""
  [ "$(id -u)" = "0" ] || SUDO="sudo"
  if [ "$APPLY" = "0" ]; then
    echo "配置已生成，但未安装（加 --apply 可自动装）。手工执行："
    echo "  $SUDO cp $RENDERED $NGINX_TARGET"
    echo "  $SUDO nginx -t && $SUDO nginx -s reload"
    exit 0
  fi

  # 备份同名旧配置，避免覆盖别人手工改过的版本
  if [ -f "$NGINX_TARGET" ]; then
    BAK="$NGINX_TARGET.bak.$(date +%Y%m%d%H%M%S)"
    $SUDO cp -f "$NGINX_TARGET" "$BAK" && echo "已备份旧配置: $BAK"
  fi
  $SUDO cp -f "$RENDERED" "$NGINX_TARGET" || { echo "复制配置失败" >&2; exit 1; }
  if ! $SUDO "$SYS_NGINX" -t 2>&1 | sed 's/^/  /'; then
    echo "nginx -t 校验失败，已回滚（删除 $NGINX_TARGET）" >&2
    $SUDO rm -f "$NGINX_TARGET"
    exit 1
  fi
  if pgrep -x nginx >/dev/null 2>&1; then
    $SUDO "$SYS_NGINX" -s reload && echo "已 reload 系统 nginx"
  else
    $SUDO "$SYS_NGINX" && echo "系统 nginx 未运行，已启动"
  fi
  echo
  echo "完成。访问入口: http://<67的IP>:$NGINX_PORT/metric-code/"
  echo "启停与状态: bash bin/deploy-nginx.sh status|reload|uninstall"
  exit 0
fi

# ---------------------------------------------------------------- 模式 B
sep "2. 编译独立 nginx"
echo "未检测到系统 nginx（或指定了 --compile），改用独立实例。"
echo "前缀: $PREFIX"

# 装到没有写权限的目录会在 make install 阶段才报 Permission denied，
# 白等一两分钟编译。这里先探一次，不行立刻给可执行的解决办法。
if ! mkdir -p "$PREFIX" 2>/dev/null || [ ! -w "$PREFIX" ]; then
  echo "错误: 没有 $PREFIX 的写权限（make install 会失败）。" >&2
  echo "解决方式（任选一种，推荐第一种）：" >&2
  echo "  1) 装到当前用户目录：在 config/app.env 里加" >&2
  echo "       NGINX_PREFIX=$APP_HOME/lib/nginx" >&2
  echo "  2) 仍装到该路径：先建目录并授权" >&2
  echo "       sudo mkdir -p '$PREFIX' && sudo chown -R \$(id -u):\$(id -g) '$(dirname "$PREFIX")'" >&2
  echo "  3) 用 root 整体安装（之后需把目录 chown 回来，否则启停脚本写不了 pid/log）" >&2
  echo "       sudo -E bash bin/install-nginx.sh" >&2
  exit 1
fi

ARCH=$(uname -m)
echo "架构: $ARCH"
case "$ARCH" in
  aarch64|arm64) ;;
  *) echo "提示: 当前架构 $ARCH，nginx 由本机源码编译，需确认 gcc 可用。" ;;
esac

for tool in gcc make tar; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "缺少 $tool，无法编译 nginx。" >&2
    echo "BCLinux/aarch64 安装编译依赖: yum install -y gcc gcc-c++ make pcre2-devel zlib-devel openssl-devel tar" >&2
    exit 1
  fi
done

sep "3. 准备源码"
mkdir -p "$WORKDIR"
cd "$WORKDIR" || exit 1
if [ -f "$TARBALL" ]; then
  echo "使用包内源码: $TARBALL"
  tar -xzf "$TARBALL"
else
  echo "包内无源码，尝试下载..."
  URL="https://nginx.org/download/nginx-${NGINX_VERSION}.tar.gz"
  if ! curl -sSL --max-time 300 -o "nginx-${NGINX_VERSION}.tar.gz" "$URL"; then
    echo "下载失败：$URL（离线环境请把源码包放到 $TARBALL）" >&2
    exit 1
  fi
  tar -xzf "nginx-${NGINX_VERSION}.tar.gz"
fi
SRC="$WORKDIR/nginx-${NGINX_VERSION}"
[ -d "$SRC" ] || { echo "源码目录不存在: $SRC" >&2; exit 1; }

sep "4. 配置编译参数"
# 不要加 --without-http_rewrite_module：配置里的 `return 302` 属于 rewrite 模块，
# 去掉后 nginx -t 报 unknown directive "return"，直接起不来。
cd "$SRC" || exit 1
./configure \
  --prefix="$PREFIX" \
  --sbin-path="$PREFIX/sbin/nginx" \
  --conf-path="$PREFIX/conf/nginx.conf" \
  --pid-path="$PREFIX/run/nginx.pid" \
  --error-log-path="$PREFIX/logs/error.log" \
  --http-log-path="$PREFIX/logs/access.log" \
  --with-http_ssl_module \
  --with-http_v2_module \
  --with-http_realip_module \
  --with-http_stub_status_module \
  --with-threads \
  --with-cc-opt="-O2" \
  > "$WORKDIR/configure.log" 2>&1
if [ $? -ne 0 ]; then
  echo "configure 失败，末尾日志：" >&2
  tail -20 "$WORKDIR/configure.log" >&2
  echo "常见原因：缺 pcre2-devel（正则 location 需要）、zlib-devel（gzip）、openssl-devel（ssl 模块）。" >&2
  echo "BCLinux: yum install -y gcc gcc-c++ make pcre2-devel zlib-devel openssl-devel" >&2
  exit 1
fi

sep "5. 编译"
make -j"$(nproc)" > "$WORKDIR/make.log" 2>&1
if [ $? -ne 0 ]; then
  echo "编译失败，末尾日志：" >&2
  tail -30 "$WORKDIR/make.log" >&2
  exit 1
fi
make install >> "$WORKDIR/make.log" 2>&1
if [ $? -ne 0 ]; then
  echo "安装失败，末尾日志：" >&2
  tail -20 "$WORKDIR/make.log" >&2
  exit 1
fi

echo "nginx 已安装到 $PREFIX"
"$PREFIX/sbin/nginx" -v 2>&1

sep "6. 生成 nginx.conf"
mkdir -p "$PREFIX/conf" "$PREFIX/logs" "$PREFIX/run" "$PREFIX/temp"
prepare_www
cat > "$PREFIX/conf/nginx.conf" <<EOF
# metric-code 统一入口（独立实例）
# 由 install-nginx.sh 生成，改端口请改 app.env 的 NGINX_PORT 后重跑。

worker_processes  2;
error_log  $PREFIX/logs/error.log warn;
pid        $PREFIX/run/nginx.pid;

events {
    worker_connections  1024;
}

http {
    include       mime.types;
    default_type  application/octet-stream;
    sendfile      on;
    keepalive_timeout  65;

    gzip              on;
    gzip_vary         on;
    gzip_comp_level   5;
    gzip_min_length   1k;
    gzip_types        text/plain text/css application/javascript application/json image/svg+xml;

    client_body_temp_path $PREFIX/temp/client_body;
    proxy_temp_path       $PREFIX/temp/proxy;
    fastcgi_temp_path     $PREFIX/temp/fastcgi;
    uwsgi_temp_path       $PREFIX/temp/uwsgi;
    scgi_temp_path        $PREFIX/temp/scgi;

    server {
        listen       $NGINX_PORT;
        server_name  _;

        charset utf-8;
        client_max_body_size 100M;

        access_log $PREFIX/logs/access.log;
        error_log  $PREFIX/logs/error.log;

        # 前端静态：URI 前缀 /metric-code/ 与磁盘 $WWW_ROOT/metric-code 对齐（软链到 frontend/）
        location /metric-code/assets/ {
            root $WWW_ROOT;
            expires 30d;
            add_header Cache-Control "public, immutable";
            access_log off;
        }

        location /metric-code/ {
            root $WWW_ROOT;
            index index.html;
            try_files \$uri \$uri/ /metric-code/index.html;
        }

        location /metric-code/api/ {
            proxy_pass http://127.0.0.1:$APP_PORT/api/;

            proxy_http_version 1.1;
            proxy_set_header Host \$host;
            proxy_set_header X-Real-IP \$remote_addr;
            proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto \$scheme;
            proxy_set_header X-Forwarded-Port \$server_port;

            proxy_connect_timeout 60s;
            proxy_send_timeout    300s;
            proxy_read_timeout    300s;

            proxy_buffering off;
            proxy_request_buffering off;
        }

        location /oauth2/ {
            proxy_pass http://127.0.0.1:$APP_PORT;
            proxy_http_version 1.1;
            proxy_set_header Host \$http_host;
            proxy_set_header X-Real-IP \$remote_addr;
            proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto \$scheme;
            proxy_set_header X-Forwarded-Port \$server_port;
        }

        location /login/oauth2/ {
            proxy_pass http://127.0.0.1:$APP_PORT;
            proxy_http_version 1.1;
            proxy_set_header Host \$http_host;
            proxy_set_header X-Real-IP \$remote_addr;
            proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto \$scheme;
            proxy_set_header X-Forwarded-Port \$server_port;
        }

        location = / {
            return 302 /metric-code/;
        }
    }
}
EOF

sep "7. 校验配置"
if ! "$PREFIX/sbin/nginx" -t -c "$PREFIX/conf/nginx.conf" -p "$PREFIX" 2>&1; then
  echo "nginx 配置校验失败，已终止（未启动）。请把上面 -t 的报错回传。" >&2
  exit 1
fi

sep "8. 自检前端文件"
for f in index.html favicon.svg; do
  [ -f "$FRONTEND_ROOT/$f" ] || echo "警告: 缺少 $FRONTEND_ROOT/$f，页面可能不完整" >&2
done
[ -d "$FRONTEND_ROOT/assets" ] || echo "警告: 缺少 $FRONTEND_ROOT/assets 目录" >&2

rm -rf "$WORKDIR"

echo
echo "nginx 编译安装完成（独立实例，端口 $NGINX_PORT）。"
echo "下一步: bash bin/deploy-nginx.sh start"
