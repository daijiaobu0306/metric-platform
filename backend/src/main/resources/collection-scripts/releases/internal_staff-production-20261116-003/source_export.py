"""Server source adapter. One former sheet per CSV; no platform database writes."""
import argparse
import copy
import csv
import io
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from urllib.parse import urlencode, urlsplit
from uuid import uuid4


def post(url, body, headers):
    try:
        with urlopen(Request(url, data=body, headers=headers), timeout=120) as response:
            return json.loads(response.read().decode('utf-8'), parse_float=Decimal)
    except HTTPError as error:
        # 把接口返回体带出来，否则 400/500 只能看到状态码，无法定位
        detail = error.read().decode('utf-8', 'replace')[:800]
        raise RuntimeError('HTTP %d %s :: %s' % (error.code, url, detail)) from None


def bpm_rows(task, config, now):
    auth = {k: config[k] for k in ('client_id', 'client_secret', 'username', 'password')}
    auth['grant_type'] = 'password'
    token = post(config['token_url'], urlencode(auth).encode(), {'Content-Type': 'application/x-www-form-urlencoded'})['access_token']
    payload = copy.deepcopy(task['payload'])
    payload.update(startTime=int((now-timedelta(days=task['days_ago'])).timestamp()*1000), endTime=int(now.timestamp()*1000))
    origin = config.get('origin') or (urlsplit(config['token_url']).scheme + '://' + urlsplit(config['token_url']).netloc)
    referer = config.get('referer') or origin + '/wg-bpm/'
    headers = {'Content-Type': 'application/json', 'Authorization': 'Bearer '+token,
               'Origin': origin, 'Referer': referer}
    records, seen = [], set()
    for page in range(1, 10001):
        payload['pageNum'] = page
        result = post(config['api_url'], json.dumps(payload).encode(), headers)
        data = result.get('dataList')
        if isinstance(data, str):
            data = json.loads(data, parse_float=Decimal)
        if not isinstance(data, dict) or not isinstance(data.get('records'), list):
            raise ValueError('BPM response missing dataList.records: '+task['dataset'])
        rows = data['records']
        fingerprint = json.dumps(rows, sort_keys=True, default=str)
        if rows and fingerprint in seen:
            raise ValueError('BPM repeated page; refusing incomplete export')
        seen.add(fingerprint)
        records.extend(rows)
        total = data.get('total')
        if total is not None:
            if len(records) >= int(total):
                break
            if not rows:
                raise ValueError('BPM ended before declared total')
        elif len(rows) < payload['pageSize']:
            break
    else:
        raise ValueError('BPM exceeded page limit')
    output = []
    for record in records:
        if any(v is not None and any(k in str(v) for k in task.get('exclude_keywords', [])) for v in record.values()):
            continue
        field = task.get('exclude_empty_field')
        if field and (record.get(field) is None or str(record[field]).strip() == ''):
            continue
        field = task.get('min_value_field')
        if field and Decimal(str(record.get(field, '0'))) <= Decimal(str(task['min_value'])):
            continue
        row = []
        for field in task['fields']:
            value = record.get(field)
            if task['handler'] == 'business_duration' and field in ('dealTime', 'clientFreeTime', 'responseTime') and value is not None:
                # Preserve the running program's /1,000,000 conversion (source is microseconds).
                value = (Decimal(str(value))/Decimal(1000000)).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
            row.append(value)
        output.append(row)
    return list(task['fields'].values()), output


def jdbc_url(raw, db_type):
    """确保连接使用 UTF-8：否则中文列别名会变成乱码或 ?，导致下游按列名取不到数据。"""
    if 'characterEncoding' in raw:
        return raw
    sep = '&' if '?' in raw else '?'
    if db_type == 'mysql':
        return raw + sep + 'useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
    return raw + sep + 'characterEncoding=UTF-8&useUnicode=true'


