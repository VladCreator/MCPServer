#!/usr/bin/env python3
"""Opt-in QA flow checks on a disposable device, using an approved native session.

Set TESTER_FLOW_TESTS=1 and TESTER_MCP_URL/TESTER_MCP_SESSION. Sensitive calls
still need dashboard approval. This suite does not grant consent or change
device settings. It creates and closes manual QA sessions and one delay-only run.
"""

import os
import secrets
import unittest

import test_android_agent_mcp as native


@unittest.skipUnless(os.environ.get("TESTER_FLOW_TESTS") == "1"
                     and os.environ.get("TESTER_MCP_URL") and os.environ.get("TESTER_MCP_SESSION"),
                     "Explicit disposable-device flow checks not configured")
class AndroidFlowTests(unittest.TestCase):
    call = native.AndroidAgentContractTests.call

    def ok(self, name, arguments):
        response = self.call(name, arguments)
        self.assertFalse(response["isError"], f"{name} failed; inspect native response")
        return response.get("structuredContent", {})

    def test_manual_sessions_have_distinct_ids_and_calls_after_end_are_unlinked(self):
        ids = []
        for _ in range(2):
            begin = self.ok("qa_session", {"action": "begin", "test_case": "Flow isolation fixture"})
            flow = begin["flow_id"]
            ids.append(flow)
            try:
                status = self.ok("qa_session", {"action": "status"})
                self.assertEqual(flow, status["flow_id"])
                health = self.ok("health_check", {})
                self.assertEqual(flow, health["flow_id"])
            finally:
                end = self.ok("qa_session", {"action": "end", "result": "PASS"})
            self.assertEqual(flow, end["flow_id"])
            self.assertNotIn("flow_id", self.ok("health_check", {}))
        self.assertNotEqual(*ids)

    def test_native_retry_and_export_keep_run_flow_and_wrong_key_has_no_link(self):
        key = secrets.token_urlsafe(32)
        args = {
            "yaml": "schema_version: 1\nid: flow.check\nplatform: android\n"
                    "app_id: com.vlad.mcpserver\nsteps:\n  - delay: {milliseconds: 1}\n",
            "mode": "sync", "run_key": key, "submission_id": secrets.token_hex(12),
        }
        first = self.ok("run_route", args)
        second = self.ok("run_route", args)
        self.assertEqual(first["flow_id"], second["flow_id"])
        self.assertEqual(first["run_id"], second["run_id"])
        owner = {"run_id": first["run_id"], "run_key": key}
        self.assertEqual(first["flow_id"], self.ok("get_run", owner)["flow_id"])
        exported = self.ok("export_run", {**owner, "format": "markdown"})
        self.assertEqual(first["flow_id"], exported["flow_id"])
        self.assertIn(f'Flow: #{first["flow_id"]}', exported["content"])
        denied = self.call("get_run", {**owner, "run_key": secrets.token_urlsafe(32)})
        self.assertTrue(denied["isError"])
        self.assertNotIn("flow_id", denied.get("structuredContent", {}))

    def test_async_run_is_separate_from_active_manual_test(self):
        manual = self.ok("qa_session", {"action": "begin", "test_case": "Async flow fixture"})["flow_id"]
        try:
            run = self.ok("run_route", {
                "yaml": "schema_version: 1\nid: flow.async\nplatform: android\n"
                        "app_id: com.vlad.mcpserver\nsteps:\n  - delay: {milliseconds: 1}\n",
                "mode": "async", "disconnect_policy": "continue_until_terminal",
                "run_key": secrets.token_urlsafe(32), "submission_id": secrets.token_hex(12),
            })
            self.assertNotEqual(manual, run["flow_id"])
            self.assertEqual(manual, self.ok("health_check", {})["flow_id"])
        finally:
            self.ok("qa_session", {"action": "end", "result": "PASS"})


if __name__ == "__main__":
    unittest.main()
