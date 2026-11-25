# 运行效率采集脚本（新环境）

一条运行效率任务承载系统性能与系统异常两个独立小类。脚本可以一次获取全部来源，也可以按小类分别执行；产生的 CSV 投递到同一条 `running_efficiency` 任务目录，由该任务逐文件扫描。每个文件以 `dataset` 标识数据集，不需要为每个 CSV 建平台任务。

## 来源及文件数量

- 系统性能：`D:/Desktop/adb-export/metrics.json` 中 10 条 ADB 查询。
- 系统异常：同一配置中的 4 条 ADB 查询，加 `bpm/bpm_export.py` 中 2 个业务自定义返回码/返回信息任务。 BPM 业务全程办理时长属于内部人员任务，不在此处。
- 完整运行效率采集共 16 个数据集，但**一轮只产出 1 份合并 CSV**（用户于 2026-11-16 确认）：表头固定 `dataset,data_time,_empty,payload`，`payload` 是 JSON 对象，装该行所属数据集自己的列（键为来源列名）。文件名为批次时间+随机批次号；每个数据集的行靠 `dataset` 列区分；空结果使用 `_empty=1` 表示。只有当一轮总大小超过 8MB 时才按「数据集边界」再拆成 `_2`/`_3`，同一份数据不会被拆到两个文件（拆开会导致指标被算成多批）。

## 执行方式

默认全量执行：

```bash
python source_export.py --source-config sources.json --connections /etc/metric-collection/sources/running_efficiency.json --output-dir <运行效率任务投递目录>
```

若系统性能、系统异常的来源需要错开调度，仍可写入同一投递目录，分别执行：

```bash
python source_export.py --source-config sources.json --connections /etc/metric-collection/sources/running_efficiency.json --output-dir <运行效率任务投递目录> --sub-category 系统性能
python source_export.py --source-config sources.json --connections /etc/metric-collection/sources/running_efficiency.json --output-dir <运行效率任务投递目录> --sub-category 系统异常
```

也可用 `--kind jdbc` 或 `--kind bpm` 分开来源通道。不要同时运行全量与分组版本，以免重复采集。Worker 自动采集时不传筛选参数，因此默认同批取得两个小类及所有来源。

## 配置口径

- ADB SQL 直接取自新环境 `metrics.json`，不将 `gxn/metrics-2.json` 的旧系统、旧时间窗口或旧 URL 过滤条件带入。保留每条新 SQL 原有时间窗口与过滤条件。
- BPM 仅取自新环境脚本中的两个异常任务；保留源脚本真实 `days_ago`、字段过滤和返回字段。注释与实际参数不一致时按参数值执行。
- `config.json` 沿用已有系统性能/异常指标编码及计算规则，按来源 `dataset` 精确绑定；不猜测阈值或关键词。系统整体成功率使用来源 SQL 已给出的成功率列；单接口成功率仍按现有阈值规则统计。
- `connections.example.json` 已换成新环境地址，所有账号密码仍为占位值。请在服务器安全目录创建 `running_efficiency.json` 并填入凭据；JDBC 驱动路径须在 Worker 主机可读。

本机无法访问这些内网地址，本次不连接来源数据库或 BPM 接口。脚本执行与真实 CSV 生成请在新环境进行；不要用空模板或旧环境样例冒充新数据。
