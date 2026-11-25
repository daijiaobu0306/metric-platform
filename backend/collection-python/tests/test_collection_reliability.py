"""Offline regressions: real parser subprocess/files, isolated API and source doubles."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


worker = load('collection_worker_test', ROOT / 'worker.py')
export = load('efficiency_export_test', ROOT / 'categories/running_efficiency/source_export.py')


class CollectionReliabilityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.inbox = self.root / 'inbox'
        self.archive = self.root / 'archive'
        self.inbox.mkdir()
        self.archive.mkdir()
        self.lease = Mock()
        self.claim = {'runId': 'run', 'config': {
            'code': 'running_efficiency', 'importPath': 'inbox', 'archivePath': 'archive',
            'format': 'csv', 'filePattern': '*.csv', 'settleSeconds': 0,
            'dateMode': 'data_time', 'worker': {'version': 'test-production-001',
            'categoryCode': 'system_run', 'timeoutSeconds': 30},
            'metricRules': [{'metricCode': 'COUNT', 'status': 1}]}, 'configHash': 'test'}

    def install_parser(self):
        release = self.root / 'collection-scripts/releases/test-production-001'
        release.mkdir(parents=True)
        source = ROOT / 'categories/running_efficiency'
        for name in ('reader.py', 'standard_reader.py'):
            shutil.copyfile(source / name, release / name)
        (release / 'config.json').write_text(json.dumps({'categoryCode': 'system_run',
            'datasets': {'test': [{'metricCode': 'COUNT', 'field': 'id', 'operation': 'distinct_count'}]}}))
        manifest = {'version': 'test-production-001', 'protocolVersion': 1, 'entry': 'reader.py',
                    'files': {p.name: worker.digest(p) for p in release.iterdir()}}
        (release / 'manifest.json').write_bytes(worker.encode(manifest))
        self.claim['manifestHash'] = worker.digest(release / 'manifest.json')

    def test_large_complete_dataset_parses_without_splitting_or_count_loss(self):
        self.install_parser()
        rows = [[str(i), 'x' * 600] for i in range(20000)]
        files = export.write_merged_csv(self.inbox, 'large', [('test', ['id', 'note'], rows, '2026-11-17T00:00:00')])
        self.assertEqual(1, len(files))
        self.assertGreater(files[0].stat().st_size, worker.MAX_FILE)
        payload = worker.parse(self.root, self.claim, files[0], self.lease)
        self.assertEqual('20000', payload['records'][0]['metric_value'])
        self.assertEqual(20000, payload['records'][0]['source_row_count'])
        self.assertLess(len(worker.encode(payload)), worker.MAX_FILE)
        self.assertEqual(worker.digest(files[0]), payload['fileHash'])

    def test_partial_acquisition_reports_failure_and_preserves_successful_files(self):
        config = self.root / 'sources.json'
        config.write_text(json.dumps({'tasks': [{'dataset': name, 'kind': 'bpm'} for name in ('good', 'bad')]}))
        connections = self.root / 'connections.json'
        connections.write_text('{"bpm":{}}')
        report = self.root / 'result.json'
        with patch.dict(os.environ, {'COLLECTION_ACQUISITION_RESULT': str(report)}), \
                patch.object(export, 'bpm_rows', side_effect=[(['id'], [['1']]), RuntimeError('private upstream error')]):
            with self.assertRaisesRegex(RuntimeError, 'Partial acquisition'):
                export.collect(config, connections, self.inbox)
        self.assertEqual(['bad'], json.loads(report.read_text())['failedDatasets'])
        self.assertNotIn('private upstream error', report.read_text())
        self.assertEqual(1, len(list(self.inbox.glob('*.csv'))))

    def test_oversize_dataset_is_rejected_before_publishing(self):
        with patch.object(export, 'MAX_INPUT_BYTES', 2048):
            with self.assertRaisesRegex(ValueError, 'complete-dataset'):
                export.write_merged_csv(self.inbox, 'oversize', [('test', ['id'], [['x' * 3000]], '2026-11-17T00:00:00')])
        self.assertEqual([], list(self.inbox.glob('*.csv')))

    def test_pending_recovery_and_existing_delivery_continue_during_acquisition_outage(self):
        self.install_parser()
        paths = export.write_merged_csv(self.inbox, 'ready', [('test', ['id'], [['1']], '2026-11-17T00:00:00')])
        state_root = self.root / 'collection-worker/running_efficiency'
        state_root.mkdir(parents=True)
        pending = state_root / 'pending-old.json'
        pending.write_text(json.dumps({'sourceFile': 'old.csv', 'fileHash': 'old', 'businessDate': '2026-11-16'}))
        events = []
        def acquire(*args):
            events.append('acquire')
            raise RuntimeError('source offline')
        def archive(api, claim, lease, inbox, archive_dir, state_path, state):
            events.append(state['sourceFile'])
            state_path.unlink()
        api = Mock()
        api.call.return_value = {'batchId': 'new'}
        with patch.dict(os.environ, {'COLLECTION_EXTERNAL_SOURCE_CODES': ''}), \
                patch.object(worker, 'acquire', side_effect=acquire), \
                patch.object(worker, 'status', side_effect=[{'state': 'COMMITTED'}, {}]), \
                patch.object(worker, 'archive_pending', side_effect=archive):
            error = worker.execute(api, self.root, self.claim, self.lease)
        self.assertEqual(['old.csv', 'acquire', paths[0].name], events)
        self.assertIn('source offline', error)
        payload = api.call.call_args.args[2]
        self.assertEqual('1', payload['records'][0]['metric_value'])

    def test_committed_pending_archive_is_idempotent(self):
        source = self.inbox / 'data.csv'
        source.write_text('original data')
        state = {'sourceFile': source.name, 'fileHash': worker.digest(source), 'businessDate': '2026-11-17'}
        pending = self.root / 'pending.json'
        pending.write_bytes(worker.encode(state))
        api = Mock()
        api.call.return_value = {'state': 'COMMITTED', 'archiveFile': 'stored.csv'}
        worker.archive_pending(api, self.claim, self.lease, self.inbox, self.archive, pending, state)
        self.assertFalse(source.exists())
        self.assertFalse(pending.exists())
        self.assertEqual(state['fileHash'], worker.digest(self.archive / 'stored.csv'))
        pending.write_bytes(worker.encode(state))
        worker.archive_pending(api, self.claim, self.lease, self.inbox, self.archive, pending, state)
        self.assertFalse(pending.exists())


if __name__ == '__main__':
    unittest.main()
