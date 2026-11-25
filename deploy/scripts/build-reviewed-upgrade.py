"""Package existing validated builds without copying environment or runtime data."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import tarfile
import zipfile
from datetime import datetime, timezone, timedelta

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--resource-input-dir', type=Path, required=True,
                    help='Directory containing the two original BOSS/BASS delivery files')
args = parser.parse_args()
resource_files = [args.resource_input_dir / f'{system}资源投入指标_入库数据.csv' for system in ('BOSS', 'BASS')]
for source in resource_files:
    if not source.is_file() or source.stat().st_size == 0:
        parser.error(f'Missing or empty resource input file: {source}')
out = root / 'deploy/out'
name = 'metric-code-upgrade-' + datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d%H%M%S')
stage = root / 'deploy/build' / name
stage.mkdir(parents=True, exist_ok=False)
(stage / 'backend').mkdir()
(stage / 'bin').mkdir()
shutil.copy2(root / 'backend/target/metric-code.jar', stage / 'backend/metric-code.jar')
shutil.copytree(root / 'frontend/dist', stage / 'frontend')
shutil.copytree(root / 'backend/collection-python', stage / 'worker',
                ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
for filename in ('apply-upgrade.sh', 'standalone-lib.sh', 'standalone-backup-database.sh'):
    text = (root / 'deploy/scripts' / filename).read_text(encoding='utf-8')
    (stage / 'bin' / filename).write_text(text, encoding='utf-8', newline='\n')
catalog = root / 'backend/src/main/resources/collection-scripts'
for version in json.loads((catalog / 'catalog.json').read_text(encoding='utf-8')):
    source = catalog / 'releases' / version
    if not source.exists():
        source = root / 'files/collection-scripts/releases' / version
    manifest = json.loads((source / 'manifest.json').read_text(encoding='utf-8'))
    for filename, digest in manifest['files'].items():
        assert hashlib.sha256((source / filename).read_bytes()).hexdigest() == digest, (version, filename)
    shutil.copytree(source, stage / 'collection-scripts/releases' / version)
shutil.copytree(out / 'mock-update-20261117', stage / 'mock-update')
(stage / 'mock-update' / '百分比更新说明.md').write_text(
    '# 运行效率模拟百分比\n\n'
    '若现场已应用上一版指标配置，停止独立 Mock 服务后，只替换现场 mock_generator.py，再启动服务。'
    '保留现场 config/mock.json 和 state/。新文件使用两位小数，历史文件保持原值。\n',
    encoding='utf-8')
(stage / 'docs').mkdir()
delivery = stage / 'manual-input' / 'resource_input'
delivery.mkdir(parents=True)
for source in resource_files:
    shutil.copy2(source, delivery / source.name)
(stage / 'manual-input' / 'README.md').write_text(
    '# 资源投入手工投递\n\n'
    '平台升级成功后，核对现有 resource_input 任务的实际扫描目录。'
    '将 resource_input/ 下两份 CSV 按原名和原内容投递。'
    '传输中使用 .part，传完再改回 .csv。已有同名文件时先核对内容。\n\n'
    '两份文件随包交付，升级脚本不会自动投递。后续收到对端文件时仍按同一方式手工投递。'
    '业务日期使用文件原始日期。验收历史日期时请在界面选择文件覆盖范围。\n',
    encoding='utf-8')
(stage / '本版补充说明.md').write_text('''# 本版补充说明

本版包括采集可靠性修复、两个运行效率指标停用迁移、数据汇聚指标方形单选控件，以及移除数据汇聚页面的日期、时间和资源投入说明提示。历史记录保留。

对内部人员新增 internal_staff-production-20261118-001，兼容融合业务全程办理时长数据集；迁移仅将已绑定 internal_staff-production-20261117-003 的既有任务更新至新版本，自定义绑定保持不变。83 取数脚本可使用原名称，保留原始记录。

独立 Mock 更新额外合并数据质量六项；原有数据质量配置按编码保留并启用，已有投递目录不覆盖。必须在67先停止生成器，按升级说明运行 mock-update/apply.py，再恢复服务。更新脚本备份原配置和程序，保留 state。

检查 dataQuality 的投递目录与生产 data_quality 采集任务扫描目录一致；没有 delivery 配置时，生成器使用 data_root/inbox/data_quality，须由现场核对是否与平台扫描目录一致。任务须启用，且 Worker 正常运行。新数据按既定每小时调度生成，保留模拟来源标记；不会补造停机期间的数据。

数据质量启用迁移仅在既有任务停用且版本匹配时生效；若现场配置不同，需在采集任务页面检查，不自动覆盖现场配置。

前端以 esnext 目标、无压缩构建，建议使用现代浏览器。
''', encoding='utf-8')
(stage / 'BUILD_INFO.txt').write_text(f'BUILD_ID: {name}\n', encoding='utf-8')
shutil.copy2(root / 'doc/数据采集改造1008/58-数据汇聚与T减一升级.md', stage / '本次升级说明.md')
shutil.copy2(root / 'doc/数据采集改造1008/56-最新梳理表模拟指标核对.md', stage / 'docs/指标核对.md')
shutil.copy2(root / 'doc/数据采集改造1008/57-生产升级操作说明.md', stage / '升级说明.md')
shutil.copy2(root / 'doc/数据采集改造1008/60-采集可靠性修复与升级验证.md', stage / 'docs/采集可靠性修复与升级验证.md')
shutil.copy2(out / 'outputs/20261117/运营指标体系梳理-8个维度-补充编码.xlsx', stage / 'docs/运营指标体系梳理-8个维度-补充编码.xlsx')
with zipfile.ZipFile(stage / 'backend/metric-code.jar') as jar:
    assert any('V20261117173846__resource_input_daily_boss_bass.sql' in entry for entry in jar.namelist())
    assert any('V20261117233519__bind_collection_reliability_releases.sql' in entry for entry in jar.namelist())
    assert any('V20261118105026__retire_unwanted_efficiency_metrics.sql' in entry for entry in jar.namelist())
    assert any('V20261118113132__bind_internal_staff_fusion_dataset.sql' in entry for entry in jar.namelist())
    for version in ('running_efficiency-production-20261117-001', 'internal_staff-production-20261117-003', 'internal_staff-production-20261118-001'):
        release = stage / 'collection-scripts/releases' / version
        prefix = 'BOOT-INF/classes/collection-scripts/releases/' + version + '/'
        for source in release.rglob('*'):
            if source.is_file():
                assert jar.read(prefix + source.relative_to(release).as_posix()) == source.read_bytes(), (version, source.name)
assert (stage / 'frontend/index.html').is_file()
manifest = {str(p.relative_to(stage)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in stage.rglob('*') if p.is_file()}
(stage / 'BUILD_MANIFEST.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
archive = out / (name + '.tar.gz')
with tarfile.open(archive, 'w:gz') as tar:
    tar.add(stage, arcname=name)
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
archive.with_name(archive.name + '.sha256').write_text(digest + '  ' + archive.name + '\n', encoding='ascii')
print(archive)
print(digest)
