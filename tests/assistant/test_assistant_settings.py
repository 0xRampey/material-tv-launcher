#!/usr/bin/env python3
"""Assistant ownership, restoration, and failure recovery without touching a TV."""
import contextlib
import copy
import importlib.util
import io
import os
import tempfile
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("assistant_settings", ROOT / "scripts/assistant-settings.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
OLD = {"version": 1, "user": 0, "holders": ["org.example.assistant"], "settings": {
    "assistant": "org.example.assistant/.Assist", "voice_interaction_service": "org.example.assistant/.Assist",
    "voice_recognition_service": "org.example.speech/.Recognizer"}}


class TV(module.Assistant):
    def __init__(self):
        self.user = "0"
        self.state = copy.deepcopy(OLD)
        self.saved = None
        self.key = True
        self.calls = []
        self.fail_once = None

    def read(self):
        return copy.deepcopy(self.state)

    def holders(self):
        return list(self.state["holders"])

    def save(self, state):
        self.saved = copy.deepcopy(self.validate(state))

    def load(self):
        if self.saved is None:
            raise RuntimeError("No saved assistant")
        return copy.deepcopy(self.validate(self.saved))

    def shell(self, *args):
        self.calls.append(args)
        if args[:2] == ("sh", "-c"):
            if not self.key:
                raise subprocess.CalledProcessError(1, "key check")
        elif args[:2] == ("cmd", "role"):
            package = args[-2]
            if args[2] == "remove-role-holder": self.state["holders"].remove(package)
            else: self.state["holders"].append(package)
            # Android role policy may rewrite all assistant settings during each role change.
            self.state["settings"] = {field: "role.policy/.Default" for field in module.FIELDS}
        elif args[0] == "settings":
            verb, field = args[3], args[5]
            if self.fail_once == field:
                self.fail_once = None
                raise RuntimeError("Device write failed")
            self.state["settings"][field] = None if verb == "delete" else args[6]
        return ""


class AssistantSettingsTest(unittest.TestCase):
    def quiet(self, method):
        with contextlib.redirect_stdout(io.StringIO()): method()

    def test_default_install_never_changes_assistant_even_with_key(self):
        for provision_key in (False, True):
            with self.subTest(provision_key=provision_key), tempfile.TemporaryDirectory() as folder:
                folder = Path(folder)
                log = folder / "adb.log"
                adb = folder / "adb"
                adb.write_text("""#!/usr/bin/env python3
import json, os, sys
with open(os.environ['ADB_TEST_LOG'], 'a') as stream: stream.write(json.dumps(sys.argv[1:]) + '\\n')
a = sys.argv[1:]
if a[0] == 'connect': print('connected to test:5555')
elif 'enabled_accessibility_services' in a: print('null')
elif 'activities' in a: print('ResumedActivity com.rampey.materialtv/.HomeActivity')
else: print('Success')
""")
                adb.chmod(0o755)
                apk = folder / 'launcher.apk'; apk.write_bytes(b'fixture')
                key = folder / 'key'; key.write_text('not-a-real-api-key')
                args = ['bash', str(ROOT / 'install-voice.sh'), 'test', str(key) if provision_key else '', str(apk)]
                result = subprocess.run(args, text=True, capture_output=True,
                        env={**os.environ, 'PATH': str(folder) + os.pathsep + os.environ['PATH'], 'ADB_TEST_LOG': str(log)})
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                commands = log.read_text()
                self.assertNotIn('add-role-holder', commands)
                self.assertNotIn('voice_interaction_service', commands)
                self.assertNotIn('RECORD_AUDIO', commands)
                self.assertIn('Assistant selection unchanged', result.stdout)

    def test_round_trip_restores_role_and_distinct_recognizer(self):
        tv = TV()
        self.quiet(tv.enable)
        self.assertEqual(tv.saved, OLD)
        self.assertEqual(tv.holders(), [module.PKG])
        self.quiet(tv.restore)
        self.assertEqual(tv.state, OLD)

    def test_missing_or_blank_key_changes_nothing(self):
        tv = TV(); tv.key = False
        with self.assertRaisesRegex(RuntimeError, "No usable key file"): tv.enable()
        self.assertEqual(tv.state, OLD)
        self.assertIsNone(tv.saved)
        self.assertFalse(any(call[0] in ("pm", "cmd", "settings") for call in tv.calls))

    def test_repeat_enable_does_not_overwrite_backup(self):
        tv = TV()
        self.quiet(tv.enable); self.quiet(tv.enable)
        self.assertEqual(tv.saved, OLD)
        self.quiet(tv.restore)
        self.assertEqual(tv.state, OLD)

    def test_empty_and_absent_settings_are_distinct(self):
        tv = TV()
        tv.state = {"version": 1, "user": 0, "holders": [], "settings": {
            "assistant": "", "voice_interaction_service": None, "voice_recognition_service": None}}
        original = copy.deepcopy(tv.state)
        self.quiet(tv.enable); self.quiet(tv.restore)
        self.assertEqual(tv.state, original)

    def test_partial_enable_failure_rolls_back(self):
        tv = TV(); tv.fail_once = "voice_recognition_service"
        with self.assertRaisesRegex(RuntimeError, "previous selection was restored"): tv.enable()
        self.assertEqual(tv.state, OLD)
        self.assertEqual(tv.saved, OLD)

    def test_partial_restore_failure_rolls_back_to_gemini(self):
        tv = TV(); self.quiet(tv.enable)
        enabled = copy.deepcopy(tv.state)
        tv.fail_once = "assistant"
        with self.assertRaisesRegex(RuntimeError, "previous selection was restored"): tv.restore()
        self.assertEqual(tv.state, enabled)
        self.assertEqual(tv.saved, OLD)

    def test_restore_does_not_override_another_assistant_chosen_later(self):
        tv = TV(); self.quiet(tv.enable)
        tv.state = copy.deepcopy(OLD)
        tv.state["holders"] = ["org.example.other"]
        tv.state["settings"]["assistant"] = "org.example.other/.Assistant"
        tv.state["settings"]["voice_interaction_service"] = "org.example.other/.Assistant"
        before = tv.read()
        self.quiet(tv.restore)
        self.assertEqual(tv.state, before)

    def test_restore_requires_a_real_backup(self):
        tv = TV()
        with self.assertRaisesRegex(RuntimeError, "No saved"): tv.restore()
        self.assertEqual(tv.state, OLD)

    def test_backup_cannot_point_to_self_or_contain_shell_commands(self):
        for value in (module.PKG + "/.AssistService", "org.example/.Assist;rm -rf /"):
            state = copy.deepcopy(OLD); state["settings"]["assistant"] = value
            with self.assertRaises(RuntimeError): module.Assistant.validate(state)

    def test_role_dump_is_scoped_to_primary_user(self):
        tv = TV()
        tv.shell = lambda *args: """user_id=0 roles=[{name=android.app.role.ASSISTANT
          holders=org.example.assistant
        }] user_id=10 roles=[{name=android.app.role.ASSISTANT
          holders=org.example.other
        }]"""
        self.assertEqual(module.Assistant.holders(tv), ["org.example.assistant"])
        tv.shell = lambda *args: "user_id=0 roles=[{name=android.app.role.ASSISTANT\n}]"
        self.assertEqual(module.Assistant.holders(tv), [])
        tv.shell = lambda *args: "unknown role format"
        with self.assertRaises(RuntimeError): module.Assistant.holders(tv)


if __name__ == "__main__": unittest.main()
