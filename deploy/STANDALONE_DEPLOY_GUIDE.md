# metric-code 部署说明（单机，无 Docker）

> **拓扑（2026-11-21 更新，以此为准）**：数据库 MySQL 与平台（nginx + 后端 + 前端 + 采集执行器）部署在**同一台 67 服务器**，库地址用本机 `127.0.0.1:3306`。原 66 数据库服务器已停用下线，不再单独部署数据库。生产升级仍以 [生产升级与部署操作手册](生产升级与部署操作手册.md) 为准。

## 0. 快速开始

单机解包，**只改 `config/app.env` 里的值，不碰任何脚本**。

```bash
tar -xzf metric-code-standalone-<时间戳>.tar.gz && cd metric-code-standalone-<时间戳>
cp config/app.env.example config/app.env && chmod 600 config/app.env
vi config/app.env                          # 填 DB_PASSWORD、JAVA_HOME、APP_PORT、NGINX_PORT，DB_URL 用 127.0.0.1
bash bin/preflight-check.sh app            # FAIL 必须先修
bash bin/preflight-check.sh db             # 数据库预检同样在本机执行
METRIC_ADMIN_PASSWORD='<首个管理员口令>' bash bin/init-database-server.sh   # 建库 + 导基线 + 跑迁移
bash bin/check-nginx-deps.sh               # 有系统 nginx → 免编译
bash bin/install-nginx.sh --apply          # 只加 conf.d 一个文件，不动既有站点
bash bin/standalone-start-backend.sh       # 首次约 70 秒
bash bin/install-worker.sh                 # 采集执行器（不装则采集一直「等待执行器」）
bash bin/standalone-health-check.sh
```

访问 `http://192.168.1.11:8080/metric-code/`。

| 机器 | 角色 | 只做这些 |
|---|---|---|
| 192.168.1.11 (aarch64 / BCLinux) | 单机（数据库 + 应用） | MySQL 建库/导基线/跑迁移，nginx 入口、后端 jar、前端静态、采集执行器 |
| 192.168.1.10 | **已停用下线** | 原数据库服务器，不再使用；不要在其中部署或备份 |

执行顺序：**先初始化数据库，再装 nginx、启动后端、装采集执行器**。后端启动时会校验库已初始化。

---

## 1. 包内容

```
metric-code-standalone-<时间戳>/
├── BUILD_INFO.txt                构建时间、git commit、jar 与前端指纹
├── backend/metric-code.jar       后端（Spring Boot fat jar）
├── frontend/                     前端静态文件（已裁剪到当前构建可达的 87 个资源，5.4MB）
├── nginx/
│   ├── nginx-1.26.3.tar.gz                源码包，独立模式在目标机本地编译
│   └── metric-code-proxy.conf.template    复用模式模板（渲染后放 conf.d）
├── bin/
│   ├── standalone-lib.sh              共用库：解析 app.env、变量校验、JDK 校验、定位 mysql
│   ├── preflight-check.sh            ★ 部署前预检（db / app 两种角色）
│   ├── check-nginx-deps.sh           ★ nginx 依赖体检（有 nginx 就免编译）
│   ├── init-database-server.sh       建库 + 导基线 + 跑迁移（单机首次）
│   ├── install-nginx.sh              配置统一入口 nginx（有则复用，无则编译）
│   ├── deploy-nginx.sh               nginx 状态/重载/启停/卸载入口
│   ├── install-worker.sh             ★ 采集执行器安装（生成令牌 + systemd）
│   ├── standalone-start-backend.sh   启动后端
│   ├── standalone-stop-backend.sh    停止后端
│   ├── standalone-health-check.sh     健康检查
│   ├── standalone-backup-database.sh  变更前数据库备份
├── config/app.env.example        运行配置模板（单机一份）
├── worker/                       采集执行器（Python，独立进程，collection-python 原样打包）
└── repo/                         数据库初始化所需（迁移脚本 + 安装基线 + 工具）
```

