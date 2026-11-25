# ============================================================
# metric-code 后端容器化构建
#   - Stage 1: 基于 Maven 镜像编译 jar
#   - Stage 2: 基于 JRE17 + Python3 + 数据处理全家桶运行
#
# 基础镜像默认走 docker.1ms.run 国内镜像源（docker.io 在国内 TLS 握手经常超时）
# 如需切换源(海外机/已配置 daemon 全局 mirror),通过 build-arg 覆盖:
#   docker build \
#     --build-arg MAVEN_IMAGE=maven:3.9-eclipse-temurin-17 \
#     --build-arg JRE_IMAGE=eclipse-temurin:17-jre-jammy \
#     -f deploy/docker/Dockerfile -t metric-code:xxx .
# ============================================================

ARG MAVEN_IMAGE=docker.1ms.run/library/maven:3.9-eclipse-temurin-17
ARG JRE_IMAGE=docker.1ms.run/library/eclipse-temurin:17-jre

# -------------------- Stage 1: Builder --------------------
FROM ${MAVEN_IMAGE} AS builder

WORKDIR /build

# Maven 走 Aliyun 镜像（中央仓库国内速度慢）
COPY deploy/maven-settings.xml /root/.m2/settings.xml

# 先单独 COPY pom.xml 让 Maven 依赖缓存可复用（代码变更不会触发依赖重下载）
COPY backend/pom.xml .
RUN mvn -B dependency:resolve dependency:resolve-plugins -DskipTests || true

# 再 COPY 源码并构建
COPY backend/src ./src
RUN mvn -B clean package -DskipTests

# Compile the database launcher with the same JDK/dependencies as the application.
# The final image only needs a JRE; production hosts need no JDK, Maven or copied JAR.
COPY deploy/database/DatabasePreparation.java /build/db-tool/DatabasePreparation.java
RUN mkdir -p /build/db-tool/deps /build/db-tool/classes && \
    cd /build/db-tool/deps && \
    jar -xf /build/target/metric-code.jar BOOT-INF/lib && \
    javac --release 17 -encoding UTF-8 -cp '/build/db-tool/deps/BOOT-INF/lib/*' \
      -d /build/db-tool/classes /build/db-tool/DatabasePreparation.java

# -------------------- Stage 2: Runtime --------------------
FROM ${JRE_IMAGE}

# 时区与系统依赖：python3 + pip + venv + curl(健康检查) + tzdata
# apt 走清华镜像（兼容 Ubuntu 22.04 sources.list 和 24.04 ubuntu.sources 两种格式）
RUN ( [ -f /etc/apt/sources.list ] && \
        sed -i 's@archive.ubuntu.com@mirrors.tuna.tsinghua.edu.cn@g; s@security.ubuntu.com@mirrors.tuna.tsinghua.edu.cn@g' /etc/apt/sources.list ; \
      [ -f /etc/apt/sources.list.d/ubuntu.sources ] && \
        sed -i 's@archive.ubuntu.com@mirrors.tuna.tsinghua.edu.cn@g; s@security.ubuntu.com@mirrors.tuna.tsinghua.edu.cn@g' /etc/apt/sources.list.d/ubuntu.sources ; \
      true ) && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv \
        tzdata curl ca-certificates default-mysql-client \
        # scipy/numpy/pandas 编译依赖
        gcc g++ libffi-dev libssl-dev \
        # 调试工具（容器内编辑 config / 看 log 需要）
        vim unzip less && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    rm -rf /var/lib/apt/lists/*

# Python 虚拟环境 + 重型全家桶（容器内统一 python3 入口指向虚拟环境）
ENV VENV_DIR=/opt/venv
RUN python3 -m venv $VENV_DIR
ENV PATH="$VENV_DIR/bin:$PATH"

# 用清华 pip 镜像加速；优先装大体积科学计算包
RUN pip install --no-cache-dir --upgrade pip \
        -i https://pypi.tuna.tsinghua.edu.cn/simple && \
    pip install --no-cache-dir \
        -i https://pypi.tuna.tsinghua.edu.cn/simple \
        numpy pandas scipy openpyxl python-dateutil pytz pymysql \
        requests matplotlib beautifulsoup4 lxml

WORKDIR /app

# 预创建上传/备份子目录结构（与现有 files/ 子目录对齐）
RUN mkdir -p \
        /app/files/ability \
        /app/files/alarm \
        /app/files/issue_attachments \
        /app/files/logos \
        /app/files/metric_backups \
        /app/files/raw_data \
        /app/files/scripts \
        /app/files/test_folder \
        /app/logs

# 从 builder 阶段拷贝 jar（finalName=metric-code）
COPY --from=builder /build/target/metric-code.jar /app/app.jar

# Immutable upgrade inputs; backups and the ledger are linked to the persistent files mount.
COPY deploy/scripts/database_support.py deploy/scripts/backup-database.py deploy/scripts/prepare-database.py /opt/metric-db/deploy/scripts/
COPY deploy/scripts/container-entrypoint.py /opt/metric-db/container-entrypoint.py
COPY deploy/database/DatabasePreparation.java /opt/metric-db/deploy/database/
COPY --from=builder /build/db-tool/classes /opt/metric-db/deploy/database/classes/
COPY --from=builder /build/src/main/resources/db/migration /opt/metric-db/backend/src/main/resources/db/migration/
COPY deploy/db_export/bootstrap /opt/metric-db/bootstrap/
COPY doc/数据库版本记录.md /opt/metric-db/backup-ledger.md
RUN mkdir -p /opt/metric-db/backend/target /opt/metric-db/doc && \
    ln -s /app/app.jar /opt/metric-db/backend/target/metric-code.jar

# 拷贝 appuser-agent 监控探针（默认安装到 /opt/appuser-agent）
#   - 启动时通过 -javaagent 注入到 JVM，采集运行时性能与异常
#   - 实例元数据目录 agentInfo/<name> 由 docker-compose 单独挂载卷以保留运行时数据
COPY deploy/appuser-agent /opt/appuser-agent
RUN mkdir -p /opt/appuser-agent/agentInfo && \
    chmod -R a+rX /opt/appuser-agent

# 数据卷：宿主机绑定挂载点（由 docker-compose 的 HOST_FILES_PATH 控制）
VOLUME ["/app/files", "/app/logs", "/opt/appuser-agent/agentInfo"]

EXPOSE 18080

# 健康检查
#   V42 引入 Spring Security 6 后，所有 /api/** 默认要鉴权。
#   - 原 /api/metric/statistics 命中后 401，healthcheck 永远失败
#   - /actuator/health 本项目未启用 actuator-starter，返回 404
#   - /api/session/config 是 SecurityConfig 白名单的 GET 端点，无需登录、返回 200，最适合做健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:18080/api/session/config > /dev/null || exit 1

# Prepare the database first; the entrypoint parses JAVA_OPTS and execs Java as PID 1.
ENTRYPOINT ["python3", "/opt/metric-db/container-entrypoint.py"]
