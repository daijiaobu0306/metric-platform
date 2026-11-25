"""Prepare an existing database before starting the container's web application."""
import fcntl
import hashlib
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile

TOOL_ROOT = Path('/opt/metric-db')


def link(target, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_symlink():
        if destination.resolve() != target.resolve():
            raise RuntimeError('Unexpected database tool link: ' + str(destination))
    elif destination.exists():
        raise RuntimeError('Refusing to replace database tool path: ' + str(destination))
    else:
        destination.symlink_to(target, target_is_directory=target.is_dir())


def prepare_database():
    expected = os.environ.get('METRIC_DATABASE_EXPECTED_NAME', '')
    if not re.fullmatch(r'[A-Za-z0-9_]+', expected):
        raise RuntimeError('Configure METRIC_DATABASE_EXPECTED_NAME before automatic upgrades')
    state = Path('/app/files/database-upgrade')
    state.mkdir(parents=True, exist_ok=True)
    with (state / 'upgrade.lock').open('a') as lock:
        # Fail rather than run two migration/backup processes against one mounted workspace.
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        exports = state / 'db_export'
        exports.mkdir(exist_ok=True)
        link(TOOL_ROOT / 'bootstrap', exports / 'bootstrap')
        link(exports, TOOL_ROOT / 'deploy/db_export')
        ledger = state / 'doc/数据库版本记录.md'
        ledger.parent.mkdir(exist_ok=True)
        seed = (TOOL_ROOT / 'backup-ledger.md').read_text(encoding='utf-8')
        if not ledger.exists():
            ledger.write_text(seed, encoding='utf-8')
        else:
            current = ledger.read_text(encoding='utf-8')
            # Carry newly shipped backup versions forward without discarding this server's records.
            for section in re.split(r'(?=^## V[0-9]+[：:\s])', seed, flags=re.MULTILINE)[1:]:
                if section.strip() not in current:
                    with ledger.open('a', encoding='utf-8') as output:
                        output.write('\n' + section)
                    current += '\n' + section
        link(ledger, TOOL_ROOT / 'doc/数据库版本记录.md')
        keys = ('SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME',
                'SPRING_DATASOURCE_PASSWORD', 'SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS')
        values = {key: os.environ[key] for key in keys if key in os.environ}
        for key in keys[:3]:
            if not values.get(key):
                raise RuntimeError('Missing database configuration: ' + key)
        if any('\n' in value or '\r' in value for value in values.values()):
            raise RuntimeError('Database configuration must not contain line breaks')
        release = hashlib.sha256(Path('/app/app.jar').read_bytes()).hexdigest()
        target = hashlib.sha256(values['SPRING_DATASOURCE_URL'].encode()).hexdigest()[:16]
        failure = state / ('failed-' + release + '-' + target)
        if failure.exists():
            raise RuntimeError('Previous upgrade failed; inspect its logs/backup before retrying. '
                               'After correcting the cause, remove ' + str(failure))
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', suffix='.env', delete=False) as config:
            for key, value in values.items():
                config.write(key + '=' + value + '\n')
            config_path = Path(config.name)
        try:
            result = subprocess.run([
                sys.executable, str(TOOL_ROOT / 'deploy/scripts/prepare-database.py'), 'upgrade',
                '--env-file', str(config_path), '--expected-database', expected,
                '--backup-version', 'auto', '--skip-if-current'
            ], cwd=TOOL_ROOT)
            if result.returncode:
                failure.write_text('Upgrade failed; backend was not started. Inspect container logs.\n', encoding='utf-8')
                raise RuntimeError('Database preparation failed; automatic retries blocked: ' + str(failure))
        finally:
            config_path.unlink(missing_ok=True)


def main():
    original_umask = os.umask(0o077)
    try:
        if os.environ.get('METRIC_DATABASE_AUTO_UPGRADE', 'false').lower() == 'true':
            prepare_database()
    finally:
        os.umask(original_umask)
    os.execvp('java', ['java', *shlex.split(os.environ.get('JAVA_OPTS', '')),
                       '-jar', '/app/app.jar'])


if __name__ == '__main__':
    main()
