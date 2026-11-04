"""Adversarial mirror checks. Synthetic fixtures only; no network or user's vault."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import knowledge_sync as sync

E1 = '11111111-1111-4111-8111-111111111111'
E2 = '22222222-2222-4222-8222-222222222222'
M1 = '33333333-3333-4333-8333-333333333333'


def fixture():
    entry = {'id': E1, 'title': '虚构缓存条件', 'markdown': '只用于虚构实验，尚待验证。',
             'domains': ['软件设计', '实验方法'], 'relatedEntryIds': [], 'revision': 1,
             'refs': [{'kind': 'message', 'id': M1, 'quote': '只用于虚构实验', 'role': 'assistant'}],
             'updatedAt': '2026-11-05T00:00:00Z'}
    return {'version': 1, 'entries': [entry], 'histories': [],
            'references': [{'kind': 'message', 'id': M1, 'role': 'assistant',
                            'title': '虚构讨论', 'quote': '只用于虚构实验'}],
            'domains': ['软件设计', '实验方法']}


class MirrorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.vault = self.root / 'vault'

    def tearDown(self):
        self.temporary.cleanup()

    def test_empty_vault_has_explanatory_dashboard(self):
        result = sync.sync_snapshot({'version': 1, 'entries': [], 'domains': []}, self.vault)
        self.assertEqual(0, result['entries'])
        self.assertIn('知识库还是空的', (self.vault / '知识总览.md').read_text())

    def test_links_domains_provenance_and_no_unnecessary_rewrite(self):
        data = fixture()
        sync.sync_snapshot(data, self.vault)
        files = {p: p.stat().st_mtime_ns for p in self.vault.rglob('*.md')}
        data['generatedAt'] = 'later'
        self.assertEqual(0, sync.sync_snapshot(data, self.vault)['changedFiles'])
        self.assertEqual(files, {p: p.stat().st_mtime_ns for p in files})
        note = (self.vault / sync.entry_path(E1)).read_text()
        self.assertIn('非事实证明', note)
        self.assertIn(sync.domain_path('软件设计')[:-3], note)

    def test_rename_revision_history_keep_same_identity(self):
        data = fixture()
        sync.sync_snapshot(data, self.vault)
        old = copy.deepcopy(data['entries'][0])
        data['entries'][0].update(title='修订后的虚构条件', revision=2)
        data['histories'] = [{'entryId': E1, 'versions': [old]}]
        sync.sync_snapshot(data, self.vault)
        self.assertTrue((self.vault / sync.entry_path(E1)).is_file())
        history = (self.vault / sync.history_path(E1, 1)).read_text()
        self.assertIn('虚构缓存条件', history)
        self.assertIn(sync.entry_path(E1)[:-3], history)

    def test_unmanaged_file_preserved_but_collision_refused(self):
        self.vault.mkdir()
        personal = self.vault / '我的手写.md'
        personal.write_text('私有文本')
        sync.sync_snapshot(fixture(), self.vault)
        self.assertEqual('私有文本', personal.read_text())
        colliding = self.root / 'collision'
        colliding.mkdir()
        (colliding / '知识总览.md').write_text('已有首页')
        with self.assertRaises(sync.SyncError):
            sync.sync_snapshot(fixture(), colliding)
        self.assertEqual('已有首页', (colliding / '知识总览.md').read_text())
        self.assertFalse((colliding / '知识').exists())

    def test_manual_change_blocks_all_overwrite_and_delete(self):
        data = fixture()
        sync.sync_snapshot(data, self.vault)
        path = self.vault / sync.entry_path(E1)
        path.write_text('手动修改')
        dashboard = (self.vault / '知识总览.md').read_bytes()
        with self.assertRaises(sync.SyncError):
            sync.sync_snapshot({'version': 1, 'entries': [], 'domains': []}, self.vault)
        self.assertEqual('手动修改', path.read_text())
        self.assertEqual(dashboard, (self.vault / '知识总览.md').read_bytes())

    def test_only_unchanged_managed_stale_files_are_removed(self):
        data = fixture()
        sync.sync_snapshot(data, self.vault)
        personal = self.vault / '我的手写.md'
        personal.write_text('保留')
        data['entries'][0]['domains'] = ['软件设计']
        data['domains'] = ['软件设计']
        sync.sync_snapshot(data, self.vault)
        self.assertFalse((self.vault / sync.domain_path('实验方法')).exists())
        self.assertEqual('保留', personal.read_text())

    def test_symlink_vault_ancestor_subfolder_and_file_refused(self):
        real = self.root / 'real'
        real.mkdir()
        alias = self.root / 'alias'
        alias.symlink_to(real, target_is_directory=True)
        for target in (alias, alias / 'nested'):
            with self.assertRaises(sync.SyncError):
                sync.sync_snapshot(fixture(), target)
        self.vault.mkdir()
        (self.vault / '知识').symlink_to(real, target_is_directory=True)
        with self.assertRaises(sync.SyncError):
            sync.sync_snapshot(fixture(), self.vault)
        (self.vault / '知识').unlink()
        (self.vault / '知识总览.md').symlink_to(real / 'outside.md')
        with self.assertRaises(sync.SyncError):
            sync.sync_snapshot(fixture(), self.vault)
        self.assertFalse((real / 'outside.md').exists())

    def test_malformed_record_ids_and_manifest_paths_cannot_escape(self):
        data = fixture()
        data['entries'][0]['id'] = '../../outside'
        with self.assertRaises(sync.SyncError):
            sync.sync_snapshot(data, self.vault)
        self.vault.mkdir(exist_ok=True)
        (self.vault / sync.MANIFEST).write_text(json.dumps(
            {'owner': sync.MARKER, 'files': {'../outside': 'x'}}))
        with self.assertRaises(sync.SyncError):
            sync.sync_snapshot(fixture(), self.vault)

    def test_domain_text_never_becomes_a_path(self):
        data = fixture()
        data['entries'][0]['domains'] = ['../../outside/危险']
        sync.sync_snapshot(data, self.vault)
        self.assertFalse((self.root / 'outside').exists())
        self.assertEqual(1, len(list((self.vault / '领域').glob('*.md'))))

    def test_remote_images_html_and_attachment_embeds_not_carried(self):
        data = fixture()
        data['entries'][0]['markdown'] = '![跟踪](https://example.invalid/x) <script>bad</script> ![[private.png]]'
        sync.sync_snapshot(data, self.vault)
        note = (self.vault / sync.entry_path(E1)).read_text()
        self.assertNotIn('https://example.invalid', note)
        self.assertNotIn('<script>', note)
        self.assertNotIn('![[', note)

    def test_generic_types_comparisons_and_code_survive_sanitizing(self):
        content = 'List<String> 与 a < b && b > c\n`List<String>`\n```java\nList<String> a;\n```'
        sanitized = sync.body(content)
        self.assertIn('List&lt;String>', sanitized)
        self.assertIn('a &lt; b && b > c', sanitized)
        self.assertIn('`List<String>`', sanitized)
        self.assertIn('```java\nList<String> a;\n```', sanitized)
        self.assertNotIn('![tracking]', sync.body('![tracking][img]\n[img]: https://example.invalid/a.png'))

    def test_file_changed_after_preflight_is_not_overwritten_or_deleted(self):
        data = fixture()
        sync.sync_snapshot(data, self.vault)
        original = sync.atomic_write
        path = self.vault / sync.entry_path(E1)

        def mutate_after_preflight(target, content):
            result = original(target, content)
            if target.name == sync.JOURNAL:
                path.write_text('同步期间的手写内容')
            return result

        with patch.object(sync, 'atomic_write', side_effect=mutate_after_preflight):
            with self.assertRaises(sync.SyncError):
                sync.sync_snapshot({'version': 1, 'entries': [], 'domains': []}, self.vault)
        self.assertEqual('同步期间的手写内容', path.read_text())
        with self.assertRaises(sync.SyncError):
            sync.sync_snapshot(data, self.vault)
        self.assertEqual('同步期间的手写内容', path.read_text())

    def test_project_stop_continues_when_mirror_stop_is_pending(self):
        import manage
        with patch('sys.argv', ['manage.py', 'stop']), patch.object(sync, 'stop',
                side_effect=sync.SyncError('synthetic timeout')), patch.object(manage, 'compose') as compose:
            manage.main()
        compose.assert_called_once_with('--profile', 'app', 'stop')

    def test_redirect_and_nonlocal_auth_destinations_refused(self):
        for address in ['https://127.0.0.1/api/v1', 'http://example.com/api/v1',
                        'http://user:pass@127.0.0.1/api/v1', 'http://127.0.0.1/?secret=1']:
            with self.assertRaises(sync.SyncError):
                sync.Client(address, 'synthetic')
        with self.assertRaises(sync.SyncError):
            sync.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://example.invalid')

    def test_interrupted_first_write_recovers_every_atomic_boundary(self):
        original = sync.atomic_write
        # A full fixture needs eight atomic writes; exercise before/after manifest and files.
        for failure_at in range(1, 10):
            vault = self.root / f'crash-{failure_at}'
            count = 0

            def interrupt(path, content):
                nonlocal count
                count += 1
                if count == failure_at:
                    raise OSError('synthetic disk interruption')
                return original(path, content)

            with patch.object(sync, 'atomic_write', side_effect=interrupt):
                try:
                    sync.sync_snapshot(fixture(), vault)
                except OSError:
                    pass
            sync.sync_snapshot(fixture(), vault)
            self.assertEqual(0, sync.sync_snapshot(fixture(), vault)['changedFiles'])
            self.assertFalse((vault / sync.JOURNAL).exists())

    def test_interrupted_revision_with_deletion_can_recover(self):
        data = fixture()
        sync.sync_snapshot(data, self.vault)
        old = copy.deepcopy(data['entries'][0])
        data['entries'][0].update(title='新版', domains=['实验方法'], revision=2)
        data['domains'] = ['实验方法']
        data['histories'] = [{'entryId': E1, 'versions': [old]}]
        original = sync.atomic_write
        count = 0

        def interrupt(path, content):
            nonlocal count
            count += 1
            if count == 5:
                raise OSError('synthetic')
            return original(path, content)

        with patch.object(sync, 'atomic_write', side_effect=interrupt):
            with self.assertRaises(OSError):
                sync.sync_snapshot(data, self.vault)
        sync.sync_snapshot(data, self.vault)
        self.assertIn('新版', (self.vault / sync.entry_path(E1)).read_text())


if __name__ == '__main__':
    unittest.main()
