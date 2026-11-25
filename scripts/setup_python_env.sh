#!/bin/bash
# 设置 Python 虚拟环境并安装离线依赖包
# 在服务器内网环境执行

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WHEELHOUSE="${1:-$SCRIPT_DIR/../deploy/python/wheelhouse}"
VENV_DIR="${VENV_DIR:-$SCRIPT_DIR/../deploy/python/venv}"
mkdir -p "$(dirname "$VENV_DIR")"

echo "============================================"
echo "Python 环境离线安装脚本"
echo "============================================"
echo ""
echo "wheelhouse 目录: $WHEELHOUSE"
echo "虚拟环境目录: $VENV_DIR"
echo ""

# 检查 wheelhouse 是否存在
if [ ! -d "$WHEELHOUSE" ]; then
    echo "错误: wheelhouse 目录不存在: $WHEELHOUSE"
    echo "请先在有网络的机器上执行 download_python_deps.sh，然后上传 wheelhouse/ 目录到服务器"
    exit 1
fi

# 检查是否有 python3
if ! command -v python3 &> /dev/null; then
    echo "错误: python3 未安装"
    exit 1
fi

echo "步骤 1: 创建虚拟环境..."
if [ -d "$VENV_DIR" ]; then
    echo "  虚拟环境已存在，跳过创建"
else
    python3 -m venv "$VENV_DIR"
    echo "  创建完成"
fi

echo ""
echo "步骤 2: 激活虚拟环境..."
source "$VENV_DIR/bin/activate"

echo ""
echo "步骤 3: 安装依赖包..."
pip install --no-index --find-links="$WHEELHOUSE" -r /dev/stdin <<EOF
pandas
openpyxl
pymysql
python-dateutil
EOF

echo ""
echo "步骤 4: 验证安装..."
python -c "import pandas; import openpyxl; import pymysql; import dateutil; print('  所有依赖安装成功！')"

echo ""
echo "============================================"
echo "安装完成！"
echo "============================================"
echo ""
echo "使用方式:"
echo "  1. 激活虚拟环境: source $VENV_DIR/bin/activate"
echo "  2. 运行脚本时指定 python 路径: $VENV_DIR/bin/python3 your_script.py"
echo ""
echo "或者配置 application.yml:"
echo "  script.python.path: $VENV_DIR/bin/python3"
echo ""
