"""合规管理（系统稽核）工单明细 txt 读取器。

来源文件 `system_jhd_detail_YYYYMMDD.txt`：无表头、`@` 分隔、固定 31 列。
工单明细只做归档展示，不产生指标值：输出 raw-rows 协议，records 为 31 列原始字段。

协议：只读取文件，不访问数据库；缺失值输出 null；ws_id 为空的行跳过。
"""
import argparse
from datetime import date
import json
from pathlib import Path
import re
import sys


def load_config():
    return json.loads(Path(__file__).with_name("config.json").read_text(encoding="utf-8"))


def file_day_of(source_file, pattern):
    """业务日只从文件名取；取不到就报错，不臆造时间。"""
    match = re.search(pattern, source_file or "")
    if not match:
        raise ValueError("文件名未包含数据日期（%s）：%s" % (pattern, source_file))
    text = match.group(1)
    value = date(int(text[:4]), int(text[4:6]), int(text[6:8]))
    return value.isoformat()


def read_records(text, source_file, config):
    expected = int(config["expectedFields"])
    columns = config.get("columns") or {}
    if not columns:
        raise ValueError("config.json 的 columns 为空，拒绝无列映射采集")
    for idx in columns:
        if not str(idx).isdigit() or not (0 <= int(idx) < expected):
            raise ValueError("config.json 的 columns 列位越界：%s" % idx)
    business_date = file_day_of(source_file, config.get("fileNamePattern") or r"(\d{8})")

    records, skipped, seen = [], [], {}
    for line_no, line in enumerate(text.replace("\r\n", "\n").replace("\r", "\n").split("\n"), 1):
        if not line.strip():
            continue
        cells = line.split(config.get("delimiter") or "@")
        if len(cells) != expected:
            raise ValueError("第%d行列数为 %d，与约定的 %d 列不一致" % (line_no, len(cells), expected))
        ws_id = cells[3].strip()
        if not ws_id:
            skipped.append({"row": line_no, "reason": "工单编码为空"})
            continue
        record = {field: cells[int(idx)].strip() or None for idx, field in columns.items()}
        key = (ws_id, record.get("audit_id"))
        if key in seen:
            # 来源偶有整行重复导出：内容一致按一条处理，内容不同则拒绝猜测。
            if seen[key] != record:
                raise ValueError("第%d行同一工单编码与稽核点编号出现不同内容，无法判定口径" % line_no)
            continue
        seen[key] = record
        records.append(record)
    if not records:
        raise ValueError("没有可采集的数据行（有效工单 0 行）")
    return records, business_date, skipped


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
        records, business_date, skipped = read_records(text, request.get("sourceFile") or "", config)
        result = dict(protocolVersion=1, outputType="raw-rows",
                      rowCount=len(records), businessDate=business_date,
                      records=records, skipped=skipped)
        Path(args.output).write_text(json.dumps(result, ensure_ascii=False, allow_nan=False), encoding="utf-8")
        if skipped:
            print("跳过无效工单 %d 项" % len(skipped), file=sys.stderr)
    except Exception as error:
        print(json.dumps({"code": "PARSE_FAILED", "message": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
