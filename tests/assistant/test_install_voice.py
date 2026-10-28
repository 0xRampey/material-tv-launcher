#!/usr/bin/env python3
"""Exercise the real install -> key -> enable -> verify -> restore scripts with isolated ADB state."""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PKG = 'com.rampey.materialtv'
KEY_PATH = f'/sdcard/Android/data/{PKG}/files/openrouter_key'
BACKUP_PATH = f'/sdcard/Android/data/{PKG}/files/previous_assistant.json'
PREVIOUS = {'assistant': 'org.example.assistant/.Assistant',
            'voice_interaction_service': 'org.example.assistant/.Assistant',
            'voice_recognition_service': 'org.example.speech/.Recognizer'}


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.release = self.folder / 'release with spaces'
        self.release.mkdir()
        (self.release / 'scripts').mkdir()
        for name in ('install.sh', 'install-voice.sh', 'uninstall.sh', 'assistant.sh', 'scripts/assistant-settings.py'):
            shutil.copy(ROOT / name, self.release / name)
        (self.release / 'material-tv-launcher.apk').write_bytes(b'fixture-apk')
        self.key = self.folder / 'private key file'
        self.key.write_text('fixture-secret-not-a-real-key')
        self.state_file = self.folder / 'device.json'
        self.log_file = self.folder / 'adb.log'
        self.state_file.write_text(json.dumps({'settings': PREVIOUS, 'holders': ['org.example.assistant'], 'files': {}}))
        adb = self.folder / 'adb'
        shutil.copy(ROOT / 'tests/assistant/fake_adb.py', adb)
        adb.chmod(0o755)
        self.env = {**os.environ, 'PATH': str(self.folder) + os.pathsep + os.environ['PATH'],
                    'ADB_TEST_STATE': str(self.state_file), 'ADB_TEST_LOG': str(self.log_file)}

    def state(self):
        return json.loads(self.state_file.read_text())

    def run_script(self, script, *args, success=True):
        result = subprocess.run(['bash', str(self.release / script), 'fixture', *args], env=self.env,
                                text=True, capture_output=True, timeout=20)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('fixture-secret-not-a-real-key', result.stdout + result.stderr)
        if self.log_file.exists():
            self.assertNotIn('fixture-secret-not-a-real-key', self.log_file.read_text())
        return result

    def assert_previous(self):
        state = self.state()
        self.assertEqual(state['holders'], ['org.example.assistant'])
        for name, value in PREVIOUS.items():
            self.assertEqual(state['settings'][name], value)

    def test_install_only_discovers_release_apk_and_keeps_assistant(self):
        self.run_script('install-voice.sh')
        self.assertTrue(self.state()['installed'])
        self.assert_previous()
        self.assertNotIn(KEY_PATH, self.state()['files'])

    def test_key_without_enable_keeps_assistant(self):
        self.run_script('install-voice.sh', '--key-file', str(self.key))
        self.assert_previous()
        self.assertEqual(self.state()['files'][KEY_PATH], self.key.read_text())
        self.assertNotIn(BACKUP_PATH, self.state()['files'])

    def test_named_command_enables_and_repeated_update_then_restore_works(self):
        self.run_script('install-voice.sh', '--key-file', str(self.key), '--enable-gemini')
        self.assertEqual(self.state()['holders'], [PKG])
        backup = self.state()['files'][BACKUP_PATH]
        self.assertEqual(json.loads(backup)['settings'], PREVIOUS)
        status = json.loads(self.run_script('assistant.sh', 'status').stdout)
        self.assertTrue(status['gemini_selected'])
        self.assertTrue(status['key_present'])
        self.assertTrue(status['restore_available'])
        self.assertFalse(status['api_access_verified'])
        self.run_script('install-voice.sh', '--enable-gemini')
        self.assertEqual(self.state()['files'][BACKUP_PATH], backup)
        self.run_script('assistant.sh', 'restore')
        self.assert_previous()
        self.assertEqual(self.state()['files'][KEY_PATH], self.key.read_text())

    def test_missing_stored_key_cannot_take_over_assistant(self):
        self.run_script('install-voice.sh', '--enable-gemini', success=False)
        self.assert_previous()
        self.assertNotIn(BACKUP_PATH, self.state()['files'])

    def test_invalid_local_key_fails_before_install(self):
        for contents in ('', ' \n\t ', 'key-one\nkey-two-is-not-a-single-key'):
            with self.subTest(contents=contents):
                self.key.write_text(contents)
                self.run_script('install-voice.sh', '--key-file', str(self.key), '--enable-gemini', success=False)
                self.assertFalse(self.state().get('installed', False))
                self.assert_previous()
                self.assertFalse(self.log_file.exists())

    def test_bad_options_fail_before_install(self):
        for args in (('--key-file',), ('--apk', '--enable-gemini'), ('--unknown',),
                     ('--key-file', str(self.key), '--key-file', str(self.key)),
                     (str(self.key), '--apk', 'other.apk')):
            with self.subTest(args=args):
                self.run_script('install-voice.sh', *args, success=False)
                self.assertFalse(self.log_file.exists())

    def test_explicit_apk_path_and_legacy_arguments_work(self):
        custom = self.folder / 'custom launcher.apk'
        custom.write_bytes(b'fixture')
        (self.release / 'material-tv-launcher.apk').unlink()
        self.run_script('install-voice.sh', '--apk', str(custom))
        self.assert_previous()
        self.run_script('install-voice.sh', str(self.key), str(custom), '--enable-gemini')
        self.assertEqual(self.state()['holders'], [PKG])

    def test_failed_key_transfer_does_not_replace_existing_key_or_assistant(self):
        state = self.state()
        state['files'][KEY_PATH] = 'existing-key-kept-on-tv'
        state['fail_push'] = True
        self.state_file.write_text(json.dumps(state))
        self.run_script('install-voice.sh', '--key-file', str(self.key), '--enable-gemini', success=False)
        self.assert_previous()
        self.assertEqual(self.state()['files'][KEY_PATH], 'existing-key-kept-on-tv')

    def test_status_without_key_is_read_only(self):
        before = self.state()
        status = json.loads(self.run_script('assistant.sh', 'status').stdout)
        self.assertFalse(status['gemini_selected'])
        self.assertFalse(status['key_present'])
        self.assertFalse(status['restore_available'])
        self.assertEqual(self.state(), before)

    def test_upgrade_removes_only_obsolete_screen_service(self):
        state = self.state()
        state['settings']['enabled_accessibility_services'] = (
            f'org.example.reader/.Reader:{PKG}/{PKG}.AndroidUseService:{PKG}/{PKG}.HomeKeyService')
        self.state_file.write_text(json.dumps(state))
        self.run_script('install-voice.sh')
        self.assertEqual(self.state()['settings']['enabled_accessibility_services'],
                         f'org.example.reader/.Reader:{PKG}/{PKG}.HomeKeyService')
        self.assert_previous()

    def test_uninstall_restores_assistant_before_removing_backup(self):
        self.run_script('install-voice.sh', '--key-file', str(self.key), '--enable-gemini')
        self.run_script('uninstall.sh')
        self.assert_previous()
        self.assertFalse(self.state()['installed'])

    def test_uninstall_without_backup_keeps_app_and_gemini_intact(self):
        self.run_script('install-voice.sh', '--key-file', str(self.key), '--enable-gemini')
        state = self.state(); del state['files'][BACKUP_PATH]
        self.state_file.write_text(json.dumps(state))
        self.run_script('uninstall.sh', success=False)
        self.assertTrue(self.state()['installed'])
        self.assertEqual(self.state()['holders'], [PKG])


if __name__ == '__main__':
    unittest.main()
