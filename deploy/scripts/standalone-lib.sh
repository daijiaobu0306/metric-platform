#!/bin/bash
# 非容器化部署脚本共用库：安全解析 config/app.env + 统一的前置校验
#
# 为什么不用 `source app.env`：
#   - 值里带空格（如 JAVA_OPTS=-Xms512m -Xmx2g）会被拆成多条命令
#   - 口令含 $ ` ; & # 等字符时会被 shell 求值，轻则取错值重则执行了别的命令
#   这里按 KEY=VALUE 逐行解析，不做任何求值，只去掉可选的成对引号。

# ==========================================================================
# 【现场可改】JDK 与 MySQL 命令行路径 —— 改这里即可，所有脚本共用
#
#   JAVA_BIN       JDK 17 的 java 可执行文件（默认已写入现场 JDK17 路径，可直接改；
#                  该路径不可用时会告警并自动回退 $JAVA_HOME/bin/java → PATH 里的 java）
#                  例：/home/appuser/besinsight/component/jdk17/bin/java
#   JAVA_HOME      JDK 17 根目录（留空则按 JAVA_BIN 自动推导）
#                  例：/home/appuser/besinsight/component/jdk17
#   MYSQL_BIN      mysql 客户端（留空则自动探测：PATH → /usr/local/mysql/bin → component/mysql*/bin）
#                  例：/home/appuser/besinsight/component/mysql/bin/mysql
#   MYSQLDUMP_BIN  mysqldump 客户端（留空则同上）
#                  例：/home/appuser/besinsight/component/mysql/bin/mysqldump
#
#   这里显式填写的值优先于 config/app.env 里的同名变量：现场改脚本就能生效。
#   也可以不改脚本，直接用环境变量临时覆盖：
#     MYSQL_BIN=/xxx/bin/mysql MYSQLDUMP_BIN=/xxx/bin/mysqldump bash bin/xxx.sh
# ==========================================================================
JAVA_BIN="${JAVA_BIN:-/home/appuser/besinsight/component/jdk17/bin/java}"
JAVA_HOME="${JAVA_HOME:-/home/appuser/besinsight/component/jdk17}"
MYSQL_BIN="${MYSQL_BIN:-}"
MYSQLDUMP_BIN="${MYSQLDUMP_BIN:-}"

# 记住脚本顶部的显式取值：load_app_env 之后仍以这里为准
_LIB_JAVA_BIN_EDIT="$JAVA_BIN"
_LIB_JAVA_HOME_EDIT="$JAVA_HOME"
_LIB_MYSQL_BIN_EDIT="$MYSQL_BIN"
_LIB_MYSQLDUMP_BIN_EDIT="$MYSQLDUMP_BIN"

# 把脚本顶部的显式取值重新应用回当前环境（load_app_env 之后调用）
apply_path_overrides() {
  [ -n "${_LIB_JAVA_BIN_EDIT:-}" ] && JAVA_BIN="$_LIB_JAVA_BIN_EDIT"
  [ -n "${_LIB_JAVA_HOME_EDIT:-}" ] && JAVA_HOME="$_LIB_JAVA_HOME_EDIT"
  [ -n "${_LIB_MYSQL_BIN_EDIT:-}" ] && MYSQL_BIN="$_LIB_MYSQL_BIN_EDIT"
  [ -n "${_LIB_MYSQLDUMP_BIN_EDIT:-}" ] && MYSQLDUMP_BIN="$_LIB_MYSQLDUMP_BIN_EDIT"
  export JAVA_BIN JAVA_HOME MYSQL_BIN MYSQLDUMP_BIN
  return 0
}

# 读取 app.env 到当前 shell 环境（同名变量覆盖已有值）
load_app_env() {
  local env_file="$1" line key value
  [ -f "$env_file" ] || { echo "缺少配置文件: $env_file" >&2; return 1; }
  while IFS= read -r line || [ -n "$line" ]; do
    # 去掉行尾的 \r（app.env 在 Windows 编辑过时会有）
    line="${line%$'\r'}"
    case "$line" in
      ''|'#'*) continue ;;
      *'='*) ;;
      *) continue ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    # 去掉 key 两侧空白
    key="${key//[[:space:]]/}"
    # 去掉 value 两侧空白
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    # 去掉成对引号
    case "$value" in
      \"*\") value="${value#\"}"; value="${value%\"}" ;;
      \'*\') value="${value#\'}"; value="${value%\'}" ;;
    esac
    [ -z "$key" ] && continue
    export "$key=$value"
  done < "$env_file"
  return 0
}

die() {
  echo "错误: $*" >&2
  exit 1
}

# require_vars VAR1 VAR2 ... —— 缺任何一个都直接报出全部缺失项
require_vars() {
  local var missing=""
  for var in "$@"; do
    eval "[ -n \"\${$var:-}\" ]" || missing="$missing $var"
  done
  if [ -n "$missing" ]; then
    echo "错误: config/app.env 缺少必填变量:$missing" >&2
    echo "请对照 config/app.env.example 补齐后重试。" >&2
    exit 1
  fi
}

