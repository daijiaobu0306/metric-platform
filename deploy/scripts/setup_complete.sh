#!/bin/bash
set -e

echo "===== 指标体系平台一键部署脚本 ====="

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.."

# 切换到部署包根目录
cd "$SCRIPT_DIR"

# 加载环境配置
if [ -f config/env.conf ]; then
    source config/env.conf
else
    echo "错误: 配置文件 config/env.conf 不存在"
    exit 1
fi

echo "===== 开始部署检查 ====="

# 1. 检查JDK17
echo "[1/7] 检查JDK17..."
if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "错误: 未检测到JDK17，请检查 JAVA_HOME=$JAVA_HOME"
    echo "请确保JDK17已安装并正确配置在config/env.conf中"
    exit 1
fi
echo "OK: JDK17 found at $JAVA_HOME"

# 2. 检查MySQL连接
echo "[2/7] 检查MySQL连接..."
if mysql -h $DB_HOST -u $DB_USER -p$DB_PASSWORD -e "SELECT 1" >/dev/null 2>&1; then
    echo "OK: MySQL连接正常"
else
    echo "错误: 无法连接MySQL，请检查DB_HOST/DB_USER/DB_PASSWORD配置"
    exit 1
fi

# 3. 创建数据库
echo "[3/7] 创建数据库..."
echo "OK: 数据库将在首次运行时自动创建"

# 4. 创建必要目录
echo "[4/7] 创建目录..."
mkdir -p backend/logs
mkdir -p $UPLOAD_BASE_PATH
echo "OK: 目录创建完成"

# 5. 部署前端
echo "[5/7] 部署前端..."
if [ -d frontend ]; then
    rm -rf /var/www/metric-code/frontend
    cp -r frontend /var/www/metric-code/frontend
    echo "OK: 前端部署完成"
else
    echo "警告: 前端构建产物 frontend 不存在，请先执行 npm run build"
fi

# 6. 配置Nginx
echo "[6/7] 配置Nginx (监听端口 ${FRONTEND_PORT})..."
if [ -f nginx/metric-code.conf ]; then
    cp nginx/metric-code.conf /etc/nginx/conf.d/metric-code.conf
    if nginx -t 2>/dev/null; then
        systemctl reload nginx
        echo "OK: Nginx配置已重载"
    else
        echo "警告: Nginx配置测试失败，请检查配置"
    fi
else
    echo "警告: Nginx配置文件不存在，跳过Nginx配置"
fi

# 7. 启动后端
echo "[7/7] 启动后端..."
cd bin
chmod +x start.sh stop.sh restart.sh
./start.sh
cd ..

echo ""
echo "===== 部署完成 ====="
echo "访问地址: http://服务器IP:${FRONTEND_PORT}/metric-code/"
echo "后端日志: backend/logs/metric-code.log"
echo ""
echo "常用命令:"
echo "  启动: ./bin/start.sh"
echo "  停止: ./bin/stop.sh"
echo "  重启: ./bin/restart.sh"
echo "  健康检查: ./bin/health_check.sh"