def select_labels(sql):
    """按 SQL 的列别名生成 CSV 表头。

    部分 JDBC 驱动（如连接串带 useOldAliasMetadataBehavior=true）对“普通列 + 别名”
    返回的是原始列名 business_name，而表达式仍返回别名，此时下游按中文列名取数会失败
    （Missing column 业务名称）。因此表头统一取 SQL 中的别名，description 只用于核对列数。
    """
    depth = 0
    quote = None
    commas = []
    words = []
    word_start = None

    def close_word(end):
        if word_start is not None:
            words.append((word_start, end))

    index = 0
    while index < len(sql):
        char = sql[index]
        if quote is not None:
            if char == quote:
                quote = None
        elif char in ("'", '"', '`'):
            close_word(index)
            word_start = None
            quote = char
        elif char == '(':
            close_word(index)
            word_start = None
            depth += 1
        elif char == ')':
            close_word(index)
            word_start = None
            depth -= 1
        elif depth == 0 and char == ',':
            close_word(index)
            word_start = None
            commas.append(index)
        elif depth == 0 and (char.isalnum() or char == '_'):
            if word_start is None:
                word_start = index
        else:
            close_word(index)
            word_start = None
        index += 1
    close_word(len(sql))

    list_start = None
    list_end = None
    for begin, end in words:
        text = sql[begin:end].lower()
        if text == 'select':
            list_start = end
        elif text == 'from' and list_start is not None:
            list_end = begin
            break
    if list_start is None or list_end is None:
        raise ValueError('Cannot resolve column aliases from source SQL: ' + sql[:120])

    items = []
    previous = list_start
    for comma in commas:
        if list_start < comma < list_end:
            items.append(sql[previous:comma])
            previous = comma + 1
    items.append(sql[previous:list_end])

    labels = []
    for item in items:
        item = item.strip()
        if item.lower().startswith('distinct '):
            item = item[len('distinct '):].strip()
        match = re.search(r'\bas\s+(`[^`]+`|"[^"]+"|[A-Za-z_][A-Za-z0-9_]*)\s*$', item, re.IGNORECASE)
        if not match:
            raise ValueError('Source SQL column has no AS alias: ' + item[:120])
        alias = match.group(1)
        labels.append(alias[1:-1] if alias[0] in '`"' else alias)
    return labels


# 合并 CSV：一轮取数只产出一份文件，payload 列里放该行所属数据集自己的列（JSON 对象）。
# 平台协议规定单个输入文件 ≤10MB，且同一个数据集的行不能跨文件拆分（否则指标会被算成多批），
# 因此默认只写一份文件，超限时按数据集边界拆成多份（_2、_3 …）。
MERGED_HEADER = ['dataset', 'data_time', '_empty', 'payload']
MERGED_MAX_BYTES = 8 * 1024 * 1024
MERGED_MAX_ROWS = 45000


def merged_block(dataset, headers, rows, data_time):
    """把一个数据集的行转成合并 CSV 的文本块。"""
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    if rows:
        for row in rows:
            if len(row) != len(headers):
                raise ValueError('Source row width mismatch: '+dataset)
            payload = {name: ('' if value is None else str(value)) for name, value in zip(headers, row)}
            writer.writerow([dataset, data_time, '0', json.dumps(payload, ensure_ascii=False)])
    else:
        # 明确标记「查询成功但无数据」，与取数失败区分开
        payload = {name: '' for name in headers}
        writer.writerow([dataset, data_time, '1', json.dumps(payload, ensure_ascii=False)])
    return buffer.getvalue()


