"""Create a versioned schema+data snapshot before database structure changes (standard library only)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone, timedelta
from urllib.parse import urlsplit


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True, help='V1, V2, ... (never overwritten)')
    parser.add_argument('--description', required=True)
    parser.add_argument('--env-file', type=Path, default=root / 'deploy/config/.env.local')
    parser.add_argument('--allow-empty', action='store_true', help='Permit a verified empty new database')
    args = parser.parse_args()
    if not re.fullmatch(r'V[1-9][0-9]*', args.version):
        parser.error('Version must be V1, V2, ...')
    record = root / 'doc/数据库版本记录.md'
    if record.exists() and re.search(r'^## ' + re.escape(args.version) + r'(?:[：:\s]|$)', record.read_text(encoding='utf-8'), re.MULTILINE):
        parser.error('Backup version already recorded; choose the next unused version')
    values = {}
    for line in args.env_file.read_text(encoding='utf-8-sig').splitlines():
        if re.match(r'^[A-Z][A-Z0-9_]*=', line):
            key, value = line.split('=', 1)
            values[key] = value
    url = values.get('SPRING_DATASOURCE_URL', '')
    if not url.startswith('jdbc:mysql://'):
        parser.error('Environment file must explicitly specify SPRING_DATASOURCE_URL')
    parsed = urlsplit(url.removeprefix('jdbc:'))
    database = parsed.path.lstrip('/')
    if not re.fullmatch(r'[A-Za-z0-9_]+', database):
        parser.error('Invalid database name')
    env = os.environ.copy()
    env['MYSQL_PWD'] = values['SPRING_DATASOURCE_PASSWORD']
    connection = ['--host=' + parsed.hostname, '--port=' + str(parsed.port or 3306),
                  '--user=' + values['SPRING_DATASOURCE_USERNAME'], '--default-character-set=utf8mb4']
    target = root / 'deploy/db_export' / args.version
    target.mkdir(parents=True, exist_ok=False)
    created = datetime.now(timezone(timedelta(hours=8)))
    stem = f'{database}_{args.version}_{created:%Y%m%d_%H%M%S}'
    dump = target / (stem + '.sql')
    partial = dump.with_suffix('.sql.partial')
    print(f'Backing up {parsed.hostname}:{parsed.port or 3306}/{database} as {args.version}', flush=True)
    # The output file is written by mysqldump, avoiding shell/PowerShell encoding conversion.
    # Ubuntu's default client can be MariaDB; only pass MySQL-specific options when supported.
    help_result = subprocess.run(['mysqldump', '--no-defaults', '--help'], env=env,
                                 capture_output=True, check=True)
    help_text = help_result.stdout.decode('utf-8', errors='replace')
    options = ['--' + name + '=OFF' for name in ('set-gtid-purged',) if name in help_text]
    if 'column-statistics' in help_text:
        options.append('--column-statistics=0')
    command = ['mysqldump', '--no-defaults', *connection, '--single-transaction', '--quick', '--routines', '--events',
               '--triggers', '--hex-blob', '--no-tablespaces', *options,
               '--result-file=' + str(partial), database]
    result = subprocess.run(command, env=env, capture_output=True)
    if result.returncode:
        raise RuntimeError('mysqldump failed; incomplete output retained, do not change schema: ' +
                           result.stderr.decode('utf-8', errors='replace'))
    contents = partial.read_bytes()
    if not contents or b'-- Dump completed on' not in contents[-4096:]:
        raise RuntimeError('Backup completion marker missing; do not change schema')
    listing = subprocess.run(['mysql', *connection, '--batch', '--skip-column-names', database,
                              '--execute=SELECT table_name FROM information_schema.tables '
                              'WHERE table_schema=DATABASE() ORDER BY table_name'],
                             env=env, capture_output=True)
    if listing.returncode:
        raise RuntimeError('Unable to verify table inventory; do not change schema')
    tables = listing.stdout.decode('utf-8', errors='strict').splitlines()
    dump_tables = contents.count(b'CREATE TABLE ')
    if dump_tables == 0 and not (args.allow_empty and not tables):
        raise RuntimeError('No CREATE TABLE statements in backup; do not change schema')
    partial.rename(dump)
    manifest = dict(version=args.version, createdAt=created.isoformat(), description=args.description,
                    host=parsed.hostname, port=parsed.port or 3306, database=database,
                    file=dump.name, bytes=dump.stat().st_size, sha256=hashlib.sha256(contents).hexdigest(),
                    tables=tables, createTableStatements=dump_tables, scope='schema_and_data',
                    restoreTested=False)
    (target / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    entry = (f'\n## {args.version}：{args.description}\n\n'
             f'- 时间：{created.isoformat()}\n'
             f'- 目标：{parsed.hostname}:{parsed.port or 3306}/{database}\n'
             f'- 文件：`deploy/db_export/{args.version}/{dump.name}`\n'
             f'- 范围：完整结构与数据；表数 {dump_tables}；大小 {manifest["bytes"]} 字节\n'
             f'- SHA-256：`{manifest["sha256"]}`\n'
             '- 验证：mysqldump 成功、完成标记及文件哈希已确认；未恢复演练。\n')
    with record.open('a', encoding='utf-8') as output:
        output.write(entry)
    print(json.dumps({k: manifest[k] for k in ('version', 'database', 'file', 'bytes', 'sha256', 'createTableStatements')}, ensure_ascii=False))
    print('Backup:', dump)


if __name__ == '__main__':
    main()
