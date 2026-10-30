#!/usr/bin/env python3
"""Host CI recovery and exit-code contracts; no device or approval bypass."""

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import tester_ci as ci


class FakeClient:
    def __init__(self, state_path, status="completed", pending=False):
        self.path, self.status, self.pending = state_path, status, pending
        self.calls = []

    def initialize(self):
        pass

    def call(self, tool, args, allow_error=False):
        self.calls.append((tool, args))
        if tool == "run_route":
            state = json.loads(self.path.read_text())
            assert state["run_key"] == args["run_key"]  # persisted before dispatch
            return {"run_id": "known-run", "status": "queued"}
        if tool in ("get_run", "cancel_run"):
            return {"run_id": "known-run", "status": self.status, "pending_cleanup": self.pending,
                    "cleanup_status": "failed" if self.pending else "completed"}
        return {"run_id": "known-run", "content_redacted": True, "content": "redacted native artifact"}


class CiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "owner.json"
        self.output = Path(self.temp.name) / "out"

    def execute(self, client, arguments=None, **kw):
        with patch("builtins.print"):
            return ci.execute(client, arguments or {"yaml": "definition"}, self.path, self.output, **kw)

    def test_persists_owner_before_submit_and_exports_canonical_artifacts(self):
        client = FakeClient(self.path)
        self.assertEqual(0, self.execute(client))
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)
        self.assertEqual("known-run", ci.load_state(self.path)["run_id"])
        self.assertEqual({"run.json", "timeline.md", "junit.xml"}, {p.name for p in self.output.iterdir()})
        self.assertEqual(["run_route", "get_run", "export_run", "export_run", "export_run"],
                         [name for name, _ in client.calls])

    def test_resume_polls_original_run_without_submission_or_replay(self):
        client = FakeClient(self.path)
        self.execute(client)
        recovered = FakeClient(self.path)
        self.assertEqual(0, self.execute(recovered, arguments={}, resume=True))
        self.assertNotIn("run_route", [name for name, _ in recovered.calls])
        self.assertEqual("known-run", recovered.calls[0][1]["run_id"])

    def test_lost_initial_response_retains_key_and_resubmits_same_identity_only_explicitly(self):
        original = FakeClient(self.path)
        call = original.call

        def lose(tool, args, **kw):
            if tool == "run_route":
                call(tool, args, **kw)
                raise ci.ClientError("response lost")
            return call(tool, args, **kw)

        original.call = lose
        with self.assertRaises(ci.ClientError):
            self.execute(original)
        saved = ci.load_state(self.path)
        with self.assertRaises(ci.ClientError):
            self.execute(FakeClient(self.path), arguments={}, resume=True)
        retry = FakeClient(self.path)
        self.execute(retry)
        self.assertEqual(saved["run_key"], retry.calls[0][1]["run_key"])
        self.assertEqual(saved["submission_id"], retry.calls[0][1]["submission_id"])

    def test_different_plan_cannot_reuse_state(self):
        self.execute(FakeClient(self.path))
        with self.assertRaises(ci.ClientError):
            self.execute(FakeClient(self.path), arguments={"yaml": "different"})

    def test_failure_interruption_cancellation_and_cleanup_failure_exit_nonzero(self):
        for status, pending in (("failed", False), ("timed_out", False), ("interrupted", False),
                                ("cancelled", False), ("completed", True)):
            with self.subTest(status=status, pending=pending):
                client = FakeClient(self.path, status, pending)
                self.assertEqual(1, self.execute(client))

    def test_poll_timeout_does_not_cancel_or_replay_accepted_run(self):
        client = FakeClient(self.path, "running")
        with self.assertRaises(ci.ClientError):
            self.execute(client, timeout=0)
        self.assertEqual(["run_route", "get_run"], [tool for tool, _ in client.calls])
        self.assertEqual("known-run", ci.load_state(self.path)["run_id"])

    def test_rejects_public_or_symlink_state_and_remote_plaintext(self):
        self.execute(FakeClient(self.path))
        os.chmod(self.path, 0o644)
        with self.assertRaises(ci.ClientError):
            ci.load_state(self.path)
        link = self.path.with_name("link")
        link.symlink_to(self.path)
        with self.assertRaises(ci.ClientError):
            ci.private_write(link, b"secret")
        with self.assertRaises(ci.ClientError):
            ci.Client("http://device.example/mcp")
        with self.assertRaises(ci.ClientError):
            ci.Client("https://user:password@device.example/mcp")

    def test_cancel_uses_owned_run_and_still_exports_failure(self):
        self.execute(FakeClient(self.path))
        client = FakeClient(self.path, "cancelled")
        self.assertEqual(1, self.execute(client, resume=True, cancel=True))
        self.assertEqual("cancel_run", client.calls[0][0])


if __name__ == "__main__":
    unittest.main()
