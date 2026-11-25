#!/bin/bash
# metric-code —— nginx 依赖体检（只读，不改任何东西）
#
# 用法：
#   bash bin/check-nginx-deps.sh
#
# 用途：上服务器后一条命令看清「要不要编译 nginx、编译缺什么」。
#   - 机器上已有 nginx → 走复用模式，压根不需要编译，工具链缺失也无所谓
#   - 没有 nginx → 必须能编译，脚本会列出缺的包并给出对应的 yum 命令
#
# 只读检查：不装包、不改配置、不启动任何服务。
set -u

ok()   { echo "[OK]   $*"; }
warn() { echo "[WARN] $*"; }
bad()  { echo "[MISS] $*"; }
sep()  { echo; echo "---- $* ----"; }

MISSING_TOOLS=""
MISSING_HEADERS=""
MISSING_PKGS=""

echo "===== nginx 依赖体检 ====="
echo "主机: $(uname -n 2>/dev/null)  内核: $(uname -s -r)  架构: $(uname -m)"
if [ -r /etc/os-release ]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  echo "系统: ${PRETTY_NAME:-未知}"
fi
echo "当前用户: $(id -un) (uid=$(id -u))"

# ---------------------------------------------------------------- 1
sep "1. 是否已有 nginx（决定要不要编译）"
SYS_NGINX=""
if command -v nginx >/dev/null 2>&1; then
  SYS_NGINX="$(command -v nginx)"
elif [ -x /usr/sbin/nginx ]; then
  SYS_NGINX="/usr/sbin/nginx"
fi
if [ -n "$SYS_NGINX" ]; then
  ok "已装 nginx: $SYS_NGINX ($("$SYS_NGINX" -v 2>&1 | head -1))"
  if pgrep -x nginx >/dev/null 2>&1; then
    ok "nginx 正在运行（复用模式直接加 conf.d 即可，无需编译）"
  else
    warn "nginx 已安装但当前没在运行"
  fi
  echo "     → 结论：走复用模式，下面的编译依赖可以不满足"
else
  warn "本机没有 nginx → 需要源码编译（或让运维装一个系统 nginx）"
fi

# ---------------------------------------------------------------- 2
sep "2. 编译工具"
for t in gcc make tar; do
  if command -v "$t" >/dev/null 2>&1; then
    case "$t" in
      gcc) ok "gcc: $(gcc --version 2>/dev/null | head -1)" ;;
      make) ok "make: $(make --version 2>/dev/null | head -1)" ;;
      *) ok "$t: $(command -v "$t")" ;;
    esac
  else
    bad "缺 $t"
    MISSING_TOOLS="$MISSING_TOOLS $t"
    MISSING_PKGS="$MISSING_PKGS $t"
  fi
done
# nginx 是纯 C，gcc-c++ 只在个别模块需要；有就报，没有不算缺
command -v g++ >/dev/null 2>&1 && ok "g++: $(g++ --version 2>/dev/null | head -1)" || warn "无 g++（nginx 本体不需要，可忽略）"

# ---------------------------------------------------------------- 3
sep "3. 依赖库头文件（编译必需的三个）"
# PCRE2：正则 location / rewrite 需要；老系统可能是 PCRE1
if [ -f /usr/include/pcre2.h ] || [ -f /usr/local/include/pcre2.h ]; then
  ok "pcre2 头文件: $(ls /usr/include/pcre2.h /usr/local/include/pcre2.h 2>/dev/null | head -1)"
elif [ -f /usr/include/pcre.h ]; then
  warn "只有 PCRE1 头文件 (/usr/include/pcre.h)：nginx 1.26 优先 PCRE2，编译时需显式 --with-pcre，建议装 pcre2-devel"
  MISSING_PKGS="$MISSING_PKGS pcre2-devel"
else
  bad "缺 pcre2 头文件（/usr/include/pcre2.h）"
  MISSING_HEADERS="$MISSING_HEADERS pcre2"
  MISSING_PKGS="$MISSING_PKGS pcre2-devel"