校验包完整性：

```bash
sha256sum -c metric-code-standalone-<时间戳>.tar.gz.sha256
```

---

## 2. 准备配置（单机一份）

```bash
tar -xzf metric-code-standalone-<时间戳>.tar.gz
cd metric-code-standalone-<时间戳>
cp config/app.env.example config/app.env
chmod 600 config/app.env
vi config/app.env      # 把 <> 占位符全部改成真实值；DB_URL 用 127.0.0.1
```

| 变量 | 是否必填 | 说明 |
|---|---|---|
| `DB_URL` | 必填 | 必须 `jdbc:mysql://127.0.0.1:3306/…`；脚本靠它解析主机/端口/库名，缺端口默认 3306 |
| `DB_USER` / `DB_PASSWORD` | 必填 | 填 `<...>` 占位符会被脚本拒绝执行 |
| `JAVA_HOME` | 必填 | 必须 17+，脚本会跑 `java -version` 校验，不符直接退出 |
| `APP_PORT` | 必填 | 默认 18090，不占生产既有 18080 |
| `NGINX_PORT` | 必填 | 默认 `8080`，对外唯一入口（前端 + API 都走它）；18080 被生产后端占用，不要用 |
| `FRONTEND_ROOT` | 可选 | 不填则用包内 `frontend/`（推荐） |
| `JAVA_OPTS` | 可选 | 默认 `-Xms512m -Xmx2g -XX:+UseG1GC` |
| `MYSQL_BIN` / `MYSQLDUMP_BIN` | 可选 | 一般不用填：已自动搜 `component/mysql*/bin` 等目录，只有定位错了才显式指定 |
| `SSO_*` | 可选 | **不接统一认证就整行注释掉，不要写成 `SSO_OIDC_CLIENT_ID=` 空值**，否则启动报 `Client id must not be empty.` |

> `app.env` 由自定义解析器读取，值里的空格和 `#` `$` `!` 不会被 shell 求值，口令无需转义。

### 已确认的环境事实（2026-11-12 核对；2026-11-21 起两台合为一台）

| 项 | 67 单机 |
|---|---|
| MySQL | 服务端 8.0，客户端同为 8.0（无 `caching_sha2_password` 兼容问题）；与平台同机 |
| 目标库 | `system_operations_metrics`；新环境为空库可直接初始化，已有环境按升级流程处理，禁止重放基线 |
| 连库账号 | `root`，本机连接即可，无需开放远程 |
| JDK 17 | `/home/appuser/besinsight/component/jdk17` |
| mysql 客户端 | `/home/appuser/besinsight/component/` 下（`mysql*/bin/`），脚本已加入自动定位目录 |
| 对外端口 | **8080** 统一入口（前端 + API）；18080 被生产后端占用；只开这一个新端口 |
| 磁盘 | 数据库、备份与平台共用本机磁盘，备份目录要单独规划并检查剩余空间 |

> mysql 客户端一般无需手填 `MYSQL_BIN`：脚本的常见目录列表已包含 `component/mysql/bin` 与 `component/mysql*/bin`，命中即用，不再走全盘 `find` 兜底。

---

## 3. 部署前预检（强烈建议，只读）

```bash
bash bin/preflight-check.sh app    # 应用与端口、nginx 依赖
bash bin/preflight-check.sh db     # 数据库、客户端、python3、连通性（同机执行）
```

输出 `[PASS]/[WARN]/[FAIL]` 清单；有 `FAIL` 会以非 0 退出，先修好再往下走。
检查项：包完整性、`app.env` 权限与变量、占位符、JDK 17+、mysql/mysqldump、python3（db）、端口占用（app）、nginx 编译依赖（app）、数据库连通（db）。

---

## 4. 初始化数据库（本机）

```bash
METRIC_ADMIN_PASSWORD='<首个管理员口令，至少10位>' bash bin/init-database-server.sh
```

