> 2026-11-03：八类脚本入口现位于 `categories/<大类编码>/`；本次生产采集改造见 [生产CSV迁移说明](../../doc/数据采集改造1008/53-生产采集脚本分析与CSV迁移.md)。旧 `profiles/` 与已发布版本保留兼容。

> 2026-10-27：任务统一为脚本加配置，旧 Java 采集已下线。大类配置见 `profiles/`；发布需添加 `--profile profiles/<任务编码>.json`，参见开发文档 39。

# 独立数据采集 Worker

Worker 领取平台任务，调用解析插件，通过标准 API 提交结果。Java 不执行 Python，插件不接触服务密钥、不写数据库。

- `worker.py`：HTTP 客户端、租约、插件执行、重试、归档及崩溃恢复。
- `plugins/standard/reader.py`：CSV/TXT/XLSX 通用读取器。
- `plugins/standard/config.json`：插件级映射覆盖，可覆盖 sheet、fields、headerRow、dataStartRow、missingValues、datetimeFormat/dateFormat 等。
- `plugins/standard/requirements.txt`：默认无第三方依赖，Worker、CSV/TXT 仅需 Python 3.10+ 标准库。
- `plugins/standard/requirements-xlsx.txt`：可选 Excel 依赖，仅使用 XLSX 时离线预装，不在扫描时安装。

生产CSV脚本输出标准指标值及 `details` 原始明细，保留规则参数和来源定位；标准文件继续使用原有通用协议。运行版本位于文件根目录 `collection-scripts/releases/<version>/`，不提交 Git。任务绑定版本；更新源字段时发布新版本再切换任务，不覆盖旧版本、不重启应用。

部署、使用及验收步骤见 [Worker 首期开发与使用说明](../../doc/数据采集改造1008/36-Worker首期开发与使用说明.md)。设计见 [开发方案](../../doc/数据采集改造1008/35-Python脚本化采集开发方案.md)。

## 对外部客户文件投递

业务方提供 UTF-8 CSV，表头为 `指标编码,指标名称,指标小类,指标值,单位`。平台以北京时间记录入库时间，并以该时间发布指标值。每个新批次使用独立文件名，同批次重传使用原文件名。文件先以 `.part` 写入任务的扫描目录，传输完成后在同一目录改名为正式的 `.csv`。任务默认每 5 秒扫描，可在文件改名后直接读取。

部署目标为内网，默认不访问 PyPI、不在线下载依赖。XLSX 可选依赖和镜像离线交付见[内网依赖说明](../../doc/数据采集改造1008/37-内网部署与Python最小依赖.md)。
