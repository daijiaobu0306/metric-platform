"""Prepare a new database or upgrade an existing one after a versioned full backup."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import time

from database_support import ROOT, environment, java_runtime, mysql

SOURCE = (ROOT / 'deploy/database/DatabasePreparation.java').read_text(encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['init', 'upgrade'])
    parser.add_argument('--env-file', type=Path, required=True)
    parser.add_argument('--expected-database', required=True, help='Explicitly verify the target database name')
    parser.add_argument('--backup-version', required=True, help='Unused V1/V2/... backup version, or auto')
    parser.add_argument('--skip-if-current', action='store_true', help='Upgrade only: no backup or writes if already current')
    parser.add_argument('--create-empty', action='store_true', help='Create the named database if absent (init only)')
    args = parser.parse_args()
    values, target, env, connection = environment(args.env_file)
    database = target.path.lstrip('/')
    if database != args.expected_database:
        parser.error('Target database does not match --expected-database')
    if args.mode != 'init' and args.create_empty:
        parser.error('--create-empty is only valid for init')
    if args.mode == 'init' and args.skip_if_current:
        parser.error('--skip-if-current is only valid for upgrade')
    if args.backup_version == 'auto':
        ledger = (ROOT / 'doc/数据库版本记录.md').read_text(encoding='utf-8')
        occupied = [int(number) for number in re.findall(r'^## V(\d+)(?:[：:\s]|$)', ledger, re.MULTILINE)]
        occupied += [int(path.name[1:]) for path in (ROOT / 'deploy/db_export').glob('V*')
                     if re.fullmatch(r'V[1-9][0-9]*', path.name)]
        args.backup_version = 'V' + str(max(occupied, default=0) + 1)
    if not re.fullmatch(r'V[1-9][0-9]*', args.backup_version):
        parser.error('Invalid backup version')
    if args.mode == 'init':
        password = env.get('METRIC_ADMIN_PASSWORD', '')
        if len(password) < 10 or 'change-me' in password.lower():
            parser.error('Set METRIC_ADMIN_PASSWORD to your own password of at least 10 characters')
        if args.create_empty:
            mysql(connection, env, sql=f'CREATE DATABASE IF NOT EXISTS `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
        tables = mysql(connection, env, database, 'SHOW TABLES').splitlines()
        if tables:
            parser.error('init requires an empty database; existing tables will never be overwritten')
    else:
        history = mysql(connection, env, database,
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='flyway_schema_history'").strip()
        if history != '1':
            parser.error('Upgrade requires existing Flyway history; review a legacy database baseline manually')
    baseline = ROOT / 'deploy/db_export/bootstrap'
    manifest = json.loads((baseline / 'manifest.json').read_text(encoding='utf-8'))
    for name, expected in manifest['files'].items():
        if hashlib.sha256((baseline / name).read_bytes()).hexdigest() != expected:
            parser.error('Bootstrap checksum mismatch: ' + name)
    print(f'Preparing {target.hostname}:{target.port or 3306}/{database} ({args.mode})', flush=True)
    # Use the precompiled image launcher, or the host JDK in manual maintenance mode.
    with java_runtime(values, env) as (work, run):
        migrations = work / 'migrations'
        migrations.mkdir()
        skip_bad = args.mode == 'init' or mysql(connection, env, database,
            "SELECT count(*) FROM flyway_schema_history WHERE version='20261012190000' AND success=1").strip() == '0'
        for file in (ROOT / 'backend/src/main/resources/db/migration').glob('*.sql'):
            if not (file.name.startswith('V') or file.name.startswith(('beforeEachMigrate__', 'afterEachMigrate__'))):
                continue
            if skip_bad and file.name.startswith('V20261012190000__'):
                continue
            shutil.copyfile(file, migrations / file.name)
        if args.skip_if_current:
            result = run(SOURCE, ['check', str(migrations), manifest['baselineVersion']])
            if result.returncode == 0:
                print('Database is current; no backup or migration needed.', flush=True)
                return
            if result.returncode != 10:
                raise subprocess.CalledProcessError(result.returncode, result.args)
        # Checking an up-to-date database is read-only. Require exclusive access
        # only when proceeding with backup and initialization or migration.
        # 容器升级发生在应用启动前，旧后端的连接往往还没释放完（部署/重启的重叠窗口）。
        # 这里等待其自然断开再继续，避免一次连接冲突就写死失败标记、把环境锁在重启循环里。
        # 超时仍拒绝执行：备份必须在独占连接下进行，否则备份内容可能不一致。
        raw_wait = os.environ.get('METRIC_DB_EXCLUSIVE_WAIT_SECONDS', '120').strip()
        wait_seconds = int(raw_wait) if raw_wait.isdigit() else 120
        deadline = time.monotonic() + wait_seconds
        while True:
            active = mysql(connection, env, database,
                'SELECT count(*) FROM information_schema.processlist WHERE db=DATABASE() AND id<>CONNECTION_ID()').strip()
            if active == '0':
                break
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                parser.error('Stop backend processes and close other connections to the target database '
                             f'before preparation (still {active} other connection(s) after waiting {wait_seconds}s)')
            print(f'Waiting for {active} other connection(s) on {database} to close; '
                  f'{int(remaining)}s left before failing.', flush=True)
            time.sleep(min(5.0, max(1.0, remaining)))
        command = [sys.executable, str(ROOT / 'deploy/scripts/backup-database.py'),
                   '--env-file', str(args.env_file.resolve()), '--version', args.backup_version,
                   '--description', f'{args.mode}: prepare {database} for portable team deployment']
        if args.mode == 'init':
            command.append('--allow-empty')
        subprocess.run(command, check=True, env=env)
        if args.mode == 'init':
            for name in ('schema.sql', 'seed.sql'):
                mysql(connection, env, database, (baseline / name).read_text(encoding='utf-8'))
        result = run(SOURCE, [args.mode, str(migrations), manifest['baselineVersion']])
        result.check_returncode()
    print('Database preparation completed. Start the backend normally; no local Flyway override required.')


if __name__ == '__main__':
    main()
