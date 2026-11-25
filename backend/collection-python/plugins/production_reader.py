"""Configured production CSV calculations migrated from the legacy Java collectors."""
import argparse
import csv
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
import json
from pathlib import Path
import re
import sys
from standard_reader import read_rows


def number(value):
    if value is None or str(value).strip() == '':
        return None
    # Java exporters used ms/s suffixes. Keep signs and decimals; reject malformed input.
    value = re.sub(r'(?:ms|s|秒|%)$', '', str(value).strip()).strip()
    result = Decimal(value)
    if not result.is_finite():
        raise ValueError('Non-finite source value')
    return result


def select(rows, rule):
    result = []
    for row in rows:
        business = row.get('业务名称', '')
        if 'businessContains' in rule and (rule['businessContains'] in business.lower()) != rule['businessMatches']:
            continue
        if 'stepType' in rule:
            # Explicit configuration replaces the former implicit step-type dispatch.
            step = row.get('步骤类型')
            if not step:
                name = row.get('步骤名称', row.get('业务步骤', ''))
                step = '页面加载' if '页面' in name else '步骤时长'
            if step != rule['stepType']:
                continue
        result.append(row)
    return result


def calculate(rows, rule, parameters):
    op, field = rule['operation'], rule['field']
    if op == 'distinct_count':
        return Decimal(len({r[field] for r in rows if r[field] != ''})), None
    if op == 'keyword_match':
        words = parameters.get('keywords') or []
        if isinstance(words, str):
            words = json.loads(words)
        if not words:
            return None, '未配置关键词'
        return Decimal(sum(any(word in r[field] for word in words) for r in rows)), None
    if op in ('count_gt', 'count_lt', 'count_distinct_gt'):
        threshold = number(rule.get('constant', parameters.get('baseline')))
        if threshold is None:
            return None, '未配置计算基准'
        matching = [r for r in rows if number(r[field]) is not None and
                    (number(r[field]) < threshold if op == 'count_lt' else number(r[field]) > threshold)]
        return Decimal(len({r[rule['distinct']] for r in matching}) if op == 'count_distinct_gt' else len(matching)), None
    values = [number(r[field]) for r in rows]
    values = [v for v in values if v is not None]
    if op == 'single':
        if len(rows) > 1:
            raise ValueError('Direct metric requires a single source row: '+rule['metricCode'])
        return values[0] if values else None, None
    if op == 'sum':
        return sum(values, Decimal(0)) if values else None, None
    if op == 'avg':
        return (sum(values)/len(values)).quantize(Decimal('0.0001'), rounding=ROUND_HALF_UP) if values else None, None
    if op == 'success_ratio':
        access = [number(r[rule['accessField']]) for r in rows]
        if any(v is None for v in access) or len(values) != len(rows):
            return None, '访问量或失败量缺失'
        total = sum(access, Decimal(0))
        return ((total-sum(values))/total*100).quantize(Decimal('0.0001'), rounding=ROUND_HALF_UP) if total else None, None
    raise ValueError('Unsupported configured operation: '+op)


# 每个指标保留的明细行上限。
# 平台协议限制插件输出 ≤10MB，而明细是把源行整份复制进 JSON（details + source_details），
# 数据量大时会把输出撑爆（Worker 报 "Plugin output missing or exceeds 10MB"，整批不入库）。
# 因此只保留前 N 行样本参与落库，真实行数用 detail_row_count / source_row_count 表达，
# 指标值本身始终按全量行计算，不受影响。
DETAIL_ROW_LIMIT = 100
# 插件输出体积上限（协议硬上限 10MB，留 2MB 余量给元数据）
MAX_OUTPUT_BYTES = 8 * 1024 * 1024

# 取数脚本一轮只产出一份合并 CSV：payload 列里放该行所属数据集自己的列（JSON 对象），
# dataset / data_time / _empty 三列保持不变；旧的「每数据集一份 CSV、列直接铺平」格式继续兼容。
MERGED_PAYLOAD_COLUMN = 'payload'
# 行数上限：旧格式 50001 行；合并格式一份文件装多个数据集，行数上限放宽（体积仍受 10MB 协议限制）
MAX_SOURCE_ROWS = 50001
MAX_MERGED_ROWS = 200000


