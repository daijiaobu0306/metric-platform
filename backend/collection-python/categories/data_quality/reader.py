"""Collection protocol v1. No database access; output decimal values as strings."""
import argparse
import csv
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
import sys
from zipfile import ZipFile
from xml.etree import ElementTree as ET

NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def excel_numbers(path):
    """Use numeric XML text, never a binary float, for decimal metric fields."""
    result = {}
    with ZipFile(path) as archive:
        if sum(info.file_size for info in archive.infolist()) > 100 * 1024 * 1024:
            raise ValueError("Excel 解压大小超过100MB")
        for name in archive.namelist():
            if name.startswith("xl/worksheets/") and name.endswith(".xml"):
                values = {}
                root = ET.fromstring(archive.read(name))
                for cell in root.findall(".//s:sheetData/s:row/s:c", NS):
                    value = cell.find("s:v", NS)
                    if cell.get("t", "n") == "n" and value is not None:
                        values[cell.get("r")] = value.text
                result["/" + name] = values
    return result


def convert(value, kind, config):
    if kind in ("decimal", "long"):
        number = Decimal(str(value))
        if not number.is_finite():
            raise ValueError("数值必须有限")
        if kind == "long":
            if number != number.to_integral_value() or not -(2**63) <= number < 2**63:
                raise ValueError("整数包含小数或超出范围")
            return int(number)
        return str(number)
    if kind in ("date", "datetime"):
        pattern = config.get("datetimeFormat" if kind == "datetime" else "dateFormat")
        parsed = (datetime.strptime(str(value), pattern) if pattern else
                  datetime.fromisoformat(str(value).replace(" ", "T")))
        if parsed.tzinfo is not None:
            raise ValueError("当前协议使用北京时间本地时间，请先显式转换时区")
        if kind == "date":
            return parsed.date().isoformat()
        return parsed.isoformat()
    if kind != "string":
        raise ValueError("不支持的字段类型：" + kind)
    return str(value).strip()


def delimited_rows(stream, delimiter):
    """Literal delimiters; double quotes protect delimiters and embedded newlines."""
    if not isinstance(delimiter, str) or not 1 <= len(delimiter) <= 16 or any(c in delimiter for c in ('\r', '\n', '"', '\0')):
        raise ValueError("分隔符须为1～16个字符，不能包含换行、引号或空字符")
    if len(delimiter) == 1:
        yield from csv.reader(stream, delimiter=delimiter, strict=True)
        return
    text = stream.read()
    row, field = [], []
    quoted = closed = started = False
    index = 0
    while index < len(text):
        char = text[index]
        if quoted:
            if char == '"':
                if text[index:index + 2] == '""':
                    field.append('"')
                    index += 2
                    continue
                quoted, closed = False, True
            else:
                field.append(char)
            index += 1
            continue
        if text.startswith(delimiter, index):
            row.append(''.join(field))
            field, closed, started = [], False, True
            index += len(delimiter)
        elif char in '\r\n':
            row.append(''.join(field))
            yield row if started or field or closed or len(row) > 1 else []
            row, field = [], []
            closed = started = False
            index += 2 if text[index:index + 2] == '\r\n' else 1
        elif closed:
            raise ValueError("引号结束后只能接分隔符或换行")
        elif char == '"':
            if field:
                raise ValueError("引号必须位于字段开头，字段内引号须写为双引号")
            quoted = started = True
            index += 1
        else:
            field.append(char)
            started = True
            index += 1
        if len(row) > 200:
            raise ValueError("超过200列限制")
    if quoted:
        raise ValueError("字段引号未闭合")
    if started or row or field or closed:
        yield row + [''.join(field)]


