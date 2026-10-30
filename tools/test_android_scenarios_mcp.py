#!/usr/bin/env python3
"""Opt-in P4 native catalog acceptance on an explicitly provisioned disposable fixture.

Provisioning/approval is external. TESTER_SCENARIO_POLICIES maps route IDs to
already dashboard-approved exact-plan policies (30 attempts each). This suite
does not install, grant permissions, change confirmation settings or approve tools.
"""

import json
import os
import secrets
import statistics
import time
import unittest
import xml.etree.ElementTree as ET

import test_android_agent_mcp as native


@unittest.skipUnless(os.environ.get("TESTER_FIXTURE_TESTS") == "1"
                     and os.environ.get("TESTER_SCENARIO_POLICIES"),
                     "Explicitly approved P4 catalog policies not configured")
class AndroidScenarioTests(unittest.TestCase):
    call = native.AndroidAgentContractTests.call

    def ok(self, name, args):
        result = self.call(name, args)
        self.assertFalse(result["isError"], f"Native tool {name} failed; inspect redacted run status")
        return result["structuredContent"]

    def test_catalog_requires_exact_version_and_digest_before_execution(self):
        entry = self.ok("list_routes", {})["routes"][0]
        self.assertTrue(self.call("get_route", {"route_id": entry["route_id"], "version": "latest"})["isError"])
        self.assertTrue(self.call("run_route", {
            "route_id": entry["route_id"], "version": entry["version"], "digest": "0" * 64,
        })["isError"])

    def test_invalid_late_cleanup_is_rejected_before_any_setup(self):
        result = self.call("run_route", {"scenario": {
            "schema_version": 2, "id": "fixture.invalid", "version": "1.0.0", "platform": "android",
            "app_id": "com.vlad.mcpserver.fixture",
            "setup": [{"device_state": {"keep_awake": True}}],
            "steps": [{"tap": {"resource_id": "open"}}],
            "cleanup": [{"shell": {"command": "unsupported"}}],
        }})
        self.assertTrue(result["isError"])

    def test_policy_rejects_different_parameters_and_privacy_without_consuming_attempts(self):
        policies = json.loads(os.environ["TESTER_SCENARIO_POLICIES"])
        entry = next(d for d in self.ok("list_routes", {})["routes"] if d["route_id"] == "fixture.unicode_form")
        exact = {key: entry[key] for key in ("route_id", "version", "digest")}
        policy_id = policies[entry["route_id"]]
        before = self.ok("route_policy", {"action": "status", "policy_id": policy_id})["remaining_runs"]
        for override in ({"parameters": {"value": "different"}}, {"privacy_sensitive": False}):
            rejected = self.call("run_route", {
                **exact, "policy_id": policy_id, "run_key": secrets.token_urlsafe(32),
                "submission_id": secrets.token_hex(12), **override,
            })
            self.assertTrue(rejected["isError"])
            self.assertEqual("rejected", rejected["structuredContent"]["status"])
        after = self.ok("route_policy", {"action": "status", "policy_id": policy_id})["remaining_runs"]
        self.assertEqual(before, after)

    def test_three_catalog_scenarios_replay_thirty_times_with_postconditions_and_cleanup(self):
        policies = json.loads(os.environ["TESTER_SCENARIO_POLICIES"])
        routes = self.ok("list_routes", {})["routes"]
        self.assertEqual(3, len(routes))
        timings = {}
        for descriptor in routes:
            route_id = descriptor["route_id"]
            with self.subTest(route=route_id):
                exact = {key: descriptor[key] for key in ("route_id", "version", "digest")}
                definition = self.ok("get_route", {k: exact[k] for k in ("route_id", "version")})
                self.assertEqual(descriptor["digest"], definition["digest"])
                durations, jitter = [], []
                last_owner = None
                for attempt in range(30):
                    key = secrets.token_urlsafe(32)
                    result = self.ok("run_route", {
                        **exact, "mode": "sync", "run_key": key,
                        "submission_id": f"p4-{route_id}-{attempt}-{secrets.token_hex(4)}",
                        "policy_id": policies[route_id],
                    })
                    self.assertEqual("completed", result["status"])
                    self.assertEqual("completed", result["primary_status"])
                    self.assertEqual("completed", result["cleanup_status"])
                    self.assertFalse(result["pending_cleanup"])
                    self.assertFalse(result["pending_state_restore"])
                    self.assertTrue(all(step["status"] == "completed" for step in result["steps"]))
                    self.assertTrue(any(step["phase"] == "postconditions" for step in result["steps"]))
                    self.assertEqual("restore_state", result["steps"][-1]["tool"])
                    owner = {"run_id": result["run_id"], "run_key": key}
                    canonical = self.ok("get_run", owner)
                    self.assertEqual([step["id"] for step in result["steps"]],
                                     [step["id"] for step in canonical["steps"]])
                    self.assertNotIn(key, json.dumps(canonical))
                    for step in canonical["steps"]:
                        if step["tool"] == "delay":
                            jitter.append(step["duration_ms"] - step["requested_delay_ms"])
                    durations.append(result["duration_ms"])
                    last_owner = owner
                status = self.ok("route_policy", {"action": "status", "policy_id": policies[route_id]})
                self.assertEqual(0, status["remaining_runs"])
                self.assertFalse(status["active"])
                export = self.ok("export_run", {**last_owner, "format": "junit"})
                root = ET.fromstring(export["content"])
                self.assertEqual("0", root.attrib["failures"])
                self.assertEqual(last_owner["run_id"], root.attrib["id"])
                timings[route_id] = {
                    "attempts": len(durations), "median_ms": statistics.median(durations),
                    "p95_ms": sorted(durations)[28], "delay_jitter_ms": jitter,
                }
        # Measured synthetic timings only, never response payloads or recovery keys.
        print("P4_NATIVE_METRICS " + json.dumps(timings, sort_keys=True))


if __name__ == "__main__":
    unittest.main()
