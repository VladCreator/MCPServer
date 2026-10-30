#!/usr/bin/env python3
"""Opt-in P3 contracts on the synthetic fixture; no installation or permission grants.

Use an explicitly provisioned disposable device, approved unattended MCP sessions,
TESTER_FIXTURE_TESTS=1 and TESTER_FIXTURE_SETTINGS_PROVISIONED=1.
"""
import os
import secrets
import unittest
from unittest.mock import patch

import test_android_agent_mcp as native


@unittest.skipUnless(os.environ.get("TESTER_FIXTURE_TESTS") == "1"
                     and os.environ.get("TESTER_MCP_URL") and os.environ.get("TESTER_MCP_SESSION"),
                     "P3 synthetic fixture checks not configured")
class AndroidEnvironmentTests(unittest.TestCase):
    call = native.AndroidAgentContractTests.call
    package = "com.vlad.mcpserver.fixture"

    def ok(self, tool, args):
        result = self.call(tool, args)
        self.assertFalse(result["isError"], result)
        return result

    def test_explicit_deep_link_is_delivered_and_foreground_verified(self):
        self.ok("launch_app", {"package_name": "com.vlad.mcpserver"})
        result = self.ok("open_link", {"package_name": self.package,
                                      "uri": "tester-fixture://route/cards?synthetic=secret",
                                      "wait_ms": 3000})
        self.assertEqual("ready", result["structuredContent"]["reason"])
        self.ok("ui_assert", {"condition": "text", "resource_id": "status",
                             "expected": "Deep link opened", "package_name": self.package})
        self.assertNotIn("secret", str(result))

    def test_unsafe_uris_and_wrong_app_have_no_launch(self):
        self.ok("launch_app", {"package_name": self.package})
        for uri in ("file:///data/local/test", "content://test/path", "intent://route/cards",
                    "javascript:alert(1)", "data:text/plain,synthetic", "tel://12345",
                    "https://user:secret@example.com/path"):
            result = self.call("open_link", {"package_name": self.package, "uri": uri})
            self.assertTrue(result["isError"], result)
            self.assertEqual("invalid_uri", result["structuredContent"]["reason"])
        wrong = self.call("open_link", {"package_name": "com.vlad.mcpserver",
                                       "uri": "tester-fixture://route/cards"})
        self.assertTrue(wrong["isError"])
        self.assertEqual("ambiguous_or_unavailable_handler", wrong["structuredContent"]["reason"])
        extra = self.call("open_link", {"package_name": self.package, "uri": "tester-fixture://route/cards",
                                       "flags": 1})
        self.assertTrue(extra["isError"])
        invalid_wait = self.call("open_link", {"package_name": self.package, "uri": "tester-fixture://route/cards",
                                              "wait_ms": None})
        self.assertTrue(invalid_wait["isError"])
        state = self.ok("observe_screen", {})["structuredContent"]
        self.assertEqual(self.package, state["foreground_package"])

    def test_clipboard_is_write_first_foreground_and_session_owned(self):
        self.ok("launch_app", {"package_name": "com.vlad.mcpserver"})
        # A fresh process/session must not read unrelated clipboard content.
        denied = self.call("clipboard", {"action": "read_owned"})
        self.assertTrue(denied["isError"])
        text = "P3_SYNTHETIC_CLIP_" + secrets.token_hex(6)
        try:
            written = self.ok("clipboard", {"action": "write", "text": text})
            self.assertNotIn(text, str(written))
            self.assertEqual(text, self.ok("clipboard", {"action": "read_owned"})["structuredContent"]["text"])
            if os.environ.get("TESTER_MCP_SECOND_SESSION"):
                with patch.dict(os.environ, {"TESTER_MCP_SESSION": os.environ["TESTER_MCP_SECOND_SESSION"]}):
                    self.assertTrue(self.call("clipboard", {"action": "read_owned"})["isError"])
                    self.assertTrue(self.call("clipboard", {"action": "clear_owned"})["isError"])
            self.ok("launch_app", {"package_name": self.package})
            background = self.call("clipboard", {"action": "read_owned"})
            self.assertTrue(background["isError"])
            self.assertEqual("foreground_required", background["structuredContent"]["reason"])
        finally:
            self.ok("launch_app", {"package_name": "com.vlad.mcpserver"})
            self.ok("clipboard", {"action": "clear_owned"})
        self.assertTrue(self.call("clipboard", {"action": "read_owned"})["isError"])

    @unittest.skipUnless(os.environ.get("TESTER_FIXTURE_SETTINGS_PROVISIONED") == "1",
                         "Disposable-device settings permissions not provisioned")
    def test_touched_fields_restore_fully_without_rotation_snapshot(self):
        key = secrets.token_urlsafe(32)
        try:
            result = self.ok("device_state", {"action": "set", "state_key": key, "volume": 20})
            self.assertEqual(["volume"], result["structuredContent"]["fields"])
            status = self.ok("device_state", {"action": "status", "state_key": key})["structuredContent"]
            self.assertEqual(["volume"], status["fields"])
            denied = self.call("device_state", {"action": "restore", "state_key": secrets.token_urlsafe(32)})
            self.assertTrue(denied["isError"])
            self.assertEqual("state_owned_by_another_client", denied["structuredContent"]["reason"])
            conflict = self.call("device_state", {"action": "set", "state_key": secrets.token_urlsafe(32),
                                                 "volume": 30})
            self.assertTrue(conflict["isError"])
        finally:
            restored = self.ok("device_state", {"action": "restore", "state_key": key})
        self.assertFalse(restored["structuredContent"]["snapshot_retained"])

    def test_global_dark_mode_is_rejected_before_any_other_field_write(self):
        key = secrets.token_urlsafe(32)
        before = self.ok("audio_state", {})["content"]
        rejected = self.call("device_state", {"action": "set", "state_key": key,
                                              "volume": 0, "dark_mode": "on"})
        self.assertTrue(rejected["isError"])
        self.assertIn("unsupported", str(rejected).lower())
        status = self.ok("device_state", {"action": "status", "state_key": key})
        self.assertFalse(status["structuredContent"]["snapshot_retained"])
        self.assertEqual(before, self.ok("audio_state", {})["content"])

    @unittest.skipUnless(os.environ.get("TESTER_FIXTURE_SETTINGS_PROVISIONED") == "1",
                         "Disposable-device settings permissions not provisioned")
    def test_font_scale_and_keep_awake_have_effective_readback_and_restore(self):
        key = secrets.token_urlsafe(32)
        try:
            data = self.ok("device_state", {"action": "set", "state_key": key,
                                            "font_scale": 1.1, "keep_awake": True})["structuredContent"]
            self.assertEqual({"font_scale", "keep_awake"}, set(data["verified_fields"]))
            self.assertAlmostEqual(1.1, data["readback"]["font_scale"], places=2)
            self.assertTrue(data["readback"]["keep_awake"])
        finally:
            self.ok("device_state", {"action": "restore", "state_key": key})

    @unittest.skipUnless(os.environ.get("TESTER_FIXTURE_SETTINGS_PROVISIONED") == "1",
                         "Disposable-device settings permissions not provisioned")
    def test_qa_end_restores_only_owned_touched_fields(self):
        key = secrets.token_urlsafe(32)
        self.ok("qa_session", {"action": "begin", "test_case": "P3 owned cleanup"})
        self.assertFalse(self.ok("device_state", {"action": "status", "state_key": key})
                         ["structuredContent"]["snapshot_retained"])
        self.ok("device_state", {"action": "set", "state_key": key, "volume": 30})
        try:
            ended = self.ok("qa_session", {"action": "end", "result": "PASS", "state_key": key})
            self.assertEqual("restored", ended["structuredContent"]["reason"])
            self.assertNotIn(key, str(ended))
            self.assertFalse(self.ok("device_state", {"action": "status", "state_key": key})
                             ["structuredContent"]["snapshot_retained"])
        finally:
            status = self.ok("device_state", {"action": "status", "state_key": key})
            if status["structuredContent"]["snapshot_retained"]:
                self.ok("device_state", {"action": "restore", "state_key": key})

    def test_permission_and_locale_inspection_never_claim_privileged_setup(self):
        data = self.ok("get_test_environment", {"package_name": self.package})["structuredContent"]
        camera = next(p for p in data["permissions"] if p["permission"] == "android.permission.CAMERA")
        self.assertIsInstance(camera["granted"], bool)
        self.assertIn(data["locale"]["status"], ("available", "denied"))
        for control in ("permission_mutation", "locale_mutation", "location", "app_data_reset", "device_time"):
            self.assertIn(control, data["unsupported_controls"])
        capabilities = self.ok("get_capabilities", {})["structuredContent"]
        self.assertIn("global_dark_mode", capabilities["unsupported_controls"])
        self.assertNotIn("clipboard", capabilities["unsupported_controls"])

    def test_provenance_is_app_reported_not_independent_verification(self):
        data = self.ok("get_build_identity", {"package_name": self.package})["structuredContent"]
        self.assertEqual("app_reported_unverified", data["provenance_status"])
        self.assertEqual("synthetic-fixture-not-ci", data["ci_artifact"])
        self.assertFalse(data["independently_verified"])
        missing = self.ok("get_build_identity", {"package_name": "com.vlad.mcpserver"})["structuredContent"]
        self.assertEqual("unknown", missing["provenance_status"])
        self.assertIsNone(missing["sha"])


if __name__ == "__main__":
    unittest.main()