流程：确认/创建目标库 → 校验库为空（非空直接拒绝）→ 库默认字符集归一为 `utf8mb4`（库已存在但可能是 latin1，空库阶段幂等修正）→ 导入安装基线 → Flyway 把基线登记为 `20261112105823`（当前基线已含到该版本的全部结构，增量迁移为 0 条）→ 记录备份台账。

前置：mysql 客户端、mysqldump、python3、JDK 17+（`JAVA_HOME` 会被传给下层 Python 工具，不会再退回 JDK8）。

核对：

```bash
mysql -h 127.0.0.1 -u root -p -e \
 "SELECT count(*) FROM information_schema.tables WHERE table_schema='system_operations_metrics';"
```

---

## 5. 配置 nginx（统一入口端口）

**思路**：67 上已经监听了很多端口，不再新增第二个对外端口。由一个 nginx 端口统一提供
前端 `/metric-code/` 和后端 `/metric-code/api/`，其余端口保持不变。

先体检依赖（只读，10 秒内出结论）：

```bash
bash bin/check-nginx-deps.sh
```

它先判断机器上有没有 nginx：**有就是复用模式，编译工具缺不缺都无所谓**；没有才检查
`gcc/make` 与 `pcre2-devel/zlib-devel/openssl-devel` 三个头文件，并给出对应的 `yum` 命令或离线 rpm 清单。

```bash
bash bin/install-nginx.sh --apply
```

脚本自动判断两种形态：

| 形态 | 触发条件 | 脚本行为 |
|---|---|---|
| **A. 复用模式（推荐）** | 67 上有 `nginx` | 只新增 `/etc/nginx/conf.d/metric-code.conf`，**不改 nginx.conf、不动既有站点**；`--apply` 自动 `cp` + `nginx -t` + `reload`；失败自动删除该文件回滚 |
| **B. 独立模式** | 机器上没有 nginx | 源码编译到 `$APP_HOME/lib/nginx`（`NGINX_PREFIX` 可改），**普通用户即可，不需要 sudo** |
| 强制独立 | `--compile` | 不想动系统 nginx 时用 |

不加 `--apply` 时只渲染配置并打印手工命令（无 sudo 免密权限时也走这条）。

编译依赖（仅形态 B 需要，BCLinux / aarch64）：

```bash
yum install -y gcc gcc-c++ make pcre2-devel zlib-devel openssl-devel tar
```

端口取 `app.env` 的 `NGINX_PORT`（默认 `8080`）。**不要用 18080**——那是生产既有后端端口。

## 6. 启动后端与前端

```bash
bash bin/standalone-start-backend.sh      # 首次约 70 秒
bash bin/deploy-nginx.sh start            # 复用模式=reload 系统 nginx；独立模式=启动实例
```

访问：`http://192.168.1.11:8080/metric-code/`（`/` 会 302 跳到该地址）

启动脚本会依次拦：缺变量 → 占位符 → JDK 版本 → jar 缺失 → 端口被占 → 目标库为空（空库需输入 YES 确认，防 V1 清库）。

## 7. 安装采集执行器

**不装这步，页面上的采集任务永远停在「等待执行器」**：采集是独立 Python 进程领任务执行的，后端只负责派发。

```bash
bash bin/install-worker.sh
```

它会：校验 python3（3.9+）→ 生成/复用 `COLLECTION_WORKER_TOKEN`（<32 字符视为无效）→ 写 systemd 单元并 `enable --now` → 打印状态。

若令牌是新生成的，**必须重启后端**才认：
```bash
bash bin/standalone-stop-backend.sh && bash bin/standalone-start-backend.sh
```

验证：`journalctl -u metric-collection-worker -f` 出现 `Worker started`，页面点一次采集，状态从「等待执行器」变为「执行中」。

### 7.1 模拟数据生成器（可选，独立程序）

