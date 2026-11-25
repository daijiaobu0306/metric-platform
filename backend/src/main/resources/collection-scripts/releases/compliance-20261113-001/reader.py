"""合规管理（系统稽核）月数据清单 txt 读取器。

来源文件 `system_jhd_YYYYMMDD.txt`：无表头、`@` 分隔、固定 27 列。
指标编码与指标值直接取源列，不做任何派生计算；行数、列数或列位不符时明确报错。

协议：只读取文件，不访问数据库；指标值以十进制字符串输出，缺失值输出 null。
"""
import argparse
from datetime import date
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
import re
import sys


def load_config():
    return json.loads(Path(__file__).with_name("config.json").read_text(encoding="utf-8"))


def file_day_of(source_file, pattern):
    """数据日期只从文件名取；取不到就报错，不臆造时间。"""
    match = re.search(pattern, source_file or "")
    if not match:
        raise ValueError("文件名未包含数据日期（%s）：%s" % (pattern, source_file))
    text = match.group(1)
    return "%s-%s-%s" % (text[:4], text[4:6], text[6:8])


def data_time_of(raw, fallback_day, config):
    """异常日期支持三种形态：8 位日、6 位月、空。

    空值只出现在无异常的稽核点（异常量为 0），按文件业务日回退；
    6 位月按配置的 monthlyDay（默认当月 1 日）落到该月。
    """
    text = (raw or "").strip()
    if not text:
        if not config.get("emptyDateUsesFileDay", True):
            raise ValueError("异常日期为空且未允许回退到文件日期")
        if not fallback_day:
            raise ValueError("异常日期为空且文件日期不可用")
        return fallback_day + "T00:00:00"
    if not re.fullmatch(r"\d{6}|\d{8}", text):
        raise ValueError("异常日期格式不支持：%r" % raw)
    try:
        if len(text) == 8:
            value = date(int(text[:4]), int(text[4:6]), int(text[6:8]))
        else:
            value = date(int(text[:4]), int(text[4:6]), int(config.get("monthlyDay", 1)))
    except ValueError:
        raise ValueError("异常日期不是有效日期：%r" % raw) from None
    return value.isoformat() + "T00:00:00"


def metric_value_of(raw, config):
    text = (raw or "").strip()
    if text in set(config.get("missingValues", [])):
        return None
    try:
        number = Decimal(text)
    except InvalidOperation:
        raise ValueError("异常量不是十进制数：%r" % raw) from None
    if not number.is_finite():
        raise ValueError("异常量必须有限：%r" % raw)
    exponent = number.normalize().as_tuple().exponent
    if isinstance(exponent, int) and exponent < -int(config.get("maxScale", 4)):
        raise ValueError("异常量超过 %d 位小数：%r" % (int(config.get("maxScale", 4)), raw))
    if abs(number) > Decimal(config.get("maxAbsValue", "99999999999.9999")):
        raise ValueError("异常量超出存储范围：%r" % raw)
    return str(number)


def read_records(text, source_file, config):
    expected = int(config["expectedFields"])
    code_index = int(config["checkCodeIndex"])
    date_index = int(config["abnormalDateIndex"])
    count_index = int(config["abnormalCountIndex"])
    allowed = set(config.get("metricCodes") or [])
    if not allowed:
        raise ValueError("config.json 的 metricCodes 为空，拒绝无白名单采集")
    fallback_day = file_day_of(source_file, config.get("fileNamePattern") or r"(\d{8})")

    records, skipped, seen = [], [], set()
    for line_no, line in enumerate(text.replace("\r\n", "\n").replace("\r", "\n").split("\n"), 1):
        if not line.strip():
            continue
        cells = line.split(config.get("delimiter") or "@")
        if len(cells) != expected:
            raise ValueError("第%d行列数为 %d，与约定的 %d 列不一致" % (line_no, len(cells), expected))
        code = cells[code_index].strip()
        if not code:
            raise ValueError("第%d行稽核点编号为空" % line_no)
        try:
            stamp = data_time_of(cells[date_index], fallback_day, config)
            value = metric_value_of(cells[count_index], config)
        except ValueError as error:
            raise ValueError("第%d行，稽核点 %s：%s" % (line_no, code, error)) from None
        if code not in allowed:
            skipped.append({"row": line_no, "metric_code": code})
            continue
        if (code, stamp) in seen:
            raise ValueError("第%d行同一指标编码和数据时间重复：%s" % (line_no, code))
        seen.add((code, stamp))
        records.append({
            "metric_code": code,
            "metric_value": value,
            "data_time": stamp,
            "_source": {"row": line_no, "sheet": None},
        })
    if not records:
        raise ValueError("没有可采集的数据行（命中白名单 0 行）")
    return records, skipped


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
        records, skipped = read_records(text, request.get("sourceFile") or "", config)
        result = dict(protocolVersion=1, outputType="metric-values",
                      rowCount=len(records), records=records, skipped=skipped)
        Path(args.output).write_text(json.dumps(result, ensure_ascii=False, allow_nan=False), encoding="utf-8")
        if skipped:
            print("跳过平台未启用的稽核点 %d 项：%s" % (
                len(skipped), ",".join(sorted({item["metric_code"] for item in skipped}))), file=sys.stderr)
    except Exception as error:
        print(json.dumps({"code": "PARSE_FAILED", "message": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