# reject_placeholders VAR1 VAR2 ... —— 拦住模板里没改的 <占位符> 和默认弱口令
reject_placeholders() {
  local var value
  for var in "$@"; do
    eval "value=\"\${$var:-}\""
    case "$value" in
      *'<'*'>'*) echo "错误: $var 仍是模板占位符（$value），请改成真实值" >&2; exit 1 ;;
      change-me|CHANGE-ME) echo "错误: $var 仍是默认弱口令，请改成真实值" >&2; exit 1 ;;
    esac
  done
}

# require_numeric VAR —— 端口之类的纯数字校验
require_numeric() {
  local var value
  var="$1"
  eval "value=\"\${$var:-}\""
  case "$value" in
    ''|*[!0-9]*) echo "错误: $var 必须是数字，当前值: '$value'" >&2; exit 1 ;;
  esac
}

# resolve_java —— 定位 JDK 17+，结果写回 JAVA_BIN，并打印版本
# 生产机系统默认 JDK8，用错版本启动只会报 UnsupportedClassVersionError，这里提前拦。
resolve_java() {
  local candidate version major
  apply_path_overrides
  JAVA_BIN=""
  # 0. 脚本顶部（或环境变量）显式指定的 java
  if [ -n "${_LIB_JAVA_BIN_EDIT:-}" ]; then
    candidate="$_LIB_JAVA_BIN_EDIT"
    if [ ! -x "$candidate" ]; then
      # 顶部写死的是现场路径，换机器时不一定存在：告警后继续自动探测，不直接中断
      echo "警告: 指定的 java 不存在或不可执行: $candidate（继续自动探测 JDK17）" >&2
    else
      version=$("$candidate" -version 2>&1 | head -1)
      major=$(echo "$version" | sed -nE 's/.*version "(1\.)?([0-9]+).*/\2/p')
      if [ "${major:-0}" -ge 17 ] 2>/dev/null; then
        JAVA_BIN="$candidate"
        echo "JDK: $JAVA_BIN ($version)"
        return 0
      fi
      echo "警告: 指定的 java 不是 JDK 17+: $candidate ($version)（继续自动探测 JDK17）" >&2
    fi
  fi
  for candidate in \
    "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
    "$(command -v java 2>/dev/null)"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    version=$("$candidate" -version 2>&1 | head -1)
    major=$(echo "$version" | sed -nE 's/.*version "(1\.)?([0-9]+).*/\2/p')
    [ -z "$major" ] && continue
    if [ "$major" -ge 17 ] 2>/dev/null; then
      JAVA_BIN="$candidate"
      echo "JDK: $JAVA_BIN ($version)"
      return 0
    fi
    echo "跳过非 17+ 的 JDK: $candidate ($version)" >&2
  done
  echo "错误: 找不到 JDK 17+。" >&2
  echo "请在 config/app.env 里把 JAVA_HOME 指向 JDK17 目录（示例：/home/appuser/besinsight/component/jdk17）" >&2
  echo "自检命令: \$JAVA_HOME/bin/java -version" >&2
  exit 1
}

# 从 jdbc:mysql URL 解析 host / port / database，结果写入 DB_HOST/DB_PORT/DB_NAME
parse_db_url() {
  local url="$1"
  case "$url" in
    jdbc:mysql://*) ;;
    *) die "DB_URL 必须以 jdbc:mysql:// 开头，当前值: $url" ;;
  esac
  DB_HOST=$(printf '%s\n' "$url" | sed -nE 's#^jdbc:mysql://([^:/?]+).*#\1#p')
  DB_PORT=$(printf '%s\n' "$url" | sed -nE 's#^jdbc:mysql://[^:/?]+:([0-9]+).*#\1#p')
  DB_NAME=$(printf '%s\n' "$url" | sed -nE 's#^jdbc:mysql://[^/]+/([^?]+).*#\1#p')
  [ -z "$DB_HOST" ] && die "无法从 DB_URL 解析主机: $url"
  [ -z "$DB_NAME" ] && die "DB_URL 里没有库名（应为 jdbc:mysql://host:port/库名?...）: $url"
  # 未写端口时 sed 不匹配，这里统一回落 3306，避免把整条 URL 当端口
  case "$DB_PORT" in ''|*[!0-9]*) DB_PORT=3306 ;; esac
}