没有真实来源的四类（业务价值12 / 对外部客户19 / 资源投入3 / 运行效率51，共85项）由独立生成器投递 CSV，不建平台任务、不改规则：

```bash
# 随包一起传到 67，解包后先预演再安装
tar -xzf metric-mock-20261112.tar.gz -C /home/appuser
cd /home/appuser/metric-mock-20261112
bash bin/install.sh --dry-run --files-root /home/appuser/metric-code   # 核对4个任务目录
bash bin/install.sh --files-root /home/appuser/metric-code             # 装 metric-mock.service
bash bin/status.sh                                                     # metrics 应为 85
```

路径以 `app.env` 的 `UPLOAD_BASE_PATH` 为准；目录名与默认不一致时加 `--dir system_run=<相对路径>`。满意度已有真实数据，该生成器不再生成 `MetricData*.csv`。详细说明见包内 `README.md`。

### 文件根目录长什么样

`UPLOAD_BASE_PATH`（如 `/home/appuser/metric-code`）下只有四类东西：

```
metric-code/
├── raw_data/
│   ├── import/<任务code>/     ← 采集任务扫描这里取文件
│   └── archive/<任务code>/    ← 处理完归档到这里
└── collection-scripts/
    └── releases/<版本号>/     ← 平台发布脚本时生成，Worker 按版本执行
```

- `import/` 下不再有 `scripts/` 中间层（迁移 `V20261113101000` 拍平）。
- 目录重叠校验只针对**启用中**的通道：`metric_import_config` 的 16 条旧通道全部 `enabled=0` 且 Java 解析器已下线，不再参与冲突判定；一旦重新启用，重叠仍会被拦下。
- `collection-scripts/releases/<版本号>/` 保留两级：任务绑定版本号，历史版本要能回滚。
- 根目录不再自动生成空的 `scripts/`（旧脚本上传通道已不用，改为上传时才创建）。

## 8. 验证

```bash
bash bin/standalone-health-check.sh
bash bin/deploy-nginx.sh status     # 形态 + 端口 + 首页 + 反代四项
```

## 9. 常用命令

```bash
bash bin/standalone-start-backend.sh      # 启动后端
bash bin/standalone-stop-backend.sh       # 停止后端
bash bin/standalone-health-check.sh       # 健康检查
bash bin/deploy-nginx.sh status           # 形态/端口/首页/反代
bash bin/deploy-nginx.sh reload           # 复用模式：配置变更后重载
bash bin/deploy-nginx.sh uninstall        # 复用模式：摘掉本应用入口（不影响其他站点）
bash bin/standalone-backup-database.sh V12 "发布前完整备份"

tail -f logs/metric-code.log                                   # 应用日志
tail -f logs/stdout.log                                        # 启动期日志
tail -f /var/log/nginx/metric-code-error.log                   # 复用模式 nginx 日志
tail -f $PWD/lib/nginx/logs/error.log                            # 独立模式 nginx 日志
```

## 10. 停止与回退

```bash
bash bin/standalone-stop-backend.sh
bash bin/deploy-nginx.sh uninstall     # 复用模式：摘掉 conf.d 里的本应用入口（其他站点不受影响）
bash bin/deploy-nginx.sh stop          # 独立模式：停掉独立实例
```

复用模式下脚本**拒绝**停止系统 nginx（会连带影响机器上其他站点），只允许摘掉自己的入口文件。
数据在本机 67 的库里，回退用对应 `db_export/V<n>/` 备份恢复。

---

## 11. 打包（开发机，出包唯一入口）

```bash
bash deploy/scripts/build-standalone.sh                    # 全量：后端 + 前端 + 打包
bash deploy/scripts/build-standalone.sh --skip-backend --skip-frontend   # 复用已有产物
```

