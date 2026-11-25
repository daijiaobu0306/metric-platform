# 对内部人员采集（新环境）

本目录承载既有 `internal_staff` 采集任务的 Python 生产版：MySQL 与 BPM 两类来源统一由同一采集任务触发，先导出 CSV，再由既有 Worker 读取、按原 Java 指标规则计算并入库。每个来源数据集独立成 CSV 明细，不再输出多工作表 Excel。

**一轮取数只产出一份合并 CSV**（用户于 2026-11-16 确认）：表头固定 `dataset,data_time,_empty,payload`，`payload` 是 JSON 对象，装该行所属数据集自己的列（键为来源列名）。不同数据集的行放在同一个文件里，平台 reader 按 `dataset` 列分组还原，指标值与拆成多份时完全一致。只有当一轮总大小超过 8MB 时才按「数据集边界」再拆成 `_2`/`_3`——同一份数据不会被拆到两个文件（拆开会导致指标被算成多批）。

## 本次新环境差异

- MySQL 改用 `D:/Desktop/adb-export/metrics-mysql.json` 中的 6 条 SQL：CRM 系统、日统计表、昨天全天窗口（见下方「取数频率与数据时间」）。SQL 的筛选、分组、单位换算与阈值条件按新配置原样保留。
- BPM 改用 `D:/Desktop/adb-export/bpm/bpm_export.py` 中的业务全程办理时长任务，系统 ID 与新环境一致，窗口同样为昨天全天；其他两个 BPM 自定义返回信息任务不属于已确认的内部人员指标映射，未混入本任务。
- 连接地址来自 `db_config.json` 与 `bpm/config.py`；真实账号口令不写入版本库或压缩包。请在服务器受保护目录按 `connections.example.json` 填入凭据，并配置 Worker 的 `COLLECTION_SOURCE_CONNECTIONS_ROOT`。JDBC 驱动文件按示例中的服务器路径提供。
- 指标编码、旧 Java 汇总规则均沿用 `config.json`；办理类型仍按步骤名称包含“页面”分类为页面加载，其余归步骤时长。

## 取数频率与数据时间（用户于 2026-11-17 确认）

- 对内部人员改为**每天取一次**：采集任务 `internal_staff` 由“5 分钟按间隔扫描”改为每日 06:00 定时执行（迁移 `V20261117103000__internal_staff_daily_schedule.sql`），应到规则 T-1。
- 一轮取的是**昨天全天**的数据：6 条 SQL 的窗口为 `create_time >= 昨天 00:00 且 < 今天 00:00`，BPM 任务的 `startTime/endTime` 同样是昨天 00:00～今天 00:00。
- `data_time` 统一写昨天 00:00:00（不再写采集时刻），平台按它归批次，因此今天早上采集的批次 `business_date` 是昨天。

## 文件与字段

- `sources.json`：6 个 MySQL SQL 数据集与 1 个 BPM 数据集。
- `source_export.py`：在 Worker 领取既有任务时访问新环境来源，分页取数并原子写入 UTF-8 CSV；不在平台数据库直接写入。
- `config.json`、`reader.py`、`standard_reader.py`：原指标映射、Java 规则的 Python 实现与标准采集协议。
- `connections.example.json`：新环境地址模板，账号口令为占位值。

服务器连接配置必须通过安全渠道部署，切勿将真实凭据提交 Git 或转发到不受控位置。本机无法访问内网接口，本次未执行来源连接测试；请在新环境由用户验证。
