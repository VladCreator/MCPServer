#!/usr/bin/env python3
"""Unit tests for the Appium/XCUITest bridge helpers."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("bridge", Path(__file__).with_name("ios_appium_mcp.py"))
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)

XML = """<?xml version="1.0" encoding="UTF-8"?>
<AppiumAUT>
  <XCUIElementTypeApplication type="XCUIElementTypeApplication" label="Tester" name="Tester" x="0" y="0" width="402" height="874" enabled="true" visible="true">
    <XCUIElementTypeWindow type="XCUIElementTypeWindow" x="0" y="0" width="402" height="874" enabled="true" visible="true">
      <XCUIElementTypeButton type="XCUIElementTypeButton" label="Settings" name="Settings" x="10" y="20" width="48" height="48" enabled="true" visible="true"/>
      <XCUIElementTypeStaticText type="XCUIElementTypeStaticText" label="Server is live" name="Server is live" value="Server is live" x="20" y="100" width="200" height="30" enabled="true" visible="true"/>
    </XCUIElementTypeWindow>
  </XCUIElementTypeApplication>
</AppiumAUT>
"""


class BridgeTests(unittest.TestCase):
    def test_tree_and_find(self):
        tree = bridge.tree_text(XML, 20)
        self.assertIn("XCUIElementTypeApplication", tree)
        self.assertIn("Settings", tree)
        matches = bridge.find_matches(XML, {"text": "Settings"})
        self.assertEqual(1, len(matches))
        self.assertEqual("Settings", matches[0]["label"])
        self.assertEqual("XCUIElementTypeButton", matches[0]["type"])

    def test_selector_and_locator(self):
        self.assertTrue(bridge.selector_matches(
            {"label": "Server is live", "name": "Server is live", "value": "Server is live", "type": "XCUIElementTypeStaticText"},
            {"text": "live"},
        ))
        self.assertFalse(bridge.selector_matches(
            {"label": "Other", "name": "", "value": "", "type": "XCUIElementTypeStaticText"},
            {"text": "live"},
        ))
        using, value = bridge.locator({"text": "Settings"})
        self.assertEqual("xpath", using)
        self.assertIn("Settings", value)

    def test_pointer_actions(self):
        actions = bridge.pointer_actions([(10, 20), (10, 20)], 50, 120)
        self.assertEqual("finger1", actions[0]["id"])
        down = [item for item in actions[0]["actions"] if item["type"] == "pointerDown"]
        self.assertEqual(2, len(down))
        swipe = bridge.swipe_actions((0, 0), (100, 100), 300)
        self.assertEqual("pointerMove", swipe[0]["actions"][2]["type"])

    def test_protocol(self):
        class FakeApp:
            base_url = "http://127.0.0.1:4723"
            udid = "UDID"
            bundle_id = "com.vlad.mcpserver"
            def source(self): return XML
            def screenshot(self): return "iVBORw0KGgo="
            def rect(self): return {"x": 0, "y": 0, "width": 402, "height": 874}
            def orientation(self): return "PORTRAIT"

        init = bridge.handle(FakeApp(), {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
        self.assertIn("protocolVersion", init["result"])
        listing = bridge.handle(FakeApp(), {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        names = [tool["name"] for tool in listing["result"]["tools"]]
        self.assertEqual(14, len(names))
        self.assertIn("ui_find", names)
        result = bridge.handle(FakeApp(), {
            "jsonrpc": "2.0", "id": 3, "method": "tools/call",
            "params": {"name": "ui_find", "arguments": {"text": "Settings"}},
        })
        text = result["result"]["content"][-1]["text"]
        self.assertEqual(1, json.loads(text)["count"])
        unknown = bridge.handle(FakeApp(), {"jsonrpc": "2.0", "id": 4, "method": "unknown"})
        self.assertEqual(-32601, unknown["error"]["code"])


class FakeServer:
    def __init__(self, failures=()):
        self.failures = list(failures)
        self.created = 0
        self.alive = set()
        self.deleted = []


class FakeAppium(bridge.Appium):
    def __init__(self, server, state_dir=None):
        super().__init__("http://appium", "udid-1", "com.example")
        self.server = server
        self.state_dir = state_dir

    def request(self, method, path, payload=None, timeout=60):
        server = self.server
        if method == "POST" and path == "/session":
            server.created += 1
            sid = f"s{server.created}"
            server.alive.add(sid)
            return {"value": {"sessionId": sid}}
        sid = path.split("/")[2]
        if method == "DELETE":
            server.deleted.append(sid)
            server.alive.discard(sid)
            return {}
        if sid not in server.alive:
            raise bridge.ToolFailure("Appium HTTP 404: invalid session id")
        if path.endswith("/timeouts"):
            return {"value": {}}
        if server.failures:
            raise bridge.ToolFailure(server.failures.pop(0))
        return {"value": path}


class SessionRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_stale_session_is_recreated_once(self):
        server = FakeServer(["Appium HTTP 404: ... Original error: Not authorized for performing UI testing actions."])
        app = FakeAppium(server)
        self.assertEqual("/session/s2/source", app.call("GET", "/source")["value"])
        self.assertEqual(2, server.created)
        self.assertEqual(["s1"], server.deleted)

    def test_other_errors_are_not_retried(self):
        app = FakeAppium(FakeServer(["Appium HTTP 404: An element could not be located"]))
        with self.assertRaises(bridge.ToolFailure):
            app.call("GET", "/element")
        self.assertEqual(1, app.server.created)

    def test_bridges_share_one_session_per_device(self):
        server = FakeServer()
        first = FakeAppium(server, self.tmp.name)
        second = FakeAppium(server, self.tmp.name)
        first.call("GET", "/source")
        second.call("GET", "/source")
        self.assertEqual(1, server.created)
        self.assertEqual("s1", second.session_id)

    def test_bridge_adopts_session_recreated_by_another(self):
        server = FakeServer()
        first = FakeAppium(server, self.tmp.name)
        second = FakeAppium(server, self.tmp.name)
        first.call("GET", "/source")
        second.call("GET", "/source")
        server.alive.discard("s1")
        first.call("GET", "/source")
        self.assertEqual("/session/s2/source", second.call("GET", "/source")["value"])
        self.assertEqual(2, server.created)

    def test_signing_capabilities(self):
        caps = bridge.signing_capabilities("TEAM", None, "com.x.WDA")
        self.assertEqual("TEAM", caps["appium:xcodeOrgId"])
        self.assertEqual("Apple Development", caps["appium:xcodeSigningId"])
        self.assertEqual("com.x.WDA", caps["appium:updatedWDABundleId"])
        self.assertEqual({}, bridge.signing_capabilities())


if __name__ == "__main__":
    unittest.main()
