"""Run scoped, real scheduler acceptance checks against the local satisfaction pipeline."""
import argparse
from datetime import datetime, timezone, timedelta
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
import uuid
import xml.etree.ElementTree as ET
import zipfile

from database_support import ROOT, environment, mysql


def literal(value):
    return "CONVERT(X'" + str(value).encode('utf-8').hex() + "' USING utf8mb4)"


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.values, target, self.env, self.connection = environment(args.env_file)
        if target.hostname not in ('localhost', '127.0.0.1') or target.path != '/system_operations_metrics':
            raise ValueError('Acceptance is restricted to the local standard platform database')
        self.database = target.path.lstrip('/')
        folder = ROOT / 'deploy/db_export' / args.backup_version
        manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
        if (manifest['database'] != self.database or manifest['host'] != target.hostname or
                manifest['port'] != (target.port or 3306) or
                hashlib.sha256((folder / manifest['file']).read_bytes()).hexdigest() != manifest['sha256']):
            raise ValueError('Full backup target/checksum mismatch')
        self.marker = 'acceptance_' + datetime.now().strftime('%Y%m%d%H%M%S') + '_' + uuid.uuid4().hex[:6]
        self.report = dict(marker=self.marker, backup=args.backup_version, checks=[], cleanup=None)
        self.names, self.hashes, self.keys = [], [], []
        self.original = None
        self.configured = False
        self.base_url = 'http://127.0.0.1:18080'
        self.key = None

    def sql(self, query):
        return mysql(self.connection, self.env, self.database, query)

    def api(self, path, method='GET', data=None, retry_auth=True):
        headers = {'Content-Type': 'application/json'}
        if self.key:
            headers['X-Session-Key'] = self.key
        request = urllib.request.Request(self.base_url + '/api' + path,
            data=json.dumps(data).encode() if data is not None else None, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            if error.code != 401 or not retry_auth or path == '/session/login':
                raise
            self.login()
            request.headers['X-session-key'] = self.key
            with urllib.request.urlopen(request, timeout=20) as response:
                result = json.load(response)
        if result.get('code') != 200:
            raise RuntimeError(result.get('message', 'API failed'))
        return result.get('data')

    def login(self):
        password = os.environ.get('METRIC_ADMIN_PASSWORD') or self.env.get('METRIC_ADMIN_PASSWORD')
        if not password:
            raise ValueError('Set METRIC_ADMIN_PASSWORD in the process environment')
        data = self.api('/session/login', 'POST', {'username': self.env.get('METRIC_ADMIN_USERNAME', 'admin'), 'password': password})
        self.key = data['sessionKey']
        self.keys.append(self.key)

    def persist(self):
        self.args.report.write_text(json.dumps(self.report, ensure_ascii=False, indent=2), encoding='utf-8')

    def passed(self, name, **details):
        self.report['checks'].append(dict(name=name, **details))
        self.persist()
        print('PASS:', name, flush=True)

    def wait(self, predicate, label, timeout=100):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = predicate()
            if result:
                return result
            time.sleep(2)
        raise TimeoutError(label)

    def digest(self, table):
        columns = self.sql(f"SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='{table}' ORDER BY ordinal_position").splitlines()
        cols = ','.join('`' + name + '`' for name in columns)
        return hashlib.sha256(self.sql(f'SELECT {cols} FROM `{table}` ORDER BY {cols}').encode()).hexdigest()

    def sample(self, suffix):
        buffer = io.BytesIO(self.sample_bytes)
        with zipfile.ZipFile(buffer, 'a') as archive:
            archive.comment = (self.marker + suffix).encode()
        data = buffer.getvalue()
        self.hashes.append(hashlib.sha256(data).hexdigest())
        return data

    def filename(self, tag, day=None):
        name = f'{day or self.day}_{self.marker}_{tag}.xlsx'
        self.names.append(name)
        return name

    def deliver(self, name, data, stable=True):
        part = self.inbox / (name + '.part')
        final = self.inbox / name
        if part.exists() or final.exists():
            raise ValueError('Acceptance file already exists')
        part.write_bytes(data)
        part.rename(final)
        if stable:
            os.utime(final, (time.time() - 10, time.time() - 10))
        return final

    def file_batch(self, name):
        return self.sql('SELECT id FROM collection_file WHERE source_code=\'satisfaction_report\' AND source_file=' + literal(name)).strip()

    def business_batch(self, name):
        return self.sql('SELECT id FROM satisfaction_report_batch WHERE source_file=' + literal(name)).strip()

    def attempt(self, name, status):
        return int(self.sql('SELECT COUNT(*) FROM collection_attempt WHERE source_code=\'satisfaction_report\' AND source_file=' + literal(name) + ' AND status=' + literal(status)).strip())

    def expected(self):
        namespace = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
        with zipfile.ZipFile(io.BytesIO(self.sample_bytes)) as archive:
            sheet = ET.fromstring(archive.read('xl/worksheets/sheet1.xml'))
            shared = []
            if 'xl/sharedStrings.xml' in archive.namelist():
                shared = [''.join(node.itertext()) for node in ET.fromstring(archive.read('xl/sharedStrings.xml')).findall('m:si', namespace)]
            cells = {}
            for cell in sheet.findall('.//m:c', namespace):
                value = cell.findtext('m:v', default='', namespaces=namespace)
                if cell.get('t') == 's':
                    value = shared[int(value)]
                elif cell.get('t') == 'inlineStr':
                    value = ''.join(cell.find('m:is', namespace).itertext())
                cells[cell.get('r')] = value
        def number(cell):
            text = cells[cell].strip().replace(',', '').replace('%', '')
            return None if text in ('', '—', '-') else Decimal(text)
        def ratio(numerator, denominator):
            return (sum(number(c) for c in numerator) * 100 / sum(number(c) for c in denominator)).quantize(Decimal('.0001'), rounding=ROUND_HALF_UP)
        return {
            ('客服投诉工单处理流程', '工单累计量'): number('B2'),
            ('客服投诉工单处理流程', '及时率'): ratio(['J2'], ['K2']),
            ('客服投诉工单处理流程', '96小时超时量'): number('P2'),
            ('客服投诉工单处理流程', '一次解决率'): number('X2'),
            ('客服投诉工单处理流程', '满意率'): number('AB2'),
            ('客服投诉工单处理流程', '重投率'): None,
            ('倒三角工单处理流程', '工单累计量'): sum(number('B' + str(row)) for row in (3, 4, 5)),
            ('倒三角工单处理流程', '及时率'): ratio(['J3', 'J4', 'J5'], ['K3', 'K4', 'K5']),
            ('倒三角工单处理流程', '回退率'): ratio(['G3', 'G4', 'G5'], ['K3', 'K4', 'K5']),
            ('倒三角工单处理流程', '满意率'): None,
            ('倒三角工单处理流程', '重投率'): None,
            ('倒三角工单处理流程', '一次解决率'): None,
            ('综调工单处理流程', '工单累计量'): number('B6'),
            ('综调工单处理流程', '及时率'): ratio(['J6'], ['K6']),
            ('综调工单处理流程', '满意率'): None,
            ('综调工单处理流程', '回退率'): None,
            ('综调工单处理流程', '一次解决率'): None,
        }

    def verify_metrics(self, name):
        batch = self.business_batch(name)
        rows = self.api('/collection/satisfaction-report?batchId=' + batch)['rows']
        expected = self.expected()
        assert len(rows) == len(expected) == 17
        for row in rows:
            actual = None if row['metricValue'] is None else Decimal(str(row['metricValue']))
            assert actual == expected[(row['groupName'], row['metricName'])], row['metricCode']
        assert self.sql('SELECT COUNT(*) FROM satisfaction_report_raw WHERE batch_id=' + literal(batch)).strip() == '224'
        return batch

    def stop_backend(self):
        root = str(ROOT).replace("'", "''")
        command = (f"$root='{root}'; $items=Get-NetTCPConnection -State Listen -LocalPort 18080; "
                   "foreach ($processId in @($items.OwningProcess | Select-Object -Unique)) { "
                   "$p=Get-CimInstance Win32_Process -Filter \"ProcessId=$processId\"; "
                   "if (!$p.CommandLine.Contains($root) -or $p.ExecutablePath -notmatch 'java.exe$') { throw 'Unexpected process' }; "
                   "Stop-Process -Id $processId }")
        subprocess.run(['powershell.exe', '-NoProfile', '-Command', command], check=True, capture_output=True)

    def restart(self):
        # Windows children can inherit pipe handles after PowerShell exits. Use a file
        # so communicate() cannot wait forever for the detached Java/Node processes.
        log = self.args.report.with_suffix('.restart.log')
        with log.open('wb') as output:
            subprocess.run(['powershell.exe', '-NoProfile', '-File', str(ROOT / 'deploy/scripts/start-local.ps1'),
                            '-Restart', '-SkipBuild', '-EnvFile', str(self.args.env_file.resolve())],
                           check=True, stdout=output, stderr=subprocess.STDOUT,
                           stdin=subprocess.DEVNULL, timeout=60, cwd=ROOT)
        def alive():
            try:
                return self.api('/session/config') is not None
            except Exception:
                return False
        self.wait(alive, 'Backend restart failed', timeout=100)
        self.login()

    def run(self):
        if os.name != 'nt':
            raise RuntimeError('This local acceptance runner requires Windows for service restart verification')
        if self.args.pause_for_ui:
            self.args.report.with_suffix('.continue').unlink(missing_ok=True)
        self.login()
        self.original = next(item['config'] for item in self.api('/collection/tasks') if item['code'] == 'satisfaction_report')
        self.report['originalConfiguration'] = self.original
        root = self.sql("SELECT config_value FROM system_config WHERE config_key='upload.base_path'").strip() or self.values['UPLOAD_BASE_PATH']
        self.root = Path(root).resolve()
        self.inbox, self.archive = self.root / self.original['importPath'], self.root / self.original['archivePath']
        if list(self.inbox.glob('*.xlsx')) or list(self.inbox.glob('*.part')):
            raise RuntimeError('Existing pending satisfaction files; acceptance will not consume or alter them')
        self.before = {name: self.digest(name) for name in ['satisfaction_report_batch', 'satisfaction_report_raw', 'satisfaction_report_result']}
        self.report['originalHashes'] = self.before
        self.sample_bytes = (ROOT / 'doc/原始数据文件样例/满意度/投诉工单效能报表_20260929113832_5153.xlsx').read_bytes()
        self.day = '2026-10-08'  # The confirmed business date of this specific sample, not its export timestamp.
        self.config = {**self.original, 'filePattern': '*' + self.marker + '*.xlsx', 'intervalSeconds': 10, 'settleSeconds': 5, 'enabled': True}
        self.configured = True
        self.api('/collection/tasks', 'POST', self.config)
        normal = self.filename('normal')
        data = self.sample('normal')
        part = self.inbox / (normal + '.part')
        part.write_bytes(data)
        previous = self.sql("SELECT last_scan_at FROM collection_source WHERE code='satisfaction_report'").strip()
        self.wait(lambda: self.sql("SELECT last_scan_at FROM collection_source WHERE code='satisfaction_report'").strip() != previous, 'No automatic scan')
        assert part.exists() and not self.file_batch(normal)
        self.passed('Partial file ignored by automatic scheduler')
        final = self.inbox / normal
        part.rename(final)
        os.utime(final, (time.time() + 120, time.time() + 120))
        previous = self.sql("SELECT last_scan_at FROM collection_source WHERE code='satisfaction_report'").strip()
        self.wait(lambda: self.sql("SELECT last_scan_at FROM collection_source WHERE code='satisfaction_report'").strip() != previous, 'No stable-wait scan')
        assert final.exists() and not self.file_batch(normal)
        self.passed('Unstable file retained without importing')
        os.utime(final, (time.time() - 10, time.time() - 10))
        self.wait(lambda: self.file_batch(normal) and not final.exists(), 'Automatic import/archival failed')
        batch = self.verify_metrics(normal)
        self.report['uiBatch'] = batch
        self.passed('Automatic import and independent 17-metric calculation match', rawValues=224, populated=10, missing=7)
        if self.args.pause_for_ui:
            self.report['readyForUi'] = True
            self.persist()
            print('READY_FOR_UI:', self.args.report, flush=True)
            self.wait(lambda: self.args.report.with_suffix('.continue').exists(), 'UI verification acknowledgement missing', timeout=240)
        identifier = self.file_batch(normal)
        self.deliver(normal, data)
        self.wait(lambda: self.attempt(normal, 'DUPLICATE') and not final.exists(), 'Duplicate not handled')
        assert self.file_batch(normal) == identifier and self.business_batch(normal) == batch
        self.verify_metrics(normal)
        self.passed('Repeated file produces no additional batch/results')
        bad, good = self.filename('00_bad'), self.filename('01_good')
        self.deliver(bad, b'acceptance invalid XLSX ' + self.marker.encode())
        self.deliver(good, self.sample('good'))
        self.wait(lambda: self.attempt(bad, 'FAILED') and self.file_batch(good), 'Bad file blocked later valid file')
        assert (self.inbox / bad).exists() and not self.file_batch(bad) and not self.business_batch(bad)
        self.verify_metrics(good)
        assert self.api('/collection/satisfaction-report')['errors']
        self.passed('Malformed file rolls back; later valid file still imports; error exposed in API')
        (self.inbox / bad).unlink()
        self.wait(lambda: not self.api('/collection/satisfaction-report')['errors'], 'Error state did not clear')
        today = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
        future = self.filename('invalid_day', today)
        self.deliver(future, self.sample('invalid_day'))
        self.wait(lambda: self.attempt(future, 'FAILED'), 'Current-day file was not rejected')
        assert not self.file_batch(future) and not self.business_batch(future)
        (self.inbox / future).unlink()
        self.passed('Invalid business date rejected without partial records')
        recovery = self.filename('recovery')
        recovery_data = self.sample('recovery')
        target = self.archive / f'{self.day}_{hashlib.sha256(recovery_data).hexdigest()}_{recovery}'
        conflict = b'acceptance archive conflict ' + self.marker.encode()
        if target.exists():
            raise RuntimeError('Archive conflict target already exists; refusing to overwrite it')
        target.write_bytes(conflict)
        self.deliver(recovery, recovery_data)
        self.wait(lambda: self.file_batch(recovery) and self.attempt(recovery, 'FAILED'), 'Archive conflict not detected')
        identifier = self.file_batch(recovery)
        assert self.sql('SELECT archived FROM collection_file WHERE id=' + literal(identifier)).strip() == '0'
        self.verify_metrics(recovery)
        self.stop_backend()
        assert target.read_bytes() == conflict
        target.unlink()
        self.restart()
        self.wait(lambda: self.sql('SELECT archived FROM collection_file WHERE id=' + literal(identifier)).strip() == '1', 'Archive recovery after restart failed')
        assert not (self.inbox / recovery).exists() and target.read_bytes() == recovery_data
        assert self.file_batch(recovery) == identifier
        self.verify_metrics(recovery)
        self.passed('Real backend restart resumes committed-but-unarchived file without duplicate results')

    def cleanup(self):
        if not self.configured:
            return
        try:
            self.api('/session/config')
        except Exception:
            self.restart()
        self.api('/collection/tasks', 'POST', {**self.config, 'enabled': False})
        names = ','.join(literal(name) for name in self.names) or 'NULL'
        for table, id_column in [('satisfaction_report_batch', 'id'), ('collection_file', 'id')]:
            rows = self.sql(f'SELECT {id_column} FROM {table} WHERE source_file IN ({names})').splitlines()
            for identifier in rows:
                if table == 'satisfaction_report_batch':
                    self.sql('START TRANSACTION; DELETE FROM satisfaction_report_result WHERE batch_id=' + literal(identifier) +
                             '; DELETE FROM satisfaction_report_raw WHERE batch_id=' + literal(identifier) +
                             '; DELETE FROM satisfaction_report_batch WHERE id=' + literal(identifier) + '; COMMIT;')
                else:
                    self.sql('DELETE FROM collection_file WHERE id=' + literal(identifier) + " AND source_code='satisfaction_report'")
        self.sql("DELETE FROM collection_attempt WHERE source_code='satisfaction_report' AND source_file IN (" + names + ')')
        for folder in [self.inbox, self.archive]:
            for path in folder.glob('*' + self.marker + '*'):
                resolved = path.resolve()
                if not resolved.is_relative_to(self.root) or path.is_symlink() or not path.is_file():
                    raise RuntimeError('Unexpected cleanup path: ' + str(path))
                path.unlink()
        self.api('/collection/tasks', 'POST', self.original)
        self.wait(lambda: not self.api('/collection/satisfaction-report')['errors'], 'Task error remained after cleanup')
        after = {name: self.digest(name) for name in self.before}
        assert after == self.before, 'Original satisfaction data changed'
        restored = next(item['config'] for item in self.api('/collection/tasks') if item['code'] == 'satisfaction_report')
        assert restored == self.original, 'Source configuration not restored'
        self.report['cleanup'] = dict(originalDataUnchanged=True, configurationRestored=True, temporaryFilesRemoved=True)
        self.persist()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, required=True)
    parser.add_argument('--backup-version', required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--pause-for-ui', action='store_true')
    args = parser.parse_args()
    acceptance = Acceptance(args)
    try:
        acceptance.run()
    except Exception as error:
        acceptance.report['failure'] = str(error)
        raise
    finally:
        try:
            acceptance.cleanup()
        except Exception as error:
            acceptance.report['cleanupFailure'] = str(error)
            raise
        finally:
            for key in list(acceptance.keys):
                try:
                    acceptance.key = key
                    acceptance.api('/session/end', 'POST', {'sessionKey': key}, retry_auth=False)
                except Exception:
                    pass
            acceptance.persist()
    print('Acceptance completed; report:', args.report, flush=True)


if __name__ == '__main__':
    main()