def merged_row(row, dataset, time, dataset_columns):
    """解析合并 CSV 的 payload 列，并登记该数据集自己的列名（用于按数据集校验列是否存在）。"""
    try:
        payload = json.loads(row[MERGED_PAYLOAD_COLUMN])
    except Exception:
        raise ValueError('Invalid merged row payload for dataset: '+dataset)
    if not isinstance(payload, dict):
        raise ValueError('Invalid merged row payload for dataset: '+dataset)
    columns = set(payload)
    known = dataset_columns.get(dataset)
    if known is None:
        dataset_columns[dataset] = columns
    elif known != columns:
        raise ValueError('Inconsistent source columns for dataset: '+dataset)
    merged = dict(payload)
    merged['dataset'] = dataset
    merged['data_time'] = time
    merged['_empty'] = row['_empty']
    return merged


def production_rows(request, config, detail_limit=DETAIL_ROW_LIMIT):
    if request['source']['worker']['categoryCode'] != config['categoryCode']:
        raise ValueError('Category binding mismatch')
    parameters = {r['metricCode']: r for r in request['source'].get('metricRules') or []}
    with open(request['inputPath'], encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream, strict=True)
        headers = reader.fieldnames or []
        if 'dataset' not in headers:
            # Keep all existing standard CSV metrics in the same category task.
            return read_rows(request['inputPath'], request['source'], {'categoryCode': config['categoryCode']})
        if len(headers) != len(set(headers)) or not {'dataset', 'data_time', '_empty'} <= set(headers):
            raise ValueError('Invalid production CSV headers')
        merged = MERGED_PAYLOAD_COLUMN in headers
        max_rows = MAX_MERGED_ROWS if merged else MAX_SOURCE_ROWS
        groups = {}
        dataset_columns = {}
        for line, row in enumerate(reader, 2):
            if line - 1 > max_rows or None in row or any(v is None for v in row.values()):
                raise ValueError('Invalid CSV row width or too many rows')
            dataset, time = row['dataset'], row['data_time']
            parsed = datetime.fromisoformat(time)
            if parsed.tzinfo or parsed.microsecond:
                raise ValueError('Source time must be Beijing local time to seconds')
            if dataset not in config['datasets']:
                raise ValueError('Unconfigured source dataset: '+dataset)
            if row['_empty'] not in ('0', '1'):
                raise ValueError('Invalid explicit empty marker')
            if merged:
                row = merged_row(row, dataset, time, dataset_columns)
            groups.setdefault((dataset, time), []).append(row)
    output = []
    for (dataset, time), source_rows in groups.items():
        empty = [r for r in source_rows if r['_empty'] == '1']
        if empty and len(source_rows) != 1:
            raise ValueError('Empty marker mixed with source rows')
        rows = [] if empty else source_rows
        # 合并格式按「该数据集自己的列」校验，旧的每数据集一份 CSV 仍按整表表头校验
        allowed = dataset_columns.get(dataset) if merged else set(headers)
        for rule in config['datasets'][dataset]:
            code = rule['metricCode']
            if code not in parameters:
                raise ValueError('Metric code missing/disabled in category: '+code)
            if parameters[code].get('status', 1) != 1:
                continue
            for field in [rule['field']]+([rule['distinct']] if 'distinct' in rule else [])+([rule['accessField']] if 'accessField' in rule else []):
                if field not in allowed:
                    raise ValueError('Missing column '+field+' for '+code)
            selected = select(rows, rule)
            value, missing = calculate(selected, rule, parameters[code])
            output.append(dict(metric_code=code, metric_value=None if value is None else str(value),
                               data_time=time, dataset=dataset,
                               details=selected[:detail_limit], source_details=rows[:detail_limit],
                               detail_row_count=len(selected), source_row_count=len(rows),
                               rule_status=missing or '已配置',
                               calculation=rule, parameters=parameters[code],
                               _source={'file': request['sourceFile'], 'dataset': dataset, 'sourceRows': len(rows)}))
    if not output:
        raise ValueError('No configured metric records')
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--request', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    try:
        request = json.loads(Path(args.request).read_text(encoding='utf-8'))
        config = json.loads(Path(__file__).with_name('config.json').read_text(encoding='utf-8'))
        limit = DETAIL_ROW_LIMIT
        while True:
            records = production_rows(request, config, limit)
            payload = json.dumps(dict(protocolVersion=1, outputType='metric-values', rowCount=len(records), records=records), ensure_ascii=False, allow_nan=False)
            if len(payload.encode('utf-8')) <= MAX_OUTPUT_BYTES or limit == 0:
                break
            # 仍然超限：明细行数减半后重算，指标值不受影响
            limit = limit // 2
        Path(args.output).write_text(payload, encoding='utf-8')
    except Exception as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
