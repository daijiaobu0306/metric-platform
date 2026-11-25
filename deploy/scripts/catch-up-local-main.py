"""Legacy local repair helper. Prefer prepare-database.py for team installation/upgrades."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from urllib.parse import urlsplit
import zipfile


ROOT = Path(__file__).resolve().parents[2]
JAVA_SOURCE = r'''
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import java.util.*;
class LocalMainCatchUp {
  public static void main(String[] args) {
    String url = System.getenv("SPRING_DATASOURCE_URL");
    String user = System.getenv("SPRING_DATASOURCE_USERNAME");
    String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
    String location = "filesystem:" + args[0];
    var allowed = Set.of("20260916010000", "20260922000000", "20260922150000", "20261012190000", "20261014143845", "51");
    var check = Flyway.configure().dataSource(url, user, password).locations(location)
        .ignoreMigrationPatterns("*:future", "*:ignored", "*:missing").load();
    for (MigrationInfo info : check.info().all()) {
      if ((info.getState().name().equals("IGNORED") || info.getState().name().equals("PENDING")) &&
          !allowed.contains(info.getVersion().toString()))
        throw new IllegalStateException("Unexpected migration requires separate backup and review: " + info.getVersion());
    }
    if (args.length > 1 && args[1].equals("verify")) {
      for (MigrationInfo info : check.info().all()) {
        if (info.getState().name().equals("PENDING") ||
            (info.getState().name().equals("IGNORED") &&
             !info.getVersion().toString().equals("20261012190000") &&
             !info.getVersion().toString().equals("51")))
          throw new IllegalStateException("Migration requires backup and explicit review: " + info.getVersion());
      }
      check.validate();
      return;
    }
    // Run only the three executable historical migrations through normal Flyway.
    Flyway.configure().dataSource(url, user, password).locations(location)
        .outOfOrder(true).target("20260922150000").load().migrate();
    // The incompatible immutable BOMC script remains ignored, never recorded as success.
    // A new additive, idempotent migration implements its fields using MySQL syntax.
    check.migrate();
    for (MigrationInfo info : check.info().all()) {
      if (info.getState().name().equals("IGNORED") &&
          !info.getVersion().toString().equals("20261012190000") &&
          !info.getVersion().toString().equals("51"))
        throw new IllegalStateException("Unresolved historical migration: " + info.getVersion());
    }
    check.validate();
  }
}
'''


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backup-version')
    parser.add_argument('--verify-only', action='store_true', help='Read-only startup guard')
    args = parser.parse_args()
    if not args.verify_only and (not args.backup_version or not args.backup_version.startswith('V') or not args.backup_version[1:].isdigit()):
        parser.error('Invalid backup version')
    config = ROOT / 'deploy/config/.env.local'
    values = dict(line.split('=', 1) for line in config.read_text(encoding='utf-8-sig').splitlines()
                  if '=' in line and not line.startswith('#'))
    target = urlsplit(values['SPRING_DATASOURCE_URL'].removeprefix('jdbc:'))
    if target.hostname not in ('localhost', '127.0.0.1') or target.path not in ('/system_operations_metrics', '/system_operations_metrics_collection'):
        parser.error('This helper is restricted to the local platform development databases')
    if not args.verify_only:
        folder = ROOT / 'deploy/db_export' / args.backup_version
        manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
        dump = folder / manifest['file']
        if (manifest['database'] != target.path.lstrip('/') or manifest['host'] != target.hostname or
                manifest['port'] != (target.port or 3306) or manifest['scope'] != 'schema_and_data' or
                hashlib.sha256(dump.read_bytes()).hexdigest() != manifest['sha256']):
            parser.error('Backup target or checksum mismatch; no migrations executed')
    env = os.environ.copy()
    env.update(values)
    java = Path(values['JAVA_HOME']) / 'bin/java.exe'
    jar = ROOT / 'backend/target/metric-code.jar'
    with tempfile.TemporaryDirectory(prefix='metric-main-catchup-') as tmp:
        work = Path(tmp).resolve()
        if not work.is_relative_to(Path(tempfile.gettempdir()).resolve()):
            raise RuntimeError('Temporary directory outside system temp')
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (work / Path(name).name).write_bytes(archive.read(name))
        source = work / 'LocalMainCatchUp.java'
        source.write_text(JAVA_SOURCE, encoding='utf-8')
        subprocess.run([str(java), '--class-path', str(work / '*'), str(source),
                        str(ROOT / 'backend/src/main/resources/db/migration'),
                        'verify' if args.verify_only else 'migrate'],
                       env=env, check=True)
    if args.verify_only:
        print('Read-only local migration guard passed.')
        return
    print('Local catch-up completed. Historical BOMC migration remains explicitly ignored.')
    print('Use the shared guarded startup strategy; no local Flyway override is required.')


if __name__ == '__main__':
    main()
