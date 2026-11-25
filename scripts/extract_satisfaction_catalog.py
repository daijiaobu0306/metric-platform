"""Extract the satisfaction catalog without modifying the source workbook.

Uses OOXML directly because the supplied workbook has nonstandard fill styles.
"""
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('--final', action='store_true', help='Validate and extract the final 17-metric catalog')
args = parser.parse_args()
with zipfile.ZipFile(args.source) as archive:
    strings = [''.join(node.itertext()) for node in ET.fromstring(
        archive.read('xl/sharedStrings.xml')).findall('m:si', NS)]
    workbook = ET.fromstring(archive.read('xl/workbook.xml'))
    sheet = next(s for s in workbook.find('m:sheets', NS)
                 if s.get('name') == '3、满意度及投诉服务体验')
    relationship = sheet.get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id')
    relationships = ET.fromstring(archive.read('xl/_rels/workbook.xml.rels'))
    target = next(r.get('Target') for r in relationships if r.get('Id') == relationship)
    target = target.lstrip('/') if target.startswith('/') else 'xl/' + target
    rows = ET.fromstring(archive.read(target)).findall('m:sheetData/m:row', NS)
    catalog = []
    for row in rows:
        index = int(row.get('r'))
        if index < 3:
            continue
        cells = {}
        for cell in row.findall('m:c', NS):
            value = cell.find('m:v', NS)
            text = value.text or '' if value is not None else ''
            if cell.get('t') == 's' and text:
                text = strings[int(text)]
            elif cell.get('t') == 'inlineStr':
                text = ''.join(cell.find('m:is', NS).itertext())
            cells[re.sub(r'\d', '', cell.get('r'))] = text
        name = cells.get('C', '').strip()
        if not name:
            continue
        unit = '%' if name.endswith('率') else '小时' if '时长' in name else '个'
        prefix = 'SAT_FINAL_R' if args.final else 'SAT_SIM_R'
        catalog.append(dict(code=f'{prefix}{index:03}', sourceRow=index,
                            groupName=cells.get('B', ''), name=name,
                            sourceScope=cells.get('H', ''), definition=cells.get('F', ''),
                            statisticalRule=cells.get('G', ''), frequency=cells.get('J', ''),
                            thresholdNote=cells.get('L', ''), officialCode=cells.get('D', ''),
                            unit=unit))
if args.final:
    expected = {'客服投诉工单处理流程': 6, '倒三角工单处理流程': 6, '综调工单处理流程': 5}
    counts = {group: sum(m['groupName'] == group for m in catalog) for group in expected}
    if len(catalog) != 17 or counts != expected:
        raise ValueError(f'Final catalog must contain 17 metrics (6/6/5); got {counts}')
result = dict(sourceFile=args.source.name, sheet=sheet.get('name'),
              sourceSha256=hashlib.sha256(args.source.read_bytes()).hexdigest(),
              protocol='satisfaction-final-v1' if args.final else 'satisfaction-sim-v1', metrics=catalog)
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'Extracted {len(catalog)} rows; source unchanged.')
