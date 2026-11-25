#!/bin/bash
APP_NAME="metric-code"
JAR_FILE="../backend/metric-code-0.0.1-SNAPSHOT.jar"
LOG_DIR="../backend/logs"

# 创建日志目录
mkdir -p $LOG_DIR

# 加载环境配置（包含JDK17路径，不会影响其他JDK8应用）
cd $(dirname $0)
source ../config/env.conf

# 使用JDK17的java命令（绝对路径），避免使用系统默认JDK
$JAVA_HOME/bin/java -Xms512m -Xmx1024m \
  -jar $JAR_FILE \
  --spring.datasource.url=jdbc:mysql://$DB_HOST:$DB_PORT/$DB_NAME \
  --spring.datasource.username=$DB_USER \
  --spring.datasource.password=$DB_PASSWORD \
  --server.port=$APP_PORT \
  --upload.base-path=$UPLOAD_BASE_PATH \
  --logging.file.path=$LOG_DIR \
  --logging.file.name=$LOG_DIR/metric-code.log \
  > $LOG_DIR/stdout.log 2>&1 &

echo $! > $LOG_DIR/app.pid
echo "Started: $APP_NAME, PID: $(cat $LOG_DIR/app.pid)"
echo "Log: $LOG_DIR/metric-code.log"