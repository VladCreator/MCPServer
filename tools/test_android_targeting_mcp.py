#!/usr/bin/env python3
"""Opt-in P1 checks on the separate synthetic qaFixture app.

Requires TESTER_MCP_URL, TESTER_MCP_SESSION, and TESTER_FIXTURE_TESTS=1.
Install qaFixture first and explicitly approve unattended tool execution on the
disposable emulator. Never use these checks on a personal device.
"""

import os
import time
import unittest
from unittest.mock import patch

import test_android_agent_mcp as native


@unittest.skipUnless(os.environ.get("TESTER_FIXTURE_TESTS") == "1"
                     and os.environ.get("TESTER_MCP_URL") and os.environ.get("TESTER_MCP_SESSION"),
                     "Synthetic fixture checks not configured")
class AndroidTargetingTests(unittest.TestCase):
    call = native.AndroidAgentContractTests.call
    package = "com.vlad.mcpserver.fixture"

    def ok(self, tool, args):
        result = self.call(tool, args)
        self.assertFalse(result["isError"], result)
        return result

    def rejects(self, tool, args, reason):
        result = self.call(tool, args)
        self.assertTrue(result["isError"], result)
        self.assertEqual(reason, result["structuredContent"]["reason"], result)
        self.assertFalse(result["structuredContent"]["action_dispatched"])
        return result

    def setUp(self):
        self.ok("launch_app", {"package_name": self.package})
        # A previous scroll can hide the header without leaving the cards screen.
        self.ok("scroll", {"direction": "up"})
        self.idle()
        # Leave any previous fixture sub-screen without relying on process resets.
        for _ in range(4):
            visible = self.ok("ui_find", {"text": "Synthetic cards fixture", "text_exact": True})
            if visible["structuredContent"]["count"]:
                break
            self.ok("global_action", {"action": "back"})
            self.idle()
        else:
            self.fail("Fixture precondition: cards screen not available")
        self.idle()

    def idle(self):
        self.ok("ui_wait", {"condition": "idle", "package_name": self.package,
                            "quiet_ms": 400, "timeout_ms": 5000})
        time.sleep(.2)

    def assert_text(self, resource_id, expected, **selector):
        self.ok("ui_assert", {"condition": "text", "resource_id": resource_id,
                             "expected": expected, "package_name": self.package,
                             "timeout_ms": 1000, **selector})

    def test_scoped_actions_and_ambiguity_have_no_wrong_target_effects(self):
        self.ok("click_element", {"text": "Open", "text_exact": True,
                                 "within": {"resource_id": "project_beta"}})
        self.assert_text("status", "Opened Beta")
        for tool, args in (
            ("click_element", {"text": "Open", "text_exact": True}),
            ("input_text", {"resource_id": "field", "text": "must not enter"}),
            ("ui_gesture", {"type": "tap", "text": "Open", "text_exact": True}),
            ("scroll", {"direction": "down", "resource_id": "open"}),
        ):
            self.rejects(tool, args, "ambiguous_match")
        self.assert_text("status", "Opened Beta")
        self.assert_text("field", "Alpha input", within={"resource_id": "project_alpha"})
        self.assert_text("field", "Beta input", within={"resource_id": "project_beta"})
        self.ok("ui_gesture", {"type": "tap", "text": "Open", "text_exact": True,
                               "within": {"content_desc": "Project Alpha"}})
        self.assert_text("status", "Opened Alpha")

    def test_unicode_and_container_input(self):
        value = "café 日本語 😀"
        self.ok("input_text", {"text": value, "resource_id": "field",
                              "within": {"resource_id": "project_beta"}})
        self.assert_text("field", value, within={"resource_id": "project_beta"})
        # Restore synthetic data for repeatability.
        self.ok("input_text", {"text": "Beta input", "resource_id": "field",
                              "within": {"resource_id": "project_beta"}})

    def test_references_work_for_input_gesture_and_scroll(self):
        found = self.ok("ui_find", {"resource_id": "field",
                                  "within": {"resource_id": "project_beta"}})["structuredContent"]
        self.ok("input_text", {"element_ref": found["matches"][0]["element_ref"], "text": "Reference input"})
        self.assert_text("field", "Reference input", within={"resource_id": "project_beta"})
        self.ok("input_text", {"text": "Beta input", "resource_id": "field",
                              "within": {"resource_id": "project_beta"}})
        found = self.ok("ui_find", {"resource_id": "open",
                                  "within": {"resource_id": "project_beta"}})["structuredContent"]
        self.ok("ui_gesture", {"type": "tap", "element_ref": found["matches"][0]["element_ref"]})
        self.assert_text("status", "Opened Beta")
        self.idle()
        found = self.ok("ui_find", {"class_name": "ScrollView"})["structuredContent"]
        self.ok("scroll", {"direction": "down", "element_ref": found["matches"][0]["element_ref"]})
        self.ok("ui_assert", {"condition": "visible", "text": "End of synthetic list", "timeout_ms": 2000})

    def test_correlated_observation_and_reference_revalidation(self):
        for _ in range(3):
            observation = self.ok("observe_screen", {"include_screenshot": True, "max_dimension": 640})["structuredContent"]
            if not observation["unstable"]:
                break
            self.idle()
        self.assertFalse(observation["atomic"])
        self.assertFalse(observation["unstable"])
        self.assertEqual(self.package, observation["foreground_package"])
        self.assertLessEqual(observation["capture_started_at_ms"], observation["tree_captured_at_ms"])
        self.assertLessEqual(observation["tree_captured_at_ms"], observation["capture_finished_at_ms"])
        self.assertEqual("captured", observation["screenshot"]["status"])
        self.assertGreaterEqual(observation["screenshot"]["scale_x"], 1)
        found = self.ok("ui_find", {"resource_id": "open",
                                  "within": {"resource_id": "project_beta"}})["structuredContent"]
        ref = found["matches"][0]["element_ref"]
        # New observation by the same client invalidates its previous observation.
        self.rejects("click_element", {"element_ref": observation["nodes"][0]["element_ref"]}, "stale_reference")
        self.ok("click_element", {"element_ref": ref})
        self.assert_text("status", "Opened Beta")
        self.rejects("click_element", {"element_ref": ref}, "stale_reference")

    def test_app_switch_invalidates_references(self):
        found = self.ok("ui_find", {"text": "Open", "text_exact": True})["structuredContent"]
        ref = found["matches"][0]["element_ref"]
        self.ok("launch_app", {"package_name": "com.vlad.mcpserver"})
        self.rejects("click_element", {"element_ref": ref}, "stale_reference")

    @unittest.skipUnless(os.environ.get("TESTER_MCP_SECOND_SESSION"), "Second approved client not configured")
    def test_reference_is_not_transferable_between_sessions(self):
        found = self.ok("ui_find", {"resource_id": "open",
                                  "within": {"resource_id": "project_beta"}})["structuredContent"]
        ref = found["matches"][0]["element_ref"]
        with patch.dict(os.environ, {"TESTER_MCP_SESSION": os.environ["TESTER_MCP_SECOND_SESSION"]}):
            self.rejects("click_element", {"element_ref": ref}, "stale_reference")
        # A foreign client's rejection must not invalidate the owner's observation.
        self.ok("click_element", {"element_ref": ref})
        self.assert_text("status", "Opened Beta")

    @unittest.skipUnless(os.environ.get("TESTER_FIXTURE_SETTINGS_PROVISIONED") == "1",
                         "Disposable device settings permissions not provisioned")
    def test_rotation_invalidates_references_and_reports_real_rotation(self):
        before = self.ok("observe_screen", {})["structuredContent"]
        ref = next(n["element_ref"] for n in before["nodes"] if n.get("resource_id", "").endswith(":id/open"))
        try:
            desired = "landscape" if before["rotation"] in (0, 2) else "portrait"
            self.ok("device_state", {"action": "set", "rotation": desired})
            for _ in range(20):
                time.sleep(.2)
                after = self.ok("observe_screen", {})["structuredContent"]
                if after["rotation"] != before["rotation"]:
                    break
            self.assertNotEqual(before["rotation"], after["rotation"])
            self.rejects("click_element", {"element_ref": ref}, "stale_reference")
        finally:
            self.ok("device_state", {"action": "restore"})
            self.idle()

    def test_scoped_route_uses_same_native_resolution(self):
        source = f"""schema_version: 1
id: p1.scoped
platform: android
app_id: {self.package}
steps:
  - tap: {{resource_id: open, within: {{resource_id: project_beta}}}}
  - assert: {{condition: text, resource_id: status, expected: Opened Beta}}
"""
        self.ok("run_route", {"yaml": source})
        self.assert_text("status", "Opened Beta")

    def test_bounded_tree_only_observation_is_explicit(self):
        result = self.ok("observe_screen", {"max_nodes": 2})
        observation = result["structuredContent"]
        self.assertEqual(2, len(observation["nodes"]))
        self.assertTrue(observation["truncated"])
        self.assertEqual("not_requested", observation["screenshot"]["status"])
        self.assertFalse(any(c["type"] == "image" for c in result["content"]))
        self.assertTrue(all("path" in node and "node_index" in node for node in observation["nodes"]))

    def test_partial_visibility_is_explicit_and_not_actionable_by_default(self):
        found = self.ok("ui_find", {"resource_id": "clipped"})["structuredContent"]
        self.assertEqual(1, found["count"])
        node = found["matches"][0]
        self.assertTrue(node["viewport_intersecting"])
        self.assertFalse(node["fully_visible"])
        self.assertFalse(node["occlusion_known"])
        self.rejects("click_element", {"resource_id": "clipped"}, "not_actionable")
        self.rejects("ui_gesture", {"type": "tap", "resource_id": "clipped"}, "not_actionable")
        none = self.ok("ui_find", {"resource_id": "clipped", "visibility": "fully_visible"})
        self.assertEqual(0, none["structuredContent"]["count"])
        self.ok("click_element", {"resource_id": "clipped", "visibility": "intersecting"})
        self.assert_text("status", "Clipped clicked")

    def test_compose_merged_semantics_and_webview_are_reported(self):
        self.ok("click_element", {"text": "Show compose", "text_exact": True})
        self.idle()
        self.ok("click_element", {"content_desc": "Merged project"})
        self.ok("ui_assert", {"condition": "visible", "text": "Compose clicked", "timeout_ms": 1500})
        self.ok("input_text", {"content_desc": "Compose field", "text": "日本語 café"})
        self.ok("ui_assert", {"condition": "text", "class_name": "EditText",
                             "expected": "日本語 café", "timeout_ms": 1000})
        self.ok("global_action", {"action": "back"})
        self.idle()
        self.ok("click_element", {"text": "Show webview", "text_exact": True})
        self.idle()
        observation = self.ok("observe_screen", {})["structuredContent"]
        self.assertGreaterEqual(observation["webview"]["count"], 1)
        self.assertGreaterEqual(observation["webview"]["with_readable_accessibility"], 1)
        self.assertFalse(observation["webview"]["dom_available"])
        self.ok("ui_assert", {"condition": "visible", "text": "Accessible web content", "timeout_ms": 2000})

    def test_secure_screenshot_is_failure_not_empty_success(self):
        self.ok("click_element", {"text": "Show secure", "text_exact": True})
        self.idle()
        tree = self.ok("observe_screen", {})["structuredContent"]
        self.assertEqual("not_requested", tree["screenshot"]["status"])
        result = self.call("observe_screen", {"include_screenshot": True})
        self.assertTrue(result["isError"])
        self.assertEqual("failed", result["structuredContent"]["screenshot"]["status"])
        self.assertIn("protected", result["structuredContent"]["screenshot"]["error"])
        self.assertFalse(any(c["type"] == "image" for c in result["content"]))


if __name__ == "__main__":
    unittest.main()
