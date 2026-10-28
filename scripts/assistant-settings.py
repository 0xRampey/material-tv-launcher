#!/usr/bin/env python3
"""Explicit Gemini opt-in and exact assistant restoration over authorized ADB."""
import argparse
import json
import re
import shlex
import subprocess
import tempfile
from pathlib import Path

PKG = "com.rampey.materialtv"
ROLE = "android.app.role.ASSISTANT"
STATE = f"/sdcard/Android/data/{PKG}/files/previous_assistant.json"
FIELDS = ("assistant", "voice_interaction_service", "voice_recognition_service")
COMPONENT = re.compile(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
PACKAGE = re.compile(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+")


class Assistant:
    def __init__(self, target):
        self.target = target
        self.user = self.shell("am", "get-current-user").strip()
        if not self.user.isdecimal():
            raise RuntimeError("Couldn't identify the active TV user")
        if self.user != "0":
            raise RuntimeError("Assistant setup currently supports the TV's primary user only")

    def adb(self, *args):
        return subprocess.check_output(["adb", "-s", self.target, *args], text=True, stderr=subprocess.PIPE).strip()

    def shell(self, *args):
        return self.adb("shell", " ".join(shlex.quote(str(arg)) for arg in args))

    def holders(self):
        # Android 12 has no `cmd role get-role-holders`. Read only this user's role block.
        dump = self.shell("dumpsys", "role")
        users = re.split(r"\buser_id=(\d+)\b", dump)
        for index in range(1, len(users), 2):
            if users[index] != self.user:
                continue
            match = re.search(r"\bname=" + re.escape(ROLE) + r"\s*(.*?)\}", users[index + 1], re.S)
            if match is None:
                raise RuntimeError("Couldn't read the current assistant role; nothing changed")
            holder = re.search(r"\bholders=([^\n}\r]+)", match.group(1))
            values = [] if holder is None else holder.group(1).strip().split(",")
            if any(not PACKAGE.fullmatch(value.strip()) for value in values):
                raise RuntimeError("Unrecognized assistant role state; nothing changed")
            return [value.strip() for value in values]
        raise RuntimeError("Couldn't read the TV user's assistant role; nothing changed")

    def read(self):
        return {"version": 1, "user": int(self.user), "holders": self.holders(),
                "settings": {field: self.setting(field) for field in FIELDS}}

    def setting(self, field):
        value = self.shell("settings", "--user", self.user, "get", "secure", field)
        return None if value == "null" else value

    @staticmethod
    def validate(state):
        if not isinstance(state, dict) or state.get("version") != 1 or state.get("user") != 0:
            raise RuntimeError("Invalid saved assistant state")
        values = state.get("settings", {})
        if not isinstance(values, dict):
            raise RuntimeError("Invalid saved assistant settings")
        for field in FIELDS:
            value = values.get(field)
            if field not in values or (value not in (None, "") and
                    (not isinstance(value, str) or not COMPONENT.fullmatch(value) or value.split("/")[0] == PKG)):
                raise RuntimeError("Saved assistant settings are incomplete or invalid")
        holders = state.get("holders")
        if not isinstance(holders, list) or any(not isinstance(p, str) or not PACKAGE.fullmatch(p) or p == PKG for p in holders):
            raise RuntimeError("Saved assistant role is incomplete or invalid")
        return state

    def load(self):
        return self.validate(json.loads(self.shell("cat", STATE)))

    def save(self, state):
        self.validate(state)
        self.shell("mkdir", "-p", str(Path(STATE).parent))
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "previous_assistant.json"
            source.write_text(json.dumps(state), encoding="utf-8")
            self.adb("push", str(source), STATE + ".tmp")
            self.shell("mv", STATE + ".tmp", STATE)
        if self.load() != state:
            raise RuntimeError("Couldn't verify the previous assistant backup; nothing changed")

    def apply(self, state):
        current = self.holders()
        for package in current:
            if package not in state["holders"]:
                self.shell("cmd", "role", "remove-role-holder", "--user", self.user, ROLE, package, "0")
        for package in state["holders"]:
            if package not in current:
                self.shell("cmd", "role", "add-role-holder", "--user", self.user, ROLE, package, "0")
        # Role changes can rewrite these settings. Restore them after applying the role.
        for field in FIELDS:
            value = state["settings"][field]
            if value is None:
                self.shell("settings", "--user", self.user, "delete", "secure", field)
            else:
                self.shell("settings", "--user", self.user, "put", "secure", field, value)
        actual = self.read()
        if actual["settings"] != state["settings"] or sorted(actual["holders"]) != sorted(state["holders"]):
            raise RuntimeError("Android did not retain the requested assistant settings")

    def change(self, desired, before):
        try:
            self.apply(desired)
        except Exception as error:
            try:
                self.apply(before)
            except Exception:
                raise RuntimeError("Assistant change and rollback failed; use Android's default-app settings") from error
            raise RuntimeError("Assistant change failed; the previous selection was restored") from error

    def has_key(self):
        # Never print the key. Check actual non-whitespace content before changing permissions or roles.
        try:
            self.shell("sh", "-c", f"test -s /sdcard/Android/data/{PKG}/files/openrouter_key && "
                       f"test $(tr -d '[:space:]' < /sdcard/Android/data/{PKG}/files/openrouter_key | wc -c) -gt 10")
            return True
        except subprocess.CalledProcessError:
            return False

    def status(self):
        state = self.read()
        try:
            previous = self.load()
        except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError):
            previous = None
        interaction = state["settings"]["voice_interaction_service"] or ""
        print(json.dumps({"assistant": state["settings"]["assistant"], "role_holders": state["holders"],
                          "gemini_selected": PKG in state["holders"] and interaction.startswith(PKG + "/"),
                          "key_present": self.has_key(), "restore_available": previous is not None,
                          "previous_assistant": previous["settings"]["assistant"] if previous else None,
                          "api_access_verified": False}, indent=2))

    def enable(self):
        if not self.has_key():
            raise RuntimeError("No usable key file on the TV. Add --key-file /path/to/key; assistant selection was not changed.")
        before = self.read()
        ours = PKG in before["holders"] or any((before["settings"][field] or "").startswith(PKG + "/") for field in FIELDS[:2])
        if not ours:
            self.save(before)
        # An already-enabled installation without a backup cannot reconstruct its original assistant.
        self.shell("pm", "grant", PKG, "android.permission.RECORD_AUDIO")
        desired = {"version": 1, "user": 0, "holders": [PKG], "settings": {
            "assistant": PKG + "/.AssistService", "voice_interaction_service": PKG + "/.AssistService",
            "voice_recognition_service": PKG + "/.AssistRecognitionService"}}
        self.change(desired, before)
        print("Gemini enabled. Audio and TV context go through OpenRouter only when you ask.")
        if ours:
            try:
                self.load()
            except Exception:
                print("No previous assistant was recorded by the older build. Choose one in Android's default-app settings.")

    def restore(self):
        desired = self.load()
        before = self.read()
        if PKG not in before["holders"] and not any((before["settings"][field] or "").startswith(PKG + "/") for field in FIELDS[:2]):
            print("Another assistant is already selected; leaving it unchanged.")
            return
        self.change(desired, before)
        print("Previous assistant restored. Material TV Launcher remains installed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tv_ip")
    parser.add_argument("action", choices=("enable", "restore", "status"))
    args = parser.parse_args()
    try:
        getattr(Assistant(args.tv_ip + ":5555"), args.action)()
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError) as error:
        # Subprocess command text may contain credentials in future; report no command/output payloads.
        generic = ("Couldn't read assistant status. Check the TV's ADB connection." if args.action == "status"
                   else "Couldn't update assistant settings. Check ADB, the saved selection, and your key file.")
        print(str(error) if isinstance(error, RuntimeError) else generic)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
