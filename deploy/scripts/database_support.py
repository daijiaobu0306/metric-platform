"""Shared connection and Java runtime support for database preparation tools."""
from contextlib import contextmanager
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def environment(path):
    values = dict(line.split('=', 1) for line in Path(path).read_text(encoding='utf-8-sig').splitlines()
                  if re.match(r'^[A-Z][A-Z0-9_]*=', line))
    url = values.get('SPRING_DATASOURCE_URL', '')
    if not url.startswith('jdbc:mysql://'):
        raise ValueError('Explicit jdbc:mysql datasource URL required')
    target = urlsplit(url.removeprefix('jdbc:'))
    if not target.hostname or not re.fullmatch(r'[A-Za-z0-9_]+', target.path.lstrip('/')):
        raise ValueError('Invalid MySQL host or database name')
    env = os.environ.copy()
    env.update(values)
    env['MYSQL_PWD'] = values['SPRING_DATASOURCE_PASSWORD']
    connection = ['--host=' + target.hostname, '--port=' + str(target.port or 3306),
                  '--user=' + values['SPRING_DATASOURCE_USERNAME'], '--default-character-set=utf8mb4']
    return values, target, env, connection


def mysql(connection, env, database=None, sql=None):
    command = ['mysql', *connection, '--batch', '--raw', '--skip-column-names']
    if database:
        command.append(database)
    result = subprocess.run(command, input=sql.encode('utf-8') if sql else None,
                            env=env, capture_output=True, check=True)
    return result.stdout.decode('utf-8')


@contextmanager
def java_runtime(values, env):
    java = Path(values['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if values.get('JAVA_HOME') else shutil.which('java')
    if not java or not Path(java).is_file():
        raise ValueError('JDK 17+ required; set JAVA_HOME or PATH')
    jar = ROOT / 'backend/target/metric-code.jar'
    if not jar.is_file():
        raise ValueError('Build backend first: mvn -f backend/pom.xml -DskipTests package')
    with tempfile.TemporaryDirectory(prefix='metric-db-tool-') as tmp:
        work = Path(tmp).resolve()
        if not work.is_relative_to(Path(tempfile.gettempdir()).resolve()):
            raise RuntimeError('Temporary path outside system temp')
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (work / Path(name).name).write_bytes(archive.read(name))
        def run(source, arguments):
            classes = ROOT / 'deploy/database/classes'
            if (classes / 'DatabasePreparation.class').is_file():
                classpath = str(work / '*') + os.pathsep + str(classes)
                target = 'DatabasePreparation'
            else:
                file = work / 'DatabasePreparation.java'
                file.write_text(source, encoding='utf-8')
                classpath, target = str(work / '*'), str(file)
            return subprocess.run([str(java), '-Dfile.encoding=UTF-8', '--class-path', classpath, target, *arguments],
                                  env=env, cwd=ROOT)
        yield work, run