# 定位 MySQL 客户端：生产机的 mysql 常装在 /usr/local/mysql/bin 但不在 PATH，
# 也可能装在 /usr、/opt 或自定义目录。这里逐级探测，最后全盘 find 兜底。
# 找到就写回 MYSQL_CLIENT / MYSQLDUMP_CLIENT，未找到则留空。
locate_mysql_client() {
  MYSQL_CLIENT=""
  MYSQLDUMP_CLIENT=""
  apply_path_overrides
  _find_mysql_binary() {
    local name="$1" candidate
    # 0. 显式指定优先（MYSQL_BIN / MYSQLDUMP_BIN）
    case "$name" in
      mysql) candidate="${MYSQL_BIN:-}" ;;
      mysqldump) candidate="${MYSQLDUMP_BIN:-}" ;;
    esac
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then echo "$candidate"; return 0; fi
    # 1. PATH
    candidate=$(command -v "$name" 2>/dev/null)
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then echo "$candidate"; return 0; fi
    # 2. 常见目录
    for candidate in \
      /usr/local/mysql/bin/"$name" /usr/local/bin/"$name" /usr/bin/"$name" /bin/"$name" \
      /opt/mysql/bin/"$name" /opt/mariadb/bin/"$name" /usr/local/mariadb/bin/"$name" \
      /usr/local/mysql-*/bin/"$name" /opt/mysql-*/bin/"$name" \
      /home/appuser/mysql/bin/"$name" /data/mysql/bin/"$name" \
      /home/appuser/besinsight/component/mysql/bin/"$name" \
      /home/appuser/besinsight/component/mysql*/bin/"$name" \
      /home/appuser/besinsight/component/bin/"$name"; do
      if [ -x "$candidate" ]; then echo "$candidate"; return 0; fi
    done
    # 3. 全盘查找（限制深度，跳过虚拟文件系统）
    candidate=$(find / -maxdepth 6 -type f -name "$name" -perm -u+x \
      -not -path '/proc/*' -not -path '/sys/*' -not -path '/dev/*' 2>/dev/null | head -1)
    if [ -n "$candidate" ]; then echo "$candidate"; return 0; fi
    return 1
  }
  MYSQL_CLIENT=$(_find_mysql_binary mysql) || MYSQL_CLIENT=""
  MYSQLDUMP_CLIENT=$(_find_mysql_binary mysqldump) || MYSQLDUMP_CLIENT=""
}

# 端口是否被监听（ss/netstat 都可能缺失，缺失时按“未监听”处理）
port_busy() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -lnt 2>/dev/null | grep -q ":$port "
  elif command -v netstat >/dev/null 2>&1; then
    netstat -lnt 2>/dev/null | grep -q ":$port "
  else
    return 1
  fi
}

# 前端静态目录：统一由 FRONTEND_ROOT 决定，默认用包内 frontend/
resolve_frontend_root() {
  FRONTEND_ROOT="${FRONTEND_ROOT:-$APP_HOME/frontend}"
  case "$FRONTEND_ROOT" in
    /*) ;;
    *) FRONTEND_ROOT="$APP_HOME/$FRONTEND_ROOT" ;;
  esac
  [ -f "$FRONTEND_ROOT/index.html" ] || {
    echo "错误: 前端目录不可用: $FRONTEND_ROOT（缺 index.html）" >&2
    echo "请确认包内 frontend/ 完整，或在 config/app.env 里把 FRONTEND_ROOT 指向正确的静态目录。" >&2
    exit 1
  }
}

# 统一入口端口：NGINX_PORT 优先，兼容老配置里的 FRONTEND_PORT，都没有则 8080。
# 语义已从「前端独立示例端口」变为「67 上对外唯一的代理端口」。
resolve_nginx_port() {
  NGINX_PORT="${NGINX_PORT:-${FRONTEND_PORT:-8080}}"
  case "$NGINX_PORT" in
    ''|*[!0-9]*)
      echo "错误: NGINX_PORT 必须是数字，当前值: $NGINX_PORT" >&2
      exit 1
      ;;
  esac
  export NGINX_PORT
}

# 空值 ≠ 未配置。Spring 的 ${X:默认值} 只在变量「不存在」时才用默认值；
# app.env 里写 SSO_OIDC_CLIENT_ID= 会把空串覆盖进 client-id，
# 启动直接失败：Client id must not be empty。这里把空的可选项按「未配置」处理。
drop_empty_optional_vars() {
  local name value
  for name in SSO_OIDC_CLIENT_ID SSO_OIDC_CLIENT_SECRET SSO_AUTH_URI SSO_TOKEN_URI \
              SSO_JWKS_URI SSO_USERINFO_URI SSO_OIDC_SCOPE SSO_JWT_SECRET \
              SSO_USERMANAGER_URL SSO_USERMANAGER_BROWSER_URL; do
    eval "value=\"\${$name:-}\""
    [ -z "$value" ] && unset "$name"
  done
  return 0
}

# 独立实例 nginx 的安装前缀。默认放在包目录下，避免 /opt 需要 root（make install 会因
# Permission denied 失败）。已装到别处的老环境用 NGINX_PREFIX 指回去即可。
resolve_nginx_prefix() {
  local default_prefix="$APP_HOME/lib/nginx"
  NGINX_PREFIX="${NGINX_PREFIX:-$default_prefix}"
  case "$NGINX_PREFIX" in
    /*) ;;
    *) NGINX_PREFIX="$APP_HOME/$NGINX_PREFIX" ;;
  esac
  export NGINX_PREFIX
}
