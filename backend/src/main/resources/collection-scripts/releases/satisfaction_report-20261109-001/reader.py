"""对端满意度推送文件读取器：单行定长列，列位对应《满意度指标清单》行序。

协议：只读取文件，不访问数据库；指标值以十进制字符串输出，缺失值输出 null。
"""
import argparse
from datetime import datetime
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
import re
import sys


def load_config():
    return json.loads(Path(__file__).with_name("config.json").read_text(encoding="utf-8"))


def cells_of(line, delimiter):
    return [cell.strip() for cell in line.split(delimiter)]


def select_row(text, config):
    """只接受与清单列数完全一致的唯一一行，不猜测列序。"""
    expected = int(config["expectedFields"])
    delimiters = []
    for delimiter in (config.get("delimiter"), "\t"):
        if delimiter and delimiter not in delimiters:
            delimiters.append(delimiter)
    lines = [line for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n") if line.strip()]
    if not lines:
        raise ValueError("文件没有数据行")
    summary = []
    for delimiter in delimiters:
        counts = [len(cells_of(line, delimiter)) for line in lines]
        summary.append(f"{delimiter!r} 列数={counts}")
        matched = [(index, cells_of(line, delimiter)) for index, line in enumerate(lines)
                   if len(cells_of(line, delimiter)) == expected]
        if len(matched) == 1:
            return matched[0][1], delimiter
        if len(matched) > 1:
            raise ValueError("文件存在多行满足清单列数 %d，拒绝猜测数据行：%s" % (expected, summary))
    raise ValueError("文件列数与《满意度指标清单》%d 列不一致：%s" % (expected, "；".join(summary)))


def parse_value(raw, entry, config):
    """按已确认单位解析指标值；保留来源位数，零值与缺失值严格区分。"""
    code = entry["code"]
    missing = set(config.get("missingValues", []))
    if raw == "" or raw in missing:
        return None
    percent = config.get("percentSuffix", "%")
    unit = entry.get("unit")
    has_percent = bool(percent) and raw.endswith(percent)
    if unit == "%" and not has_percent:
        raise ValueError("%s 期望百分比值但来源未带 %%%s：%r" % (code, percent, raw))
    if unit not in ("%", None) and has_percent:
        raise ValueError("%s 为计数单位但来源带百分比：%r" % (code, raw))
    text = raw[:-len(percent)].strip() if has_percent else raw
    if not re.fullmatch(r"[+-]?\d+(\.\d+)?", text):
        raise ValueError("%s 指标值不是十进制数：%r" % (code, raw))
    try:
        number = Decimal(text)
    except InvalidOperation:
        raise ValueError("%s 指标值不是十进制数：%r" % (code, raw)) from None
    if not number.is_finite():
        raise ValueError("%s 指标值必须有限：%r" % (code, raw))
    exponent = number.normalize().as_tuple().exponent
    max_scale = int(config.get("maxScale", 4))
    if isinstance(exponent, int) and exponent < -max_scale:
        raise ValueError("%s 指标值超过 %d 位小数：%r" % (code, max_scale, raw))
    limit = Decimal(config.get("maxAbsValue", "99999999999.9999"))
    if abs(number) > limit:
        raise ValueError("%s 指标值超出存储范围：%r" % (code, raw))
    return text


def data_time_of(source_file, config):
    """数据日期取来源文件名中的日期；来源未提供时不臆造历史日期。"""
    pattern = config.get("fileNamePattern", r"^MetricData(\d{8})")
    match = re.search(pattern, source_file)
    if not match:
        raise ValueError("文件名未包含数据日期（%s），不臆造数据时间：%s" % (pattern, source_file))
    day = datetime.strptime(match.group(1), "%Y%m%d").date()
    return day.isoformat() + "T00:00:00"


def build_records(cells, config, source_file):
    records = []
    stamp = data_time_of(source_file, config)
    provenance = config.get("provenance", "counterparty_delivery")
    for entry in config["metrics"]:
        index = int(entry["index"])
        if index >= len(cells):
            raise ValueError("%s 的列位 %d 超出文件列数 %d" % (entry["code"], index, len(cells)))
        value = parse_value(cells[index], entry, config)
        record = {
            "metric_code": entry["code"],
            "metric_name": entry["name"],
            "metric_value": value,
            "unit": entry.get("unit"),
            "data_time": stamp,
            "provenance": provenance,
            "_source": {"row": entry.get("listRow"), "column": index + 1, "sheet": config.get("listName")},
        }
        records.append(record)
    return records


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        request = json.loads(Path(args.request).read_text(encoding="utf-8"))
        config = load_config()
        source = request["source"]
        bound = (source.get("worker") or {}).get("categoryCode")
        if config.get("categoryCode") and bound and config["categoryCode"] != bound:
            raise ValueError("脚本版本绑定大类与任务不一致：%s != %s" % (config["categoryCode"], bound))
        text = Path(request["inputPath"]).read_bytes().decode("utf-8-sig")
        cells, delimiter = select_row(text, config)
        records = build_records(cells, config, request.get("sourceFile") or "")
        result = dict(protocolVersion=1, outputType="metric-values", rowCount=len(records), records=records)
        Path(args.output).write_text(json.dumps(result, ensure_ascii=False, allow_nan=False), encoding="utf-8")
    except Exception as error:
        print(json.dumps({"code": "PARSE_FAILED", "message": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
