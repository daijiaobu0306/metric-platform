"""Generate one file of three source-provided resource metrics. Does not calculate business metrics or write the database."""
import argparse
import csv
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[2]
    now = datetime.now(timezone(timedelta(hours=8)))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--business-date', default=now.date().isoformat())
    args = parser.parse_args()
    day = datetime.strptime(args.business_date, '%Y-%m-%d').date()
    if day > now.date():
        parser.error('Business date cannot be in the future')
    timestamp = now.replace(second=0, microsecond=0) if day == now.date() else datetime.combine(day, datetime.min.time()).replace(hour=18)
    inbox = root / 'files/raw_data/import/resource_input'
    inbox.mkdir(parents=True, exist_ok=True)
    target = inbox / f'{day}_{now:%H%M%S}_resource_input_values.csv'
    manifest_dir = root / 'files/raw_data/manifests/resource_input'
    manifest_dir.mkdir(parents=True, exist_ok=True)
    manifest_file = manifest_dir / (target.stem + '.json')
    if target.exists() or manifest_file.exists():
        raise ValueError('Batch already exists; no file was overwritten')
    # Three supplied observations, not outputs derived from demand-detail records.
    metrics = [('requirementCount', '需求数量', '24', '个'),
               ('avgRequirementDuration', '需求投入平均工时', '6.75', '小时'),
               ('totalRequirementDuration', '需求投入总工时', '162', '小时')]
    temporary = target.with_suffix('.csv.part')
    with temporary.open('x', encoding='utf-8-sig', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['指标编码', '指标名称', '指标值', '单位', '数据时间', '数据来源标记'])
        for code, name, value, unit in metrics:
            writer.writerow([code, name, value, unit, timestamp.strftime('%Y-%m-%d %H:%M:%S'), 'generated_acceptance'])
    manifest = dict(businessDate=str(day), generatedAt=now.isoformat(), provenance='generated_acceptance',
                    protocol='metric-values-v1', file=target.name,
                    sha256=hashlib.sha256(temporary.read_bytes()).hexdigest(),
                    suppliedValues={code: value for code, _, value, _ in metrics},
                    alarmThreshold=None, collectionFrequency='天')
    manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.rename(target)
    print(json.dumps(dict(file=str(target), manifest=str(manifest_file), suppliedValues=manifest['suppliedValues']), ensure_ascii=False))


if __name__ == '__main__':
    main()
