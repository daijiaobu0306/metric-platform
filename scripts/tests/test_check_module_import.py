"""Exercise the real CLI against isolated Git repositories; never change the project index."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CHECKER = Path(__file__).resolve().parents[1] / 'check_module_import.py'
SOURCE = 'backend/src/main/java/com/metric/'


class ModuleImportCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Test')

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.root, check=True, capture_output=True)

    def write(self, imports='', module='alarm', name='Example', prefix=SOURCE):
        path = self.root / prefix / module / 'service' / f'{name}.java'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'package com.metric.{module}.service;\n{imports}\nclass {name} {{}}\n')
        return path

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'baseline')

    def check(self, *args):
        return subprocess.run([sys.executable, '-X', 'utf8', str(CHECKER), *args], cwd=self.root, capture_output=True, text=True, encoding='utf-8')

    def test_services_entities_dtos_allowed_repository_only_warns(self):
        self.write('''import com.metric.metric.service.MetricService;
import com.metric.metric.entity.Metric;
import com.metric.metric.dto.MetricDTO;
import com.metric.metric.repository.MetricRepository;''')
        result = self.check('--all')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('0 个错误，1 个提醒', result.stdout)

    def test_static_nested_and_wildcard_controller_imports_blocked(self):
        self.write('''import static com.metric.issue.controller.admin.IssueController.FLAG;
import com.metric.metric.controller.*;
import com.metric.alarm.controller.OwnController;''')
        result = self.check()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn('2 个错误', result.stdout)

    def test_shared_reverse_dependency_and_comment_false_positives(self):
        self.write('''/* import com.metric.issue.controller.FalsePositive; */
// import com.metric.metric.controller.AnotherFalsePositive;
String s = "import com.metric.metric.controller.StringLiteral;";
import com.metric.collect.service.NotificationEventService;''', module='shared')
        result = self.check('--all')
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn('1 个错误', result.stdout)
        self.assertNotIn('FalsePositive', result.stdout)

    def test_existing_dependencies_suppressed_but_new_import_detected(self):
        self.write('import com.metric.metric.repository.MetricRepository;')
        self.commit()
        self.write('''import com.metric.metric.repository.MetricRepository;
import com.metric.issue.repository.IssueRepository;''')
        result = self.check('--changed')
        self.assertEqual(result.returncode, 0)
        self.assertIn('0 个错误，1 个提醒', result.stdout)
        self.assertIn('略过 1 处', result.stdout)

    def test_staged_reads_index_not_working_tree(self):
        self.write()
        self.commit()
        self.write('import com.metric.issue.controller.IssueController;')
        self.git('add', '.')
        self.write()  # Fix exists only in worktree, staged version must still fail.
        self.assertEqual(self.check('--staged').returncode, 1)
        self.assertEqual(self.check('--changed').returncode, 0)

    def test_untracked_included_but_not_in_staged(self):
        self.write()
        self.commit()
        self.write('import com.metric.issue.controller.IssueController;', name='New')
        self.assertEqual(self.check().returncode, 1)
        self.assertEqual(self.check('--staged').returncode, 0)

    def test_layout_move_preserves_baseline_but_new_problem_detected(self):
        old = self.write('import com.metric.metric.repository.MetricRepository;', prefix='src/main/java/com/metric/')
        self.commit()
        old.unlink()
        self.write('''import com.metric.metric.repository.MetricRepository;
import com.metric.issue.controller.IssueController;''')
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn('1 个错误，0 个提醒', result.stdout)
        self.assertIn('略过 1 处', result.stdout)

    def test_package_change_reevaluates_dependencies(self):
        self.write('import com.metric.alarm.controller.OwnController;')
        self.commit()
        path = self.root / SOURCE / 'alarm/service/Example.java'
        path.write_text('package com.metric.issue.service;\nimport com.metric.alarm.controller.OwnController;')
        self.assertEqual(self.check().returncode, 1)

    def test_unstaged_error_does_not_block_clean_index(self):
        self.write()
        self.commit()
        self.write('import com.metric.issue.controller.IssueController;')
        self.assertEqual(self.check('--staged').returncode, 0)
        self.assertEqual(self.check('--changed').returncode, 1)

    def test_staged_rename_keeps_existing_dependency_baseline(self):
        old = self.write('import com.metric.metric.repository.MetricRepository;')
        self.commit()
        new = old.with_name('Renamed.java')
        self.git('mv', str(old.relative_to(self.root)), str(new.relative_to(self.root)))
        result = self.check('--staged')
        self.assertEqual(result.returncode, 0)
        self.assertIn('0 个错误，0 个提醒', result.stdout)
        self.assertIn('略过 1 处', result.stdout)

    def test_existing_error_visible_only_in_full_audit(self):
        path = self.write('import com.metric.metric.service.MetricService;', module='shared')
        self.commit()
        self.assertEqual(self.check().returncode, 0)
        self.assertEqual(self.check('--all').returncode, 1)
        path.unlink()
        self.assertEqual(self.check().returncode, 0)


if __name__ == '__main__':
    unittest.main()
