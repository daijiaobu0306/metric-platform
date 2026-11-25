"""Server source adapter. One former sheet per CSV; no platform database writes."""
import argparse
import copy
import csv
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
            staged = []
            skipped_datasets = []
            print('共 %d 个数据集，开始取数' % len(selected), flush=True)
            for index, task in enumerate(selected):
                task_started = time.monotonic()
                print('[%d/%d] %s (%s) ...' % (index+1, len(selected), task['dataset'], task['kind']), flush=True)
                if task['kind'] == 'bpm':
                    # BPM 接口不可用时不阻断整轮：跳过该数据集并打印原因，其余数据集照常产出。
                    try:
                        headers, rows = bpm_rows(task, secrets['bpm'], now)
                    except Exception as error:
                        print('[%d/%d] %s 跳过（BPM 取数失败）：%s'
                              % (index + 1, len(selected), task['dataset'], str(error)[:400]), flush=True)
                        skipped_datasets.append(task['dataset'])
                        continue
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
                target = Path(temporary)/(batch+'_'+str(index)+'.csv')
                with target.open('w', encoding='utf-8-sig', newline='') as stream:
                    writer = csv.writer(stream)
                    writer.writerow(['dataset', 'data_time', '_empty']+headers)
                    for row in rows:
                        writer.writerow([task['dataset'], now.isoformat(timespec='seconds').split('+')[0], '0']+[None if v is None else str(v) for v in row])
                    if not rows:
                        # Explicit successful empty result; distinguish zero from a failed source query.
                        writer.writerow([task['dataset'], now.isoformat(timespec='seconds').split('+')[0], '1']+['']*len(headers))
                    stream.flush()
                    os.fsync(stream.fileno())
                staged.append(target)
                print('[%d/%d] %s 完成 rows=%d 用时=%.1fs'
                      % (index+1, len(selected), task['dataset'], len(rows), time.monotonic()-task_started), flush=True)
            for target in staged:
                target.replace(output_dir/target.name)
            if skipped_datasets:
                print('本轮跳过 %d 个 BPM 数据集：%s' % (len(skipped_datasets), '、'.join(skipped_datasets)), flush=True)
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