产物：`deploy/out/metric-code-standalone-<时间戳>.tar.gz` 与 `.tar.gz.sha256`。
打包时自动：强制 LF（并校验）、`bash -n` 语法检查、关键文件完整性检查、写入 `BUILD_INFO.txt`。
**不要再手工 `tar` 打包**——手工打包正是之前脚本版本漂移、CRLF 进包的根因。

## 12. 注意事项

- 数据库必须先在 67 本机初始化完，后端才能启动成功。
- 不要用 `java -jar` 裸启动：会绕过空库防护，空库上 Flyway 从 `V1__init_schema.sql` 开始执行（含 `CREATE DATABASE` + 9 组无条件 `DROP TABLE`）。
- `app.env` 含明文口令，权限 600，不要提交 Git、不要外传。
- 磁盘预留：jar 约 91MB、前端约 6MB、nginx 编译中间件约 100MB、数据库备份另计。

---

## 13. 多层反向代理接入（A → B → C）

当平台要经过公司统一网关对外访问时，实际链路是三层：

| 层 | 机器 | 角色 | 监听/对外 |
|---|---|---|---|
| A | 网关服务器（nginx） | HTTPS 终止 + 首跳反代 | `https://211.137.117.144:18080` |
| B | 网关服务器（宝兰德 BWS） | 安全网关（CORS/框架白名单） | `192.168.1.12:80` |
| C | 67（nginx） | 静态 + 后端 API（见第 5 节） | `192.168.1.11:8080` |

### 13.1 A 层（nginx，HTTPS 终止）

```nginx
location /metric-code/ {
    proxy_pass http://192.168.1.12:80;   # 指向 B 层；末尾不加 /，保留 /metric-code/ 前缀
    proxy_http_version 1.1;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;   # 只有最外层用 $scheme（这里是 https）
    proxy_set_header X-Forwarded-Port  $server_port;
    proxy_set_header Upgrade           $http_upgrade;
    proxy_connect_timeout 30s;
    proxy_read_timeout    300s;
    proxy_send_timeout    300s;
    proxy_buffering       off;
}
```

### 13.2 B 层（宝兰德 BWS 网关）

```nginx
location /metric-code/ {
    proxy_pass http://192.168.1.11:8080/metric-code/;   # 指向 C 层 67 nginx；保留前缀
    proxy_http_version 1.1;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    # 关键：中间层透传，不要用 $scheme 覆盖（否则 https 会被降级成 http）
    proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
    proxy_set_header X-Forwarded-Port  $http_x_forwarded_port;
    proxy_connect_timeout 30s;
    proxy_read_timeout    300s;
    proxy_send_timeout    300s;
    proxy_buffering       off;
}
```

### 13.3 C 层（67 nginx）

见第 5 节 `metric-code.conf`，无需额外配置。

### 13.4 关键坑（均踩过，务必照做）

1. **BWS 的 CORS 白名单必须加新 https 地址**（最常见）：BWS 默认只放行 `http://appuser.sn.cmcc`。
   前端入口 JS 是 `<script type="module" crossorigin>`，`type=module` 强制 cors 模式、必带 `Origin` 头；
   Origin 不在 BWS 白名单 → 返回 403，响应头带 `cors-request-status: Not Allowed Origin`，页面白屏。
   **换 https / 换域名 / 加端口后，务必把新地址（如 `https://211.137.117.144:18080`）追加进 BWS 的 CORS / 框架白名单。**
2. **`X-Forwarded-Proto` 只在最外层 A 用 `$scheme`**：B、C 层若也用 `$scheme` 会把 https 覆盖成 http，
   导致 SSO `redirect_uri` 用 http 构造、被浏览器当混合内容拦截。中间层统一用 `$http_x_forwarded_proto` 透传。
3. **后端 CORS 白名单同步换协议**：`app.env` 的 `APP_CORS_ALLOWED_ORIGINS` 从 http 前缀改成 https 前缀
   （`http://` 与 `https://` 是两个不同 origin），否则 `/api/**` 会 403 "Invalid CORS request"，表现为登录报错。
