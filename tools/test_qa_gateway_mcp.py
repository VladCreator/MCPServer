#!/usr/bin/env python3
"""Unit tests for the unified QA gateway."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("gateway", Path(__file__).with_name("qa_gateway_mcp.py"))
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)


class FakeBackend:
    def __init__(self):
        self.name = "ios"
        self.platform = "ios"
        self.connected = True
        self.error = None
        self.tools = [{
            "name": "get_device_state",
            "description": "State",
            "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
        }]
        self.calls = []

    def connect(self):
        self.connected = True
        return True

    def call(self, tool, arguments):
        self.calls.append((tool, arguments))
        return {"content": [{"type": "text", "text": "state ok"}], "isError": False}

    def close(self):
        pass


class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.backend = FakeBackend()
        self.gateway = gateway.Gateway([self.backend], self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_tools_namespaced(self):
        names = [tool["name"] for tool in self.gateway.tools()]
        self.assertIn("qa_capabilities", names)
        self.assertIn("qa_session", names)
        self.assertIn("ios__get_device_state", names)

    def test_http_headers_include_session(self):
        backend = gateway.HttpBackend("ios", "ios", "http://127.0.0.1:8080/mcp", "token")
        backend.session_id = "session-1"
        headers = backend._headers()
        self.assertEqual("Bearer token", headers["Authorization"])
        self.assertEqual("session-1", headers["Mcp-Session-Id"])

    def test_gateway_session_and_report(self):
        begin = gateway.handle(self.gateway, {
            "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": "qa_session", "arguments": {"action": "begin", "test_case": "P0", "platform": "ios"}},
        })
        self.assertFalse(begin["result"]["isError"])
        self.gateway.call("ios__get_device_state", {})
        step = gateway.handle(self.gateway, {
            "jsonrpc": "2.0", "id": 2, "method": "tools/call",
            "params": {"name": "qa_session", "arguments": {"action": "step", "title": "State", "status": "pass"}},
        })
        self.assertFalse(step["result"]["isError"])
        end = gateway.handle(self.gateway, {
            "jsonrpc": "2.0", "id": 3, "method": "tools/call",
            "params": {"name": "qa_session", "arguments": {"action": "end", "result": "PASS"}},
        })
        self.assertFalse(end["result"]["isError"])
        reports = list(Path(self.temp.name).glob("gw-*.json"))
        self.assertEqual(1, len(reports))
        report = json.loads(reports[0].read_text())
        self.assertEqual("PASS", report["result"])
        self.assertEqual(1, len(report["steps"]))
        self.assertEqual(1, len(report["tool_calls"]))
        self.assertEqual("get_device_state", report["tool_calls"][0]["tool"])

    def test_unknown_backend(self):
        result = gateway.handle(self.gateway, {
            "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": "android__get_device_info", "arguments": {}},
        })
        self.assertTrue(result["result"]["isError"])
        self.assertIn("Unknown backend", result["result"]["content"][0]["text"])


if __name__ == "__main__":
    unittest.main()
