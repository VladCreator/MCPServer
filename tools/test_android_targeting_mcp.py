#!/usr/bin/env python3
"""Opt-in P1 checks on the separate synthetic qaFixture app.

Requires TESTER_MCP_URL, TESTER_MCP_SESSION, and TESTER_FIXTURE_TESTS=1.
Install qaFixture first and explicitly approve unattended tool execution on the
disposable emulator. Never use these checks on a personal device.
"""

import os
import time
import unittest
import secrets
import json
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
        self.idle()
        # Leave any previous fixture sub-screen without relying on process resets.
        for _ in range(4):
            scrolled = self.call("scroll", {"direction": "up"})
            if scrolled["isError"]:
                self.assertEqual("no_scroll_target", scrolled.get("structuredContent", {}).get("reason"), scrolled)
            self.idle()
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
        self.idle()
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
        state_key = secrets.token_urlsafe(32)
        before = self.ok("observe_screen", {})["structuredContent"]
        ref = next(n["element_ref"] for n in before["nodes"] if n.get("resource_id", "").endswith(":id/open"))
        try:
            desired = "landscape" if before["rotation"] in (0, 2) else "portrait"
            self.ok("device_state", {"action": "set", "rotation": desired, "state_key": state_key})
            for _ in range(20):
                time.sleep(.2)
                after = self.ok("observe_screen", {})["structuredContent"]
                if after["rotation"] != before["rotation"]:
                    break
            self.assertNotEqual(before["rotation"], after["rotation"])
            self.rejects("click_element", {"element_ref": ref}, "stale_reference")
        finally:
            self.ok("device_state", {"action": "restore", "state_key": state_key})
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
        result = self.ok("observe_screen", {"max_nodes": 2, "detail": "full"})
        observation = result["structuredContent"]
        self.assertEqual(2, len(observation["nodes"]))
        self.assertTrue(observation["truncated"])
        self.assertEqual("not_requested", observation["screenshot"]["status"])
        self.assertFalse(any(c["type"] == "image" for c in result["content"]))
        self.assertTrue(all("path" in node and "node_index" in node for node in observation["nodes"]))

    def test_partial_visibility_is_explicit_and_not_actionable_by_default(self):
        found = self.ok("ui_find", {"resource_id": "clipped", "detail": "full"})["structuredContent"]
        self.assertEqual(1, found["count"])
        node = found["matches"][0]
        self.assertTrue(node["viewport_intersecting"])
        self.assertFalse(node["fully_visible"])
        self.assertFalse(node["occlusion_known"])
        self.rejects("click_element", {"resource_id": "clipped"}, "reported_clipping")
        self.rejects("ui_gesture", {"type": "tap", "resource_id": "clipped"}, "reported_clipping")
        none = self.ok("ui_find", {"resource_id": "clipped", "visibility": "fully_visible"})
        self.assertEqual(0, none["structuredContent"]["count"])
        self.ok("click_element", {"resource_id": "clipped", "visibility": "intersecting"})
        self.assert_text("status", "Clipped clicked")

    def test_compact_output_is_bounded_and_not_duplicated(self):
        self.ok("qa_session", {"action": "begin", "test_case": "Compact synthetic output"})
        try:
            for tool, args, budget in (
                ("ui_find", {"resource_id": "missing_synthetic_id", "limit": 1}, 2048),
                ("observe_screen", {"max_nodes": 80}, 24 * 1024),
                ("observe_screen", {"max_nodes": 80, "detail": "full"}, 64 * 1024),
            ):
                result = self.ok(tool, args)
                wire = json.dumps({"jsonrpc": "2.0", "id": 9223372036854775807,
                                   "result": result}, ensure_ascii=False, separators=(",", ":")).encode()
                self.assertLessEqual(len(wire), budget)
                self.assertIn("flow_id", result["structuredContent"])
                self.assertFalse(any(c.get("text", "").startswith("{") for c in result["content"]))
        finally:
            self.ok("qa_session", {"action": "end", "result": "PASS"})

    def reliability_screen(self, name, settle=True):
        self.ok("scroll", {"direction": "down"})
        self.ok("click_element", {"text": "Show reliability", "text_exact": True})
        self.idle()
        self.ok("click_element", {"text": f"Show {name}", "text_exact": True})
        if settle:
            self.idle()

    def test_cancel_eligibility_and_disabled_rejection(self):
        self.reliability_screen("search")
        found = self.ok("ui_find", {"resource_id": "search_cancel", "visibility": "fully_visible"})
        node = found["structuredContent"]["matches"][0]
        self.assertTrue(node["actionable"])
        self.ok("click_element", {"element_ref": node["element_ref"]})
        self.assert_text("status", "My projects")
        rejected = self.rejects("click_element", {"resource_id": "disabled_cancel"}, "disabled")
        self.assertIn("disabled", rejected["structuredContent"]["failed_checks"])
        self.assert_text("status", "My projects")

    def test_drawer_scroll_reports_target_and_observed_movement(self):
        self.reliability_screen("drawer")
        home = self.ok("ui_find", {"resource_id": "home_anchor"})["structuredContent"]["matches"][0]["bounds"]
        self.idle()
        boundary = self.call("scroll", {"direction": "up", "resource_id": "drawer", "verify_movement": True})
        self.assertTrue(boundary["isError"], boundary)
        self.assertTrue(boundary["structuredContent"]["action_dispatched"])
        self.assertEqual("not_observed", boundary["structuredContent"]["movement_status"])
        self.idle()
        for method in ("auto", "gesture"):
            result = self.ok("scroll", {"direction": "down", "resource_id": "drawer",
                                      "method": method, "verify_movement": True})["structuredContent"]
            self.assertEqual("observed", result["movement_status"])
            self.assertTrue(result["target"]["resource_id"].endswith(":id/drawer"))
            self.assertTrue(result["action_dispatched"])
            self.idle()
            after = self.ok("ui_find", {"resource_id": "home_anchor"})["structuredContent"]["matches"][0]["bounds"]
            self.assertEqual(home, after)

    def test_transient_tree_recovers_without_an_image(self):
        self.reliability_screen("delayed")
        self.ok("click_element", {"text": "Empty then populate", "text_exact": True})
        self.ok("ui_wait", {"condition": "absent", "text": "Empty then populate",
                           "text_exact": True, "package_name": self.package, "timeout_ms": 1000})
        result = self.ok("get_screen_tree", {"max_nodes": 80})
        self.assertEqual("available", result["structuredContent"]["state"])
        self.assertLessEqual(result["structuredContent"]["attempts"], 3)
        self.assertIn("Delayed readable content", result["content"][-1]["text"])
        self.assertFalse(any(c["type"] == "image" for c in result["content"]))

    def test_route_scroll_uses_the_same_target_verification(self):
        self.reliability_screen("drawer")
        home = self.ok("ui_find", {"resource_id": "home_anchor"})["structuredContent"]["matches"][0]["bounds"]
        self.idle()
        self.ok("run_route", {"yaml": f"""schema_version: 1
id: reliability.drawer
platform: android
app_id: {self.package}
steps:
  - scroll: {{direction: down, resource_id: drawer, method: gesture, verify_movement: true}}
"""})
        self.idle()
        after = self.ok("ui_find", {"resource_id": "home_anchor"})["structuredContent"]["matches"][0]["bounds"]
        self.assertEqual(home, after)

    def test_custom_labels_and_dynamic_shortcuts_require_fresh_discovery(self):
        self.reliability_screen("dynamic")
        self.assert_text("custom_title", "Log Information")
        self.assert_text("shortcut", "SUBMITTALS")
        self.ok("click_element", {"text": "Refresh shortcuts", "text_exact": True})
        self.assert_text("shortcut", "TEAM MEMBERS")
        wrong = self.call("ui_wait", {"condition": "text", "resource_id": "custom_title",
                                     "expected": "Log Contents", "timeout_ms": 250})
        self.assertTrue(wrong["isError"])
        self.assertEqual("text_mismatch", wrong["structuredContent"]["reason"])

    def test_back_dispatch_is_verified_against_actual_destination(self):
        self.reliability_screen("viewer")
        self.ok("click_element", {"text": "View Properties", "text_exact": True})
        self.idle()
        result = self.ok("global_action", {"action": "back"})["structuredContent"]
        self.assertEqual(1, result["dispatched_count"])
        self.assertEqual("not_checked", result["destination_status"])
        self.ok("wait_for_state", {"all": [
            {"condition": "text", "resource_id": "status", "expected": "Synthetic viewer"}
        ], "package_name": self.package, "timeout_ms": 2000})

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
