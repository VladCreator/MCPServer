#!/usr/bin/env python3
"""Unit tests for the unified QA gateway."""

import importlib.util
import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

spec = importlib.util.spec_from_file_location("gateway", Path(__file__).with_name("qa_gateway_mcp.py"))
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)


class FakeBackend(gateway.ReconnectMixin):
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

    def test_route_is_forwarded_without_persisting_payload(self):
        self.gateway.qa_session({"action": "begin", "test_case": "route"})
        arguments = {"yaml": "private route", "parameters": {"password": "private"}}
        result = self.gateway.call("ios__run_route", arguments)
        self.assertFalse(result["isError"])
        self.assertEqual(("run_route", arguments), self.backend.calls[-1])
        self.assertEqual("[private tool payload omitted]", self.gateway.session.calls[-1]["arguments"])

    def test_route_failure_is_recorded_as_failure(self):
        self.gateway.qa_session({"action": "begin", "test_case": "route"})
        self.backend.call = lambda tool, arguments: {"content": [], "isError": True}
        self.assertTrue(self.gateway.call("ios__run_route", {"yaml": "route"})["isError"])
        self.assertFalse(self.gateway.session.calls[-1]["ok"])

    def test_native_structured_failure_is_preserved(self):
        self.gateway.qa_session({"action": "begin", "test_case": "predicate"})
        result = {
            "content": [{"type": "text", "text": "Mismatch"}],
            "isError": True,
            "structuredContent": {"reason": "text_mismatch", "actual": ["Tester"], "expected": "Wrong"},
        }
        self.backend.call = lambda tool, arguments: result
        self.assertEqual(result, self.gateway.call("ios__ui_assert", {"condition": "text"}))
        self.assertFalse(self.gateway.session.calls[-1]["ok"])

    def test_route_is_not_replayed_after_session_loss(self):
        calls = []

        def lose_session(tool, arguments):
            calls.append(tool)
            raise gateway.SessionLost("lost")

        self.backend.call = lose_session
        with self.assertRaisesRegex(gateway.BackendError, "not retried"):
            self.gateway.call("ios__run_route", {"yaml": "route"})
        self.assertEqual(["run_route"], calls)

    def test_run_recovery_arguments_are_not_persisted(self):
        self.gateway.qa_session({"action": "begin", "test_case": "recovery"})
        for tool in ("get_run", "cancel_run", "collect_failure_bundle", "export_run", "get_artifact",
                     "device_state", "open_link", "clipboard", "qa_session"):
            self.gateway.call("ios__" + tool, {"run_id": "id", "run_key": "private key"})
            self.assertEqual("[private tool payload omitted]", self.gateway.session.calls[-1]["arguments"])

    def test_failure_bundle_is_not_recaptured_after_session_loss(self):
        calls = []

        def lose_session(tool, arguments):
            calls.append(tool)
            raise gateway.SessionLost("lost")

        self.backend.call = lose_session
        with self.assertRaisesRegex(gateway.BackendError, "not retried"):
            self.gateway.call("ios__collect_failure_bundle", {"run_key": "private key"})
        self.assertEqual(["collect_failure_bundle"], calls)

    def test_environment_mutations_are_not_replayed_after_session_loss(self):
        for tool in ("device_state", "open_link", "clipboard", "qa_session"):
            calls = []

            def lose_session(name, arguments):
                calls.append(name)
                raise gateway.SessionLost("lost")

            self.backend.call = lose_session
            with self.assertRaisesRegex(gateway.BackendError, "not retried"):
                self.gateway.call("ios__" + tool, {"state_key": "private key"})
            self.assertEqual([tool], calls)


class FlakyBackend(FakeBackend):
    """Offline until `online` is set; raises SessionLost once when `expire` is set."""

    def __init__(self):
        super().__init__()
        self.connected = False
        self.online = False
        self.expire = False
        self.connects = 0

    def connect(self):
        self.connects += 1
        self.connected = self.online
        self.error = None if self.online else "ios: unreachable"
        return self.connected

    def call(self, tool, arguments):
        if not self.connected:
            raise gateway.BackendError(self.error)
        if self.expire:
            self.expire = False
            raise gateway.SessionLost("ios: session expired")
        return super().call(tool, arguments)


class ReconnectTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.backend = FlakyBackend()
        self.gateway = gateway.Gateway([self.backend], self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_offline_backend_reconnects_on_next_call(self):
        self.assertNotIn("ios__get_device_state", [tool["name"] for tool in self.gateway.tools()])
        with self.assertRaises(gateway.BackendError):
            self.gateway.call("ios__get_device_state", {})
        self.backend.online = True
        result = self.gateway.call("ios__get_device_state", {})
        self.assertFalse(result["isError"])
        self.assertEqual([{"jsonrpc": "2.0", "method": "notifications/tools/list_changed"}],
                         self.gateway.drain_notifications())
        self.assertIn("ios__get_device_state", [tool["name"] for tool in self.gateway.tools()])

    def test_session_lost_reconnects_and_retries_once(self):
        self.backend.online = True
        self.gateway.call("ios__get_device_state", {})
        connects = self.backend.connects
        self.backend.expire = True
        result = self.gateway.call("ios__get_device_state", {})
        self.assertFalse(result["isError"])
        self.assertEqual(connects + 1, self.backend.connects)
        self.assertEqual(2, len(self.backend.calls))

    def test_reconnect_attempts_are_rate_limited_for_listing(self):
        self.gateway.tools()
        self.gateway.tools()
        self.assertEqual(1, self.backend.connects)


class FakeMcpServer(BaseHTTPRequestHandler):
    """Minimal Streamable HTTP MCP server; `restart()` forgets all sessions."""

    sessions = set()
    counter = 0

    def log_message(self, *args):
        pass

    def do_DELETE(self):
        self.send_response(204)
        self.end_headers()

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        session = self.headers.get("Mcp-Session-Id")
        if body.get("method") == "initialize":
            FakeMcpServer.counter += 1
            session = f"s{FakeMcpServer.counter}"
            FakeMcpServer.sessions.add(session)
            reply = {"jsonrpc": "2.0", "id": body["id"], "result": {"protocolVersion": "2025-06-18"}}
        elif session not in FakeMcpServer.sessions:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"Session not found")
            return
        elif "id" not in body:
            self.send_response(202)
            self.end_headers()
            return
        elif body["method"] == "tools/list":
            reply = {"jsonrpc": "2.0", "id": body["id"], "result": {"tools": [{"name": "ping_tool"}]}}
        else:
            reply = {"jsonrpc": "2.0", "id": body["id"],
                     "result": {"content": [{"type": "text", "text": session}], "isError": False}}
        data = json.dumps(reply).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Mcp-Session-Id", session)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class HttpReconnectTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeMcpServer)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        url = f"http://127.0.0.1:{self.server.server_address[1]}/mcp"
        self.temp = tempfile.TemporaryDirectory()
        self.gateway = gateway.Gateway([gateway.HttpBackend("android", "android", url, timeout=5)], self.temp.name)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.temp.cleanup()

    def test_backend_restart_is_transparent(self):
        first = self.gateway.call("android__ping_tool", {})["content"][0]["text"]
        FakeMcpServer.sessions.clear()
        second = self.gateway.call("android__ping_tool", {})["content"][0]["text"]
        self.assertNotEqual(first, second)


class UnreachableHintTests(unittest.TestCase):
    def test_hints(self):
        url = "http://127.0.0.1:8081/mcp"
        self.assertIn("ios_tunnel.py --check --local-port 8081", gateway.unreachable_hint(url, TimeoutError("timed out")))
        self.assertIn("nothing listens on Mac port 8081", gateway.unreachable_hint(url, ConnectionRefusedError()))
        self.assertIn("Start server", gateway.unreachable_hint(url, ConnectionResetError()))
        self.assertEqual("", gateway.unreachable_hint("http://10.0.0.5:8080/mcp", TimeoutError()))


FAKE_STDIO_BRIDGE = """
import json, sys
for line in sys.stdin:
    msg = json.loads(line)
    if "id" not in msg:
        continue
    method = msg["method"]
    if method == "initialize":
        result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "fake"}}
    elif method == "tools/list":
        result = {"tools": [{"name": "ping", "inputSchema": {"type": "object"}}]}
    else:
        result = {"content": [{"type": "text", "text": "pong"}]}
    print(json.dumps({"jsonrpc": "2.0", "id": msg["id"], "result": result}), flush=True)
"""


class StdioRestartTests(unittest.TestCase):
    def test_dead_bridge_is_restarted_and_call_retried(self):
        backend = gateway.StdioBackend("ui", "ios", sys.executable, ["-c", FAKE_STDIO_BRIDGE], timeout=10)
        gw = gateway.Gateway([backend], tempfile.mkdtemp())
        self.addCleanup(backend.close)
        gw.start()
        self.assertTrue(backend.connected)
        first = backend.process
        first.kill()
        first.wait()
        result = gw.call("ui__ping", {})
        self.assertFalse(result.get("isError"))
        self.assertIsNot(first, backend.process)
        self.assertIsNone(backend.process.poll())


if __name__ == "__main__":
    unittest.main()
