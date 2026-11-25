#!/bin/bash
# 下载 Python 依赖到本地目录（用于内网环境离线安装）
# 在有网络的机器上执行，将依赖包下载到 wheelhouse/ 文件夹

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WHEELHOUSE="${1:-$SCRIPT_DIR/../deploy/python/wheelhouse}"
mkdir -p "$WHEELHOUSE"

# 核心依赖
PACKAGES="pandas openpyxl pymysql python-dateutil numpy pytz six et-xmlfile packaging"

echo "开始下载 Python 依赖到 $WHEELHOUSE/ ..."

# 下载所有包及其依赖
pip download --no-deps -d "$WHEELHOUSE" $PACKAGES

# 下载所有依赖项
pip download -d "$WHEELHOUSE" $PACKAGES

echo ""
echo "下载完成！wheelhouse/ 目录内容:"
ls -la "$WHEELHOUSE/"
echo ""
echo "将 wheelhouse/ 目录上传到服务器后，执行:"
echo "  ./setup_python_env.sh /path/to/wheelhouse"