def write_merged_csv(output_dir, batch, blocks):
    """blocks: [(dataset, headers, rows, data_time)] → 产出一份合并 CSV（必要时按数据集边界拆分）。"""
    output_dir = Path(output_dir)
    written = []
    parts = []
    size = 0
    count = 0
    for dataset, headers, rows, data_time in blocks:
        text = merged_block(dataset, headers, rows, data_time)
        block_bytes = len(text.encode('utf-8'))
        block_rows = len(rows) if rows else 1
        if block_bytes > MERGED_MAX_BYTES:
            # 单个数据集本身就超限时无法再拆（拆了指标会被算成多批），保留一份并告警
            print('警告：数据集 %s 单独就有 %d 字节，超过单文件上限 %d 字节，平台可能拒绝该文件'
                  % (dataset, block_bytes, MERGED_MAX_BYTES), flush=True)
        if parts and (size + block_bytes > MERGED_MAX_BYTES or count + block_rows > MERGED_MAX_ROWS):
            written.append(write_merged_part(output_dir, batch, len(written) + 1, parts))
            parts, size, count = [], 0, 0
        parts.append(text)
        size += block_bytes
        count += block_rows
    if parts:
        written.append(write_merged_part(output_dir, batch, len(written) + 1, parts))
    return written


def write_merged_part(output_dir, batch, index, parts):
    name = batch+'.csv' if index == 1 else '%s_%d.csv' % (batch, index)
    target = Path(output_dir)/name
    with target.open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(MERGED_HEADER)
        for text in parts:
            stream.write(text)
        stream.flush()
        os.fsync(stream.fileno())
    return target


def collect(config_path, connections_path, output_dir, kind=None):
    config = json.loads(Path(config_path).read_text(encoding='utf-8'))
    secrets = json.loads(Path(connections_path).read_text(encoding='utf-8'))
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    connections = {}
    now = datetime.now(timezone(timedelta(hours=8))).replace(microsecond=0)
    batch = now.strftime('%Y%m%d_%H%M%S')+'_'+uuid4().hex[:12]
    selected = [t for t in config['tasks'] if kind is None or t['kind'] == kind]
    if not selected:
        raise ValueError('No source tasks selected')
    try:
        with tempfile.TemporaryDirectory(prefix='.source-', dir=output_dir) as temporary:
            blocks = []
            data_time = now.isoformat(timespec='seconds').split('+')[0]
            for index, task in enumerate(selected):
                if task['kind'] == 'bpm':
                    headers, rows = bpm_rows(task, secrets['bpm'], now)
                else:
                    import jpype
                    import jaydebeapi
                    db = secrets['databases'][task['db_type']]
                    if not jpype.isJVMStarted():
                        jpype.startJVM(classpath=[c['jar_path'] for c in secrets['databases'].values()], convertStrings=True)
                    if task['db_type'] not in connections:
                        connections[task['db_type']] = jaydebeapi.connect(db['driver_class'], jdbc_url(db['url'], task['db_type']), [db['user'], db['password']], db['jar_path'])
                    cursor = connections[task['db_type']].cursor()
                    try:
                        cursor.execute(task['sql'])
                        headers = select_labels(task['sql'])
                        if len(headers) != len(cursor.description):
                            raise ValueError('源 SQL 别名数量与查询结果列数不一致: %d/%d' % (len(headers), len(cursor.description)))
                        if any('?' in h for h in headers):
                            # 中文别名被字符集转坏时，下游按列名取数会全部失败，这里提前报清楚
                            raise ValueError('源列名出现问号，通常为 JDBC 字符集不匹配，实际列名: ' + repr(headers))
                        rows = cursor.fetchall()
                    finally:
                        cursor.close()
                if len(set(headers)) != len(headers) or set(headers) & {'dataset', 'data_time', '_empty'}:
                    raise ValueError('Invalid or duplicate source headers')
                blocks.append((task['dataset'], headers, rows, data_time))
            targets = write_merged_csv(Path(temporary), batch, blocks)
            for target in targets:
                target.replace(output_dir/target.name)
            print('本轮产出 %d 个合并文件：%s'
                  % (len(targets), '、'.join(target.name for target in targets)), flush=True)
    finally:
        for connection in connections.values():
            connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-config', required=True)
    parser.add_argument('--connections', required=True, help='Server-only JSON, never committed')
    parser.add_argument('--output-dir', required=True, help='Existing category task inbox')
    parser.add_argument('--kind', choices=('jdbc', 'bpm'))
    args = parser.parse_args()
    collect(args.source_config, args.connections, args.output_dir, args.kind)


if __name__ == '__main__':
    main()
