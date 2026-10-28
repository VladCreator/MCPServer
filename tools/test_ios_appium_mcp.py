#!/usr/bin/env python3
"""Unit tests for the Appium/XCUITest bridge helpers."""

import importlib.util
import json
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


if __name__ == "__main__":
    unittest.main()
