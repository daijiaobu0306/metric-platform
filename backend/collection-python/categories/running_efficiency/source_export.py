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
import tempfile
import time
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
    started = time.monotonic()
    max_pages = int(task.get('max_pages') or 400)
    for page in range(1, max_pages + 1):
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
        print('  BPM %s page=%d rows=%d total=%s 累计=%d 用时=%.1fs'
              % (task['dataset'], page, len(rows), total, len(records), time.monotonic()-started), flush=True)
        if total is not None:
            if len(records) >= int(total):
                break
            if not rows:
                raise ValueError('BPM ended before declared total')
        elif len(rows) < payload['pageSize']:
            break
    else:
        raise ValueError('BPM 分页超过 %d 页仍未取完（已取 %d 条），'
                         '请确认接口 pageNum 是否生效，或缩小 days_ago 时间窗' % (max_pages, len(records)))
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


# 合并 CSV：一轮取数只产出一份文件，payload 列里放该行所属数据集自己的列（JSON 对象）。
# 平台协议规定单个输入文件 ≤10MB，且同一个数据集的行不能跨文件拆分（否则指标会被算成多批），
# 因此默认只写一份文件，超限时按数据集边界拆成多份（_2、_3 …）。
MERGED_HEADER = ['dataset', 'data_time', '_empty', 'payload']
MERGED_MAX_BYTES = 8 * 1024 * 1024
MERGED_MAX_ROWS = 45000
# Must match Worker raw input capacity; output JSON/HTTP remains limited to 10MB.
MAX_INPUT_BYTES = 64 * 1024 * 1024
MAX_DATASET_ROWS = 200000


def check_dataset(dataset, headers, rows, data_time):
    # Include BOM/header margin; do not publish permanently uncollectable files.
    if len(rows) > MAX_DATASET_ROWS or len(merged_block(dataset, headers, rows, data_time).encode("utf-8")) + 1024 > MAX_INPUT_BYTES:
        raise ValueError("Dataset exceeds 64MB/200000 rows; complete-dataset export required: " + dataset)


def acquisition_report(skipped):
    path = os.environ.get("COLLECTION_ACQUISITION_RESULT")
    if path:
        Path(path).write_text(json.dumps({"failedDatasets": [name for name, _ in skipped]}, ensure_ascii=False), encoding="utf-8")


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
        check_dataset(dataset, headers, rows, data_time)
        text = merged_block(dataset, headers, rows, data_time)
        block_bytes = len(text.encode('utf-8'))
        block_rows = len(rows) if rows else 1
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


def collect(config_path, connections_path, output_dir, kind=None, sub_category=None):
    config = json.loads(Path(config_path).read_text(encoding='utf-8'))
    secrets = json.loads(Path(connections_path).read_text(encoding='utf-8'))
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    connections = {}
    now = datetime.now(timezone(timedelta(hours=8))).replace(microsecond=0)
    batch = now.strftime('%Y%m%d_%H%M%S')+'_'+uuid4().hex[:12]
    selected = [t for t in config['tasks'] if (kind is None or t['kind'] == kind)
                and (sub_category is None or t.get('sub_category') == sub_category)]
    if not selected:
        raise ValueError('No source tasks selected')
    try:
        with tempfile.TemporaryDirectory(prefix='.source-', dir=output_dir) as temporary:
            blocks = []
            skipped_datasets = []
            data_time = now.isoformat(timespec='seconds').split('+')[0]
            print('共 %d 个数据集，开始取数' % len(selected), flush=True)
            for index, task in enumerate(selected):
                task_started = time.monotonic()
                print('[%d/%d] %s (%s) ...' % (index+1, len(selected), task['dataset'], task['kind']), flush=True)
                try:
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
                            print('  SQL 执行中（%s）...' % task['db_type'], flush=True)
                            cursor.execute(task['sql'])
                            headers = [str(d[0]) for d in cursor.description]
                            if any('?' in h for h in headers):
                                # 中文别名被字符集转坏时，下游按列名取数会全部失败，这里提前报清楚
                                raise ValueError('源列名出现问号，通常为 JDBC 字符集不匹配，实际列名: ' + repr(headers))
                            rows = cursor.fetchall()
                            print('  SQL 返回 %d 行，用时=%.1fs' % (len(rows), time.monotonic()-task_started), flush=True)
                        finally:
                            cursor.close()
                    if len(set(headers)) != len(headers) or set(headers) & {'dataset', 'data_time', '_empty'}:
                        raise ValueError('Invalid or duplicate source headers')
                    check_dataset(task["dataset"], headers, rows, data_time)
                except Exception as error:
                    # 接口不通或查询失败时只跳过该数据集：打印原因后继续取下一个，不中断整轮。
                    reason = str(error)[:400]
                    print('[%d/%d] %s 跳过（取数失败）：%s'
                          % (index + 1, len(selected), task['dataset'], reason), flush=True)
                    skipped_datasets.append((task['dataset'], reason))
                    continue
                blocks.append((task['dataset'], headers, rows, data_time))
                print('[%d/%d] %s 完成 rows=%d 用时=%.1fs'
                      % (index+1, len(selected), task['dataset'], len(rows), time.monotonic()-task_started), flush=True)
            acquisition_report(skipped_datasets)
            if not blocks:
                # 全部数据集都取不到数：明确报错，避免“静默产出空文件”被当成采集成功
                raise RuntimeError('全部 %d 个数据集取数失败，未产出任何文件：%s'
                                   % (len(selected), '；'.join('%s（%s）' % (name, reason[:120]) for name, reason in skipped_datasets)))
            targets = write_merged_csv(Path(temporary), batch, blocks)
            for target in targets:
                target.replace(output_dir/target.name)
            print('本轮产出 %d 个合并文件：%s'
                  % (len(targets), '、'.join(target.name for target in targets)), flush=True)
            if skipped_datasets:
                print('本轮跳过 %d 个数据集：%s'
                      % (len(skipped_datasets), '、'.join('%s（%s）' % (name, reason[:120]) for name, reason in skipped_datasets)),
                      flush=True)
                raise RuntimeError("Partial acquisition: failed datasets reported; successful files remain available")
    finally:
        for connection in connections.values():
            connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-config', required=True)
    parser.add_argument('--connections', required=True, help='Server-only JSON, never committed')
    parser.add_argument('--output-dir', required=True, help='Existing category task inbox')
    parser.add_argument('--kind', choices=('jdbc', 'bpm'))
    parser.add_argument('--sub-category', choices=('系统性能', '系统异常'), help='仅筛选运行效率小类；默认包含两个小类')
    args = parser.parse_args()
    collect(args.source_config, args.connections, args.output_dir, args.kind, args.sub_category)


if __name__ == '__main__':
    main()
