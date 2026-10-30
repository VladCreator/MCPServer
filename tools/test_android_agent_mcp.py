#!/usr/bin/env python3
"""Opt-in live MCP contract checks; use only a disposable Android test device.

Set TESTER_MCP_URL and TESTER_MCP_SESSION to an already approved session.
The foreground must be the Tester dashboard. These tests do not approve tools,
change settings, install builds, or bypass authentication.
"""

import json
import os
import time
import unittest
import urllib.request


@unittest.skipUnless(os.environ.get("TESTER_MCP_URL") and os.environ.get("TESTER_MCP_SESSION"),
                     "Live MCP session not configured")
class AndroidAgentContractTests(unittest.TestCase):
    def setUp(self):
        readiness = self.call("ui_wait", {
            "condition": "idle", "package_name": "com.vlad.mcpserver",
            "timeout_ms": 5000, "quiet_ms": 400,
        })
        self.assertFalse(readiness["isError"], "Test precondition: Tester dashboard must be foreground and idle")

    def call(self, tool, arguments):
        request = urllib.request.Request(
            os.environ["TESTER_MCP_URL"],
            data=json.dumps({
                "jsonrpc": "2.0", "id": time.time_ns(), "method": "tools/call",
                "params": {"name": tool, "arguments": arguments},
            }).encode(),
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json, text/event-stream",
                "Mcp-Session-Id": os.environ["TESTER_MCP_SESSION"],
            },
        )
        with urllib.request.urlopen(request, timeout=70) as response:
            body = response.read().decode()
        if body.startswith(("event:", "data:")):
            body = next(line[6:] for line in body.splitlines() if line.startswith("data: "))
        reply = json.loads(body)
        self.assertNotIn("error", reply)
        return reply["result"]

    def test_native_text_checks_do_not_accept_wrong_expected_value(self):
        for tool in ("ui_wait", "ui_assert"):
            for expected, should_pass in (("Tester", True), ("P0_WRONG_EXPECTED_7291", False)):
                with self.subTest(tool=tool, expected=expected):
                    result = self.call(tool, {
                        "condition": "text", "text": "Tester", "text_exact": True,
                        "expected": expected, "package_name": "com.vlad.mcpserver",
                        "timeout_ms": 250, "max_dimension": 320,
                    } if tool == "ui_assert" else {
                        "condition": "text", "text": "Tester", "text_exact": True,
                        "expected": expected, "package_name": "com.vlad.mcpserver", "timeout_ms": 250,
                    })
                    self.assertEqual(not should_pass, result["isError"])
                    structured = result["structuredContent"]
                    self.assertEqual(should_pass, structured["passed"])
                    self.assertEqual("matched" if should_pass else "text_mismatch", structured["reason"])
                    self.assertEqual(["Tester"], structured["actual"])

    def test_wrong_package_never_passes_absence_or_zero_count(self):
        for condition, expected in (("absent", None), ("count", "0")):
            args = {
                "condition": condition, "text": "P0_NONEXISTENT_7291",
                "package_name": "com.example.wrong", "timeout_ms": 250, "max_dimension": 320,
            }
            if expected is not None:
                args["expected"] = expected
            result = self.call("ui_assert", args)
            self.assertTrue(result["isError"])
            self.assertEqual("wrong_package", result["structuredContent"]["reason"])

    def test_failure_evidence_and_expected_comparison_modes(self):
        result = self.call("ui_assert", {
            "condition": "visible", "text": "P0_NONEXISTENT_7291", "timeout_ms": 1000, "max_dimension": 320,
        })
        self.assertTrue(result["isError"])
        self.assertEqual("no_match", result["structuredContent"]["reason"])
        self.assertTrue(any(c["type"] == "image" for c in result["content"]))
        self.assertTrue(any("tree excerpt" in c.get("text", "") for c in result["content"]))
        result = self.call("ui_wait", {
            "condition": "text", "text": "Tester", "text_exact": True, "expected": "test",
            "text_mode": "contains", "ignore_case": True, "timeout_ms": 250,
        })
        self.assertFalse(result["isError"])
        result = self.call("ui_assert", {"condition": "text", "expected": "Tester", "timeout_ms": 250})
        self.assertFalse(result["isError"])

    def test_capabilities_are_structured_and_explicit(self):
        result = self.call("get_capabilities", {})
        self.assertFalse(result["isError"])
        capabilities = result["structuredContent"]
        self.assertEqual("0.3.0", capabilities["server_version"])
        tools = capabilities["tools"]
        self.assertEqual(43, len(tools))
        self.assertFalse(capabilities["visibility"]["live_capture"])
        self.assertEqual("unsupported; explicitly deferred", capabilities["visibility"]["recording"])
        self.assertIn("host", capabilities["visibility"]["execution_location"])
        self.assertTrue(all(t["execution_location"] == "android_device" for t in tools))
        self.assertTrue(all("missing_permissions" in t and "reversibility" in t for t in tools))
        self.assertIn("device_time", capabilities["unsupported_controls"])


if __name__ == "__main__":
    unittest.main()