def read_rows(input_path, source, overrides):
    config = dict(source)
    config.update(overrides)
    if overrides.get("categoryCode") and overrides["categoryCode"] != (source.get("worker") or {}).get("categoryCode"):
        raise ValueError("脚本版本绑定大类与任务不一致")
    fields = config.get("fields") or []
    if not fields:
        raise ValueError("请配置字段映射")
    targets = [field["target"] for field in fields]
    if len(targets) != len(set(targets)) or any(name.startswith("_") for name in targets):
        raise ValueError("目标字段重复或使用平台保留字段")
    header_row = int(config.get("headerRow", 1))
    data_row = int(config.get("dataStartRow", header_row + 1))
    if not 1 <= header_row < data_row <= 50001:
        raise ValueError("表头行或数据起始行无效")
    workbook = None
    sheet_name = None
    numbers = {}
    if source["format"] == "xlsx":
        # openpyxl handles dates/strings; numeric XML preserves source precision.
        try:
            import openpyxl
        except ImportError as error:
            raise ValueError("当前Worker未安装可选Excel依赖；请使用CSV/TXT，或按部署说明离线安装requirements-xlsx.txt及其依赖，不会自动联网安装") from error
        numbers_by_sheet = excel_numbers(input_path)
        workbook = openpyxl.load_workbook(input_path, read_only=True, data_only=False)
        sheet = workbook[config["sheet"]] if config.get("sheet") else workbook.worksheets[0]
        sheet_name = sheet.title
        numbers = numbers_by_sheet.get("/" + sheet._worksheet_path.lstrip("/"), {})
        rows = sheet.iter_rows()
    else:
        stream = open(input_path, encoding=config.get("encoding") or "utf-8-sig", newline="")
        rows = delimited_rows(stream, config.get("delimiter") or ",")
    try:
        headers = None
        records = []
        for line, cells in enumerate(rows, 1):
            if line > 50001:
                raise ValueError("超过50000行限制")
            if len(cells) > 200:
                raise ValueError("超过200列限制")
            values = [getattr(cell, "value", cell) for cell in cells]
            if line == header_row:
                headers = [str(v).strip().lstrip("\ufeff") if v is not None else "" for v in values]
                if not headers or "" in headers or len(set(headers)) != len(headers):
                    raise ValueError("表头为空或重复")
                for field in fields:
                    if field.get("required") and field["source"] not in headers:
                        raise ValueError("缺少源列：" + field["source"])
            if line < data_row:
                continue
            if not headers:
                raise ValueError("未找到表头")
            if all(v is None or not str(v).strip() for v in values):
                continue
            if len(values) != len(headers):
                raise ValueError(f"第{line}行列数与表头不一致")
            if any(getattr(cell, "data_type", None) == "f" for cell in cells):
                raise ValueError(f"第{line}行包含公式，请提供导出的实际值")
            record = {}
            for field in fields:
                name = field["source"]
                column = headers.index(name) if name in headers else -1
                value = values[column] if column >= 0 else None
                kind = field["type"]
                if column >= 0 and kind in ("decimal", "long") and workbook:
                    cell = cells[column]
                    value = numbers.get(cell.coordinate, value)
                try:
                    if value is None or str(value).strip() in config.get("missingValues", ["", "—"]):
                        if field.get("required"):
                            raise ValueError("必填值缺失")
                        record[field["target"]] = None
                    else:
                        record[field["target"]] = convert(value, kind, config)
                except (ValueError, InvalidOperation) as error:
                    raise ValueError(f"第{line}行，列{name}：{error}") from error
            record["_source"] = {"row": line, "sheet": sheet_name}
            records.append(record)
        if not records:
            raise ValueError("没有有效数据行")
        return records
    finally:
        if workbook:
            workbook.close()
        else:
            stream.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        request = json.loads(Path(args.request).read_text(encoding="utf-8"))
        overrides = json.loads(Path(__file__).with_name("config.json").read_text(encoding="utf-8"))
        records = read_rows(request["inputPath"], request["source"], overrides)
        result = dict(protocolVersion=1, outputType="metric-values", rowCount=len(records), records=records)
        Path(args.output).write_text(json.dumps(result, ensure_ascii=False, allow_nan=False), encoding="utf-8")
    except Exception as error:
        print(json.dumps({"code": "PARSE_FAILED", "message": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
