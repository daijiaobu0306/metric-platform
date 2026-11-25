import importlib.util
import json
from pathlib import Path
from datetime import datetime, timezone, timedelta
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / 'categories/internal_staff'
spec = importlib.util.spec_from_file_location('daily_export', SOURCE / 'source_export.py')
export = importlib.util.module_from_spec(spec)
spec.loader.exec_module(export)


class DailyExportTest(unittest.TestCase):
    def test_bpm_window_is_previous_beijing_calendar_day(self):
        task = next(t for t in json.loads((SOURCE / 'sources.json').read_text(encoding='utf-8'))['tasks'] if t['kind'] == 'bpm')
        now = datetime(2026, 3, 1, 0, 10, tzinfo=timezone(timedelta(hours=8)))
        payloads = []
        def post(url, body, headers):
            if url.endswith('/token'):
                return {'access_token': 'test-only'}
            payloads.append(json.loads(body))
            return {'dataList': {'records': [], 'total': 0}}
        config = dict(client_id='test',client_secret='test',username='test',password='test',token_url='http://test/token',api_url='http://test/data')
        with patch.object(export, 'post', side_effect=post):
            _, rows = export.bpm_rows(task, config, now)
        self.assertEqual([], rows)
        expected = now.replace(hour=0, minute=0)
        self.assertEqual(int((expected-timedelta(days=1)).timestamp()*1000), payloads[0]['startTime'])
        self.assertEqual(int(expected.timestamp()*1000), payloads[0]['endTime'])

    def test_every_sql_uses_same_explicit_closed_open_window(self):
        tasks = json.loads((SOURCE / 'sources.json').read_text(encoding='utf-8'))['tasks']
        queries = [t['sql'] for t in tasks if t['kind'] == 'jdbc']
        self.assertEqual(6, len(queries))
        for sql in queries:
            self.assertIn("create_time >= '__WINDOW_START__'", sql)
            self.assertIn("create_time < '__WINDOW_END__'", sql)
            self.assertNotIn('NOW()', sql.upper())


if __name__ == '__main__':
    unittest.main()