4. **`add_header` 别误写成 `dd_header`**：手滑会 `nginx -t` 报 `unknown directive`，reload 不生效、配置等于没改。

### 13.5 排障速查

| 现象 | 先查 | 根因方向 |
|---|---|---|
| 静态资源 403 + 页面空白 | 响应头有无 `cors-request-status: Not Allowed Origin` | BWS CORS 白名单缺当前 https 地址 |
| 静态资源 403（无 CORS 头） | C 的 nginx error log + assets 目录权限 | C 静态文件权限 / location |
| 登录跳转/回调异常 | 后端日志里的 `redirect_uri` 协议 | 中间层把 `X-Forwarded-Proto` 降级成 http |
| 登录页能开、一登录就 403 | 后端 `APP_CORS_ALLOWED_ORIGINS` | 后端 CORS 白名单没加当前地址 |

---

## 附录：历史故障对照（这些坑已在本版修掉）

| 现象 | 原因 | 本版处理 |
|---|---|---|
| `$'\r': command not found`、`syntax error near unexpected token` | Windows 上打的包把 LF 换成了 CRLF | 打包强制转 LF 并校验失败即中止；`.gitattributes` 锁定 `eol=lf` |
| `unknown directive "return"` / nginx 起不来 | 编译时 `--without-http_rewrite_module`，配置里却用 `return` | 保留 rewrite 与 gzip 模块；`nginx -t` 失败即回滚 |
| 首页 404 | `root` 指向 `frontend/`，实际去找 `frontend/metric-code/` | 改为 `$APP_HOME/www/metric-code` 软链 + `root`，URI 与磁盘路径严格对齐 |
| 深层路由 500 / 重定向循环 | `alias` 配 `try_files` 的经典坑 | 同上，已改用 `root` |
| Flyway 报 `UnsupportedClassVersionError` | 临时 env 未传 `JAVA_HOME`，回落 JDK8 | 显式传 `JAVA_HOME`，两处都校验 17+ |
| 找不到 mysql / mysqldump | 装在 `/usr/local/mysql/bin` 或 `component/mysql/bin`，不在 PATH | 多路径探测（含 `component/mysql*`），仍找不到可用 `MYSQL_BIN` 指定 |
| 缺变量报错看不懂 | 各脚本各写一套 `: "${X:?}"` | 统一 `require_vars` 一次列全；`<...>` 占位符直接拒绝 |
| 部署包 92MB，其中 24MB 是死文件 | vite `emptyOutDir:false` 使历次构建产物堆在 `dist/assets` | 打包时 `VITE_CLEAN_DIST=1` 重建 + 按可达性裁剪（实测删 352 个 / 22MB） |
| 编译成功但 `make install` 报 `cannot create directory '/opt/...': Permission denied` | 默认前缀 `/opt` 需要 root 才能建目录，普通用户装不了 | 前缀改为 `$APP_HOME/lib/nginx`（包目录内、当前用户可写）；编译前先探写权限，不通过立刻报错，不白等编译 |
| 启动报 `Client id must not be empty.` | `app.env` 里写了 `SSO_OIDC_CLIENT_ID=`（空值），把 yml 的默认值覆盖成了空串 | 模板里 SSO 各行改为注释状态；启动脚本对空 SSO_* 变量自动按「未配置」处理 |
| 配了 `UPLOAD_BASE_PATH`，文件仍写到包内 `files/` | 启动脚本无条件传 `--upload.base-path`，命令行参数优先级高于环境变量 | 改为只在未配置时才传，配置了就让 env / 数据库配置生效 |
| 访问 `/metric-code/` 跳到 `localhost:28080` | `app.sso.frontend-base-url` 默认值是 dev 的 vite 端口 | `app.env` 里设 `SSO_FRONTEND_BASE_URL=`（留空=同源相对路径） |
| 采集任务一直「等待执行器」 | 后端只派发，实际执行靠独立 Python Worker；且后端令牌 <32 字符时 `/api/collection/worker/**` 一律 401 | 包内新增 `worker/` 与 `bin/install-worker.sh`（自动生成令牌 + systemd）；令牌变更后需重启后端 |
| 启动报「目录与旧采集通道 xxx 重叠」 | 拍平任务目录后与 `metric_import_config` 里共用同一目录的旧通道重叠 | 守卫改为只校验 `enabled=1` 的旧通道（16 条全停用）；迁移 `V20261113101000` 拍平落地 |

