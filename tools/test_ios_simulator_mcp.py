import base64
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "bridge", Path(__file__).with_name("ios_simulator_mcp.py")
)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)
UDID = "00000000-0000-0000-0000-000000000001"


def call(method, params=None):
    return bridge.handle(
        {"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}, UDID
    )


class SimulatorBridgeTests(unittest.TestCase):
    def test_discovery(self):
        self.assertIn("tools", call("initialize")["result"]["capabilities"])
        self.assertEqual(call("tools/list")["result"]["tools"], [bridge.SCREENSHOT_TOOL])

    def test_notifications_and_invalid_requests(self):
        self.assertIsNone(bridge.handle(
            {"jsonrpc": "2.0", "method": "notifications/initialized"}, UDID
        ))
        self.assertEqual(bridge.handle([], UDID)["error"]["code"], -32600)
        self.assertEqual(call("unknown")["error"]["code"], -32601)
        self.assertEqual(call("tools/call", {"name": "unknown"})["error"]["code"], -32602)
        self.assertEqual(call("tools/call", {
            "name": "take_screenshot", "arguments": {"unexpected": True},
        })["error"]["code"], -32602)

    def test_screenshot_and_cleanup(self):
        png = b"\x89PNG\r\n\x1a\nimage"
        paths = []

        def capture(command, **kwargs):
            self.assertEqual(command[:5], ["xcrun", "simctl", "io", UDID, "screenshot"])
            paths.append(Path(command[-1]))
            paths[-1].write_bytes(png)

        with patch.object(bridge.subprocess, "run", side_effect=capture):
            result = call("tools/call", {"name": "take_screenshot"})["result"]
        self.assertFalse(result["isError"])
        self.assertEqual(base64.b64decode(result["content"][1]["data"]), png)
        self.assertIn(UDID, result["content"][0]["text"])
        self.assertIn("Captured at:", result["content"][0]["text"])
        self.assertFalse(paths[0].exists())

    def test_capture_failure_is_visible(self):
        with patch.object(bridge.subprocess, "run", side_effect=subprocess.CalledProcessError(
            1, "xcrun", stderr="Device is not booted",
        )), contextlib.redirect_stderr(io.StringIO()):
            result = call("tools/call", {"name": "take_screenshot"})["result"]
        self.assertTrue(result["isError"])
        self.assertIn("Device is not booted", result["content"][0]["text"])

    def test_invalid_image_is_rejected(self):
        with patch.object(bridge.subprocess, "run"), patch.object(
            bridge.Path, "read_bytes", return_value=b"not an image"
        ), contextlib.redirect_stderr(io.StringIO()):
            result = call("tools/call", {"name": "take_screenshot"})["result"]
        self.assertTrue(result["isError"])


if __name__ == "__main__":
    unittest.main()
