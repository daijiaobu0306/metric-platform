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
from urllib.request import Request, urlopen
from urllib.parse import urlencode
from uuid import uuid4


def post(url, body, headers):
    with urlopen(Request(url, data=body, headers=headers), timeout=120) as response:
        return json.loads(response.read().decode('utf-8'), parse_float=Decimal)


def bpm_rows(task, config, now):
    auth = {k: config[k] for k in ('client_id', 'client_secret', 'username', 'password')}
    auth['grant_type'] = 'password'
    token = post(config['token_url'], urlencode(auth).encode(), {'Content-Type': 'application/x-www-form-urlencoded'})['access_token']
    payload = copy.deepcopy(task['payload'])
    payload.update(startTime=int((now-timedelta(days=task['days_ago'])).timestamp()*1000), endTime=int(now.timestamp()*1000))
    headers = {'Content-Type': 'application/json', 'Authorization': 'Bearer '+token,
               'Origin': 'http://appuser.sn.cmcc', 'Referer': 'http://appuser.sn.cmcc/wg-bpm/'}
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
            staged = []
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
                        connections[task['db_type']] = jaydebeapi.connect(db['driver_class'], db['url'], [db['user'], db['password']], db['jar_path'])
                    cursor = connections[task['db_type']].cursor()
                    try:
                        cursor.execute(task['sql'])
                        headers = [str(d[0]) for d in cursor.description]
                        rows = cursor.fetchall()
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
            for target in staged:
                target.replace(output_dir/target.name)
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