## 升级（已部署环境的标准交付方式）

> **现有生产升级以 [生产升级与部署操作手册](生产升级与部署操作手册.md) 为准。** 拉取 main 后应比较现场版本与本次交付并核对目标库：涉及数据库迁移时，先在 67 备份并运行本版经隔离演练的数据库升级程序，成功后再升级平台；确认没有数据库迁移和其他数据库修改时，沿用 67 备份、67 升级流程。旧版专用数据库升级脚本不得直接用于后续版本。

首次安装用全量包 `metric-code-standalone-*.tar.gz`；**之后每次交付一律用升级包**
`metric-code-upgrade-*.tar.gz`，两者不要混用。

### 打包（开发机）

```bash
bash deploy/scripts/build-upgrade.sh                 # 全量构建后端+前端
bash deploy/scripts/build-upgrade.sh --skip-backend  # 复用已有 jar
```

产物：`deploy/out/metric-code-upgrade-<时间戳>.tar.gz` 与 `.sha256`。

### 升级包里有什么、没有什么

| 包含 | 不包含（刻意） |
| --- | --- |
| `backend/metric-code.jar`（内含全部 Flyway 迁移） | `config/app.env` |
| `frontend/` 静态资源 | `files/` 采集目录与已采集数据 |
| `worker/` 采集执行器 | 数据库数据 |
| `collection-scripts/releases/<版本>/` 采集/取数脚本（**外置，不进 jar**） | |
| `bin/apply-upgrade.sh` 升级脚本 | |

升级只替换程序文件，不覆盖现场配置，不动采集数据。

### 采集脚本是外置的

对内部人员、运行效率这类「先取数再采集」的脚本（`source_export.py`、`sources.json`、`config.json`、`reader.py` 等）**不打包进 jar**，而是作为独立目录随包分发，由 `apply-upgrade.sh` 装到：

```
<UPLOAD_BASE_PATH>/collection-scripts/releases/<版本号>/
```

后端启动后从该目录读取清单，所以**改脚本不需要重新打 jar**：发布新版本号 → 放进包 → 升级，现场同名版本目录不会被覆盖。

版本目录**不可变**：目标机已存在同名目录且内容不一致时，升级会直接中止并提示发布新版本号，绝不会静默改写现场脚本。

### 升级（目标机）

```bash
sha256sum -c metric-code-upgrade-<时间戳>.tar.gz.sha256
tar -xzf metric-code-upgrade-<时间戳>.tar.gz
cd metric-code-upgrade-<时间戳>
bash bin/apply-upgrade.sh --dry-run     # 先看要做哪些动作，不做任何变更
bash bin/apply-upgrade.sh               # 正式升级
```

`apply-upgrade.sh` 固定顺序：

