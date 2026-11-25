# satisfaction_report

本大类脚本和配置目录。已发布版本保持不可变。

2026-11-09 起本大类改用对端真实推送格式，不再复用 `../../plugins/standard`：单行、`€€` 分隔、无表头、只给指标值，列位对应《满意度指标清单》行序。

- `reader.py`：按列位取已确认的 17 项，校验列数、单位与小数位，数据日期取文件名 `MetricDataYYYYMMDD`。
- `config.json`：列位映射（指标编码、名称、单位、清单行号）与文件规则（分隔符、期望列数、缺失值、百分比后缀）。
- 文件投递目录：`raw_data/import/scripts/satisfaction_report`，文件名 `MetricDataYYYYMMDD.csv`。

规则与列位来源见 [满意度对端真实文件采集](../../../../doc/数据采集改造1008/54-满意度对端真实文件采集.md)。
