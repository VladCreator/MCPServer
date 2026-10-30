#!/usr/bin/env python3
"""Opt-in P2 live contracts on the separate synthetic fixture.

Requires TESTER_FIXTURE_TESTS=1 and an approved unattended disposable device.
Recovery tests can also use TESTER_MCP_SECOND_SESSION, already approved.
No tests install builds, grant permissions or bypass approval.
"""

import json
import os
import secrets
import time
import unittest
from unittest.mock import patch

import test_android_agent_mcp as native


@unittest.skipUnless(os.environ.get("TESTER_FIXTURE_TESTS") == "1"
                     and os.environ.get("TESTER_MCP_URL") and os.environ.get("TESTER_MCP_SESSION"),
                     "P2 synthetic fixture checks not configured")
class AndroidRunTests(unittest.TestCase):
    call = native.AndroidAgentContractTests.call
    package = "com.vlad.mcpserver.fixture"

    def ok(self, name, args):
        result = self.call(name, args)
        self.assertFalse(result["isError"], result)
        return result

    def setUp(self):
        self.ok("launch_app", {"package_name": self.package})
        self.ok("scroll", {"direction": "up"})
        self.ok("ui_wait", {"condition": "idle", "package_name": self.package, "quiet_ms": 400})
        for _ in range(3):
            found = self.ok("ui_find", {"text": "Synthetic cards fixture", "text_exact": True})
            if found["structuredContent"]["count"]:
                break
            self.ok("global_action", {"action": "back"})
            self.ok("ui_wait", {"condition": "idle", "package_name": self.package, "quiet_ms": 400})
        self.active = []
        self.addCleanup(self.cancel_active)

    def cancel_active(self):
        for owner in self.active:
            self.call("cancel_run", owner)

    def route(self, steps):
        return f"""schema_version: 1
id: p2.synthetic
platform: android
app_id: {self.package}
steps:
""" + "\n".join("  " + step for step in steps)

    def submit(self, steps, **overrides):
        args = {
            "yaml": self.route(steps), "mode": "async",
            "run_key": secrets.token_urlsafe(32), "submission_id": secrets.token_hex(12),
            "disconnect_policy": "continue_until_terminal",
            **overrides,
        }
        data = self.ok("run_route", args)["structuredContent"]
        owner = {"run_id": data["run_id"], "run_key": args["run_key"]}
        self.active.append(owner)
        return owner, args, data

    def terminal(self, owner, timeout=8):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = self.call("get_run", owner)
            data = result["structuredContent"]
            if data["status"] not in ("awaiting_approval", "queued", "running", "cancelling"):
                return data
            time.sleep(.1)
        self.fail("Run did not reach a terminal state")

    def test_combined_wait_uses_one_deadline_and_identifies_failure(self):
        self.ok("wait_for_state", {
            "package_name": self.package,
            "all": [{"condition": "foreground"}, {"condition": "visible", "resource_id": "open",
                     "within": {"resource_id": "project_beta"}},
                    {"condition": "absent", "text": "NONEXISTENT_SPINNER"},
                    {"condition": "idle", "quiet_ms": 200}],
            "timeout_ms": 1500,
        })
        started = time.monotonic()
        failed = self.call("wait_for_state", {
            "package_name": self.package,
            "all": [{"condition": "foreground"},
                    {"condition": "visible", "text": "NONEXISTENT_A"},
                    {"condition": "visible", "text": "NONEXISTENT_B"}],
            "timeout_ms": 500,
        })
        self.assertTrue(failed["isError"])
        self.assertLess(time.monotonic() - started, 2)
        data = failed["structuredContent"]
        self.assertEqual([1, 2], data["failed_conditions"])
        self.assertTrue(all("observed_at_ms" in c for c in data["conditions"]))
        wrong = self.call("wait_for_state", {
            "package_name": "com.example.wrong", "timeout_ms": 250,
            "all": [{"condition": "absent", "text": "missing"}],
        })
        self.assertTrue(wrong["isError"])
        self.assertEqual("wrong_package", wrong["structuredContent"]["conditions"][0]["reason"])

    def test_async_dedup_never_reexecutes_input_or_tap(self):
        before = self.ok("ui_find", {"resource_id": "action_count"})["structuredContent"]["matches"][0]["text"]
        owner, args, accepted = self.submit([
            "- tap: {resource_id: open, within: {resource_id: project_beta}}",
            "- delay: {milliseconds: 1200}",
        ])
        self.assertIn(accepted["status"], ("queued", "running"))
        duplicate = self.ok("run_route", args)["structuredContent"]
        self.assertTrue(duplicate["deduplicated"])
        self.assertEqual(owner["run_id"], duplicate["run_id"])
        final = self.terminal(owner)
        self.assertEqual("completed", final["status"])
        again = self.ok("run_route", args)["structuredContent"]
        self.assertEqual(owner["run_id"], again["run_id"])
        after = self.ok("ui_find", {"resource_id": "action_count"})["structuredContent"]["matches"][0]["text"]
        self.assertEqual(int(before) + 1, int(after))
        conflict = self.call("run_route", {**args, "yaml": self.route(["- back: {}"])})
        self.assertTrue(conflict["isError"])
        self.assertEqual("invalid_run", conflict["structuredContent"]["reason"])

    @unittest.skipUnless(os.environ.get("TESTER_MCP_SECOND_SESSION"), "Second approved client not configured")
    def test_reconnected_session_needs_recovery_key_and_never_replays(self):
        owner, _, _ = self.submit(["- delay: {milliseconds: 1200}"])
        with patch.dict(os.environ, {"TESTER_MCP_SESSION": os.environ["TESTER_MCP_SECOND_SESSION"]}):
            self.assertEqual(owner["run_id"], self.ok("get_run", owner)["structuredContent"]["run_id"])
            wrong = self.call("get_run", {**owner, "run_key": secrets.token_urlsafe(32)})
            self.assertTrue(wrong["isError"])
            self.assertEqual("completed", self.terminal(owner)["status"])

    def test_cancel_stops_later_steps_and_releases_device_lock(self):
        owner, _, _ = self.submit(["- delay: {milliseconds: 5000}", "- tap: {resource_id: open}"])
        time.sleep(.2)
        self.call("cancel_run", owner)
        final = self.terminal(owner)
        self.assertEqual("cancelled", final["status"])
        self.assertEqual("skipped", final["steps"][1]["status"])
        other, _, _ = self.submit(["- delay: {milliseconds: 1}"], queue_timeout_ms=500)
        self.assertEqual("completed", self.terminal(other)["status"])

    def test_queue_timeout_is_separate_and_has_no_dispatched_steps(self):
        owner, _, _ = self.submit(["- delay: {milliseconds: 5000}"])
        time.sleep(.2)
        queued, _, _ = self.submit(["- delay: {milliseconds: 1}"], queue_timeout_ms=250)
        final = self.terminal(queued)
        self.assertEqual("queue_timed_out", final["status"])
        self.assertTrue(all(s["status"] != "dispatched" for s in final["steps"]))
        self.call("cancel_run", owner)

    def test_failure_evidence_and_exports_redact_text_and_preserve_primary_failure(self):
        secret = "P2_SYNTHETIC_SECRET_" + secrets.token_hex(6)
        owner, _, _ = self.submit([
            f"- assert: {{condition: text, resource_id: status, expected: {secret}, timeout_ms: 250}}",
            "- delay: {milliseconds: 1}",
        ])
        final = self.terminal(owner)
        self.assertEqual("failed", final["status"])
        self.assertEqual("text_mismatch", final["steps"][0]["result"]["reason"])
        self.assertNotIn(secret, json.dumps(final))
        bundle = self.call("collect_failure_bundle", {**owner, "package_name": self.package})
        manifest = bundle["structuredContent"]
        self.assertNotIn(secret, json.dumps(manifest))
        sources = {s["source"]: s for s in manifest["sources"]}
        self.assertEqual("redacted", sources["screenshot"]["status"])
        self.assertEqual("redacted", sources["observation"]["status"])
        self.assertIn(sources["recent_logs"]["status"], ("denied", "redacted", "unsupported", "timed_out"))
        self.assertEqual("failed", self.call("get_run", owner)["structuredContent"]["status"])
        for format in ("json", "markdown"):
            export = self.ok("export_run", {**owner, "format": format})["structuredContent"]
            content = self.ok("get_artifact", {**owner, "artifact_id": export["artifact_id"]})
            self.assertNotIn(secret, json.dumps(content))
            denied = self.call("get_artifact", {**owner, "run_key": secrets.token_urlsafe(32),
                                               "artifact_id": export["artifact_id"]})
            self.assertTrue(denied["isError"])
        self.ok("get_artifact", {**owner, "artifact_id": manifest["artifact_id"]})

    def test_build_identity_does_not_infer_sha(self):
        data = self.ok("get_build_identity", {"package_name": self.package})["structuredContent"]
        self.assertEqual(self.package, data["package_name"])
        self.assertIsNone(data["sha"])
        self.assertIsNone(data["ci_artifact"])
        self.assertEqual("unknown", data["provenance_status"])

    def test_privacy_policy_blocks_images_and_explicit_synthetic_capture_is_owned(self):
        private, _, _ = self.submit(["- delay: {milliseconds: 1}"])
        self.terminal(private)
        denied = self.call("collect_failure_bundle", {**private, "package_name": self.package,
                                                     "include_screenshot": True})
        self.assertTrue(denied["isError"])
        self.assertEqual("privacy_policy", denied["structuredContent"]["reason"])
        public, _, _ = self.submit(["- delay: {milliseconds: 1}"], privacy_sensitive=False)
        self.terminal(public)
        bundle = self.call("collect_failure_bundle", {**public, "package_name": self.package,
                                                     "include_screenshot": True})["structuredContent"]
        screenshot = next(s for s in bundle["sources"] if s["source"] == "screenshot")
        self.assertEqual("collected", screenshot["status"])
        content = self.ok("get_artifact", {**public, "artifact_id": screenshot["data"]["artifact_id"]})
        self.assertTrue(any(c["type"] == "image" for c in content["content"]))
        foreign = self.call("get_artifact", {**private, "artifact_id": screenshot["data"]["artifact_id"]})
        self.assertTrue(foreign["isError"])
        wrong = self.call("collect_failure_bundle", {**public, "package_name": "com.example.wrong"})
        self.assertTrue(wrong["isError"])
        self.assertEqual("wrong_package", wrong["structuredContent"]["reason"])
        self.ok("click_element", {"text": "Show secure", "text_exact": True})
        self.ok("ui_wait", {"condition": "idle", "package_name": self.package, "quiet_ms": 400})
        protected = self.call("collect_failure_bundle", {**public, "package_name": self.package,
                                                        "include_screenshot": True})
        self.assertTrue(protected["isError"])
        source = next(s for s in protected["structuredContent"]["sources"] if s["source"] == "screenshot")
        self.assertEqual("failed", source["status"])
        self.assertNotIn("artifact_id", source["data"])


if __name__ == "__main__":
    unittest.main()