fi

# zlib：gzip 压缩需要
if [ -f /usr/include/zlib.h ]; then
  ok "zlib 头文件: /usr/include/zlib.h"
else
  bad "缺 zlib 头文件（/usr/include/zlib.h）"
  MISSING_HEADERS="$MISSING_HEADERS zlib"
  MISSING_PKGS="$MISSING_PKGS zlib-devel"
fi

# openssl：https / http_v2 需要
if [ -f /usr/include/openssl/ssl.h ]; then
  ok "openssl 头文件: /usr/include/openssl/ssl.h"
  command -v openssl >/dev/null 2>&1 && ok "openssl 版本: $(openssl version 2>/dev/null)"
else
  bad "缺 openssl 头文件（/usr/include/openssl/ssl.h）"
  MISSING_HEADERS="$MISSING_HEADERS openssl"
  MISSING_PKGS="$MISSING_PKGS openssl-devel"
fi

# 已装的 rpm 包（有 rpm 才查，纯参考）
sep "4. 已安装的相关 rpm（参考）"
if command -v rpm >/dev/null 2>&1; then
  for p in gcc make pcre2-devel pcre-devel zlib-devel openssl-devel; do
    v=$(rpm -q --qf '%{VERSION}-%{RELEASE}' "$p" 2>/dev/null)
    case "$v" in
      *not\ installed*|'') echo "  [未装] $p" ;;
      *) echo "  [已装] $p $v" ;;
    esac
  done
else
  warn "无 rpm 命令，跳过"
fi

# ---------------------------------------------------------------- 5
sep "5. 包管理器与源"
if command -v dnf >/dev/null 2>&1; then
  ok "包管理器: dnf"
  PKG_MGR="dnf"
elif command -v yum >/dev/null 2>&1; then
  ok "包管理器: yum"
  PKG_MGR="yum"
else
  bad "无 yum/dnf（离线精简系统），只能手工传 rpm 包"
  PKG_MGR=""
fi

if [ -n "${PKG_MGR:-}" ]; then
  # 只 ping 一个仓库，超时就当离线，避免卡住
  if timeout 8 "$PKG_MGR" -q repolist >/dev/null 2>&1; then
    ok "软件源可访问（可直接 $PKG_MGR install）"
    REPO_OK=1
  else
    warn "软件源不可用/超时（离线环境，需提前准备 rpm 或本地源）"
    REPO_OK=0
  fi
  # 顺手看看这几个包在源里有没有，有的话给出准确包名
  if [ "${REPO_OK:-0}" = "1" ]; then
    for p in pcre2-devel zlib-devel openssl-devel; do
      if timeout 15 "$PKG_MGR" -q list --available "$p" >/dev/null 2>&1; then
        echo "  源中可装: $p"
      fi
    done
  fi
fi

# ---------------------------------------------------------------- 6
sep "6. 结论"
if [ -n "$SYS_NGINX" ]; then
  echo "→ 复用模式：不需要编译。执行"
  echo "     bash bin/install-nginx.sh --apply"
  echo "   只新增 /etc/nginx/conf.d/metric-code.conf，不动既有站点。"
  exit 0
fi

if [ -z "$MISSING_TOOLS" ] && [ -z "$MISSING_HEADERS" ]; then
  echo "→ 依赖齐备，可以直接编译："
  echo "     bash bin/install-nginx.sh"
  exit 0
fi

echo "→ 编译缺这些：${MISSING_TOOLS}${MISSING_HEADERS}"
if [ -n "${PKG_MGR:-}" ] && [ "${REPO_OK:-0}" = "1" ]; then
  echo "   一条命令补齐："
  echo "     sudo $PKG_MGR install -y gcc make ${MISSING_PKGS// / }"
  echo "   （已装过的会自动跳过）"
else
  echo "   当前软件源不可用，需离线安装以下 rpm（含依赖）："
  echo "     ${MISSING_PKGS}"
  echo "   或让运维先装好系统 nginx，再走复用模式，就完全不需要编译。"
fi
exit 2