1. 预检：java 17+、包内 jar 体积、磁盘 ≥512MB、`mysqldump` 存在
2. **校验数据库连通性**（见下节“数据库密码”）：连不上立即中止，**此时后端还没停**
3. **停后端**（避免备份期间有写入）
4. 备份现有 `backend/ frontend/ worker/ bin/` 到 `rollback/<时间戳>/`
5. 用包内 `bin/*.sh` 覆盖现场同名脚本（备份脚本要先更新，密码覆盖才生效；不删除现场其他脚本）
6. **安装外置采集脚本**：把包内 `collection-scripts/releases/<版本>/` 装到 `<UPLOAD_BASE_PATH>/collection-scripts/releases/`，装前装后都校验 sha256；同名且一致则跳过，同名但内容不同则中止
7. **备份数据库**：自动取 `db_export` 下一个 `V<n>`，调 `bin/standalone-backup-database.sh`，失败即中止
8. 替换程序文件
9. 启动后端并检查 Flyway；生产环境若本版涉及迁移，须事先在 67 完成本版数据库升级，不依赖后端启动时补跑
10. 90 次 × 2 秒轮询端口，未就绪则**自动回滚程序文件（含 bin/）并重启**
11. 重启 `metric-collection-worker.service`（若存在）——worker 是常驻进程，不重启不会加载新协议
12. 打印版本、数据库最新迁移版本、回滚方法

### 数据库密码（67 本机）

升级在第 2、4 步要连数据库（备份 + 迁移版本查询），需要 **67 本机 MySQL 的密码**。

- **来源**：`<APP_HOME>/config/app.env` 的 `DB_PASSWORD`，也就是后端正在用的那份。升级包**不包含** `app.env`，密码不会随包传播，也不会被覆盖。
- **用法**：密码只经环境变量 `MYSQL_PWD` 传给 `mysql`/`mysqldump`，脚本**不打印、不写日志、不落盘**。
- **密码与配置不一致时**，不必改文件，可临时指定：

```bash
bash bin/apply-upgrade.sh --db-password '真实密码'
# 或（避免留在命令历史里）
METRIC_DB_PASSWORD='真实密码' bash bin/apply-upgrade.sh
```

- **两种会被提前拦住的情况**（都在停后端之前）：
  - `DB_PASSWORD` 为空，或仍是模板示例值 `RealPass12345`
  - 用该密码连不上 `DB_URL` 指向的库（会打印客户端原始报错，如 `Access denied for user 'root'`）

  报错会明确提示“本次未停止后端，服务仍在线”，改完重跑即可。

- 目标机需要 `mysql` 与 `mysqldump` 客户端；缺 `mysqldump` 会在预检阶段报错（确有必要可 `--skip-backup`，但不建议）。

常用参数：

| 参数 | 作用 |
| --- | --- |
| `--app-home /opt/metric-code-standalone` | 指定已部署目录（默认自动探测 `/opt/metric-code-standalone` 等） |
| `--dry-run` | 只打印动作，不做变更 |
| `--yes` | 免交互确认（自动化用） |
| `--skip-backup` | 跳过数据库备份与密码校验（不建议；仅在已单独备份时使用） |
| `--db-password '密码'` / `METRIC_DB_PASSWORD` | 临时指定本机库密码，覆盖 `app.env`，不落盘 |

### 回滚

```bash
bash <APP_HOME>/bin/standalone-stop-backend.sh
cp -a <APP_HOME>/rollback/<时间戳>/{backend,frontend,worker} <APP_HOME>/
# bin/ 是整目录替换，避免与现场脚本混在一起
rm -rf <APP_HOME>/bin && cp -a <APP_HOME>/rollback/<时间戳>/bin <APP_HOME>/bin
bash <APP_HOME>/bin/standalone-start-backend.sh
```

数据库如需回退，用 `db_export` 下升级前那份备份手工恢复；**恢复前必须先停后端**。

### 注意

- Flyway 迁移只会前进：`apply-upgrade.sh` 不自动回滚数据库，程序回滚后若已执行新迁移，需人工判断是否回退库。
- 升级后首次启动会自动补齐缺失迁移，耗时取决于落后多少个版本；期间健康检查可能持续 503，属正常。
- 采集脚本（`collection-scripts/releases`）**外置**，由 `apply-upgrade.sh` 安装到 `UPLOAD_BASE_PATH` 下；改脚本只需发布新版本号，不必重打 jar。
- 回滚程序文件不会删除已安装的脚本版本目录（版本只增不减，旧版本保留可回退）。
