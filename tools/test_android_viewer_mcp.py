#!/usr/bin/env python3
"""Opt-in Viewer contracts against one already accepted native run.

Requires TESTER_VIEWER_STATE (private CI recovery file), TESTER_MCP_URL and an
approved TESTER_MCP_SESSION. Never submits actions, captures or approves tools.
"""

import json
import os
import threading
import unittest
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

from tester_ci import Client, load_state
from tester_viewer import Server, Viewer


@unittest.skipUnless(all(os.environ.get(key) for key in
                         ("TESTER_VIEWER_STATE", "TESTER_MCP_URL", "TESTER_MCP_SESSION")),
                     "Existing owned native Viewer run/session not configured")
class AndroidViewerTests(unittest.TestCase):
    def setUp(self):
        state = load_state(Path(os.environ["TESTER_VIEWER_STATE"]))
        self.owner = {key: state[key] for key in ("run_id", "run_key")}
        self.client = Client(os.environ["TESTER_MCP_URL"], os.environ["TESTER_MCP_SESSION"],
                             os.environ.get("TESTER_MCP_TOKEN"))
        self.viewer = Viewer(self.client, self.owner)
        self.auth = "Bearer " + self.viewer.token
        self.server = Server(self.viewer)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": .01})
        self.thread.start()
        self.addCleanup(self.close)

    def close(self):
        self.viewer.revoke()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def request(self, path, method="GET", auth=True):
        headers = {"Authorization": self.auth} if auth else {}
        try:
            with urllib.request.urlopen(urllib.request.Request(
                    self.server.origin + path, method=method, headers=headers), timeout=90) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def test_native_timeline_remains_canonical_and_keys_stay_host_side(self):
        self.assertEqual(401, self.request("/api/run", auth=False)[0])
        status, body = self.request("/api/run")
        self.assertEqual(200, status)
        run = json.loads(body)
        self.assertEqual(self.owner["run_id"], run["run_id"])
        self.assertTrue(run["content_redacted"])
        self.assertNotIn(self.owner["run_key"].encode(), body)
        self.assertNotIn(self.viewer.token.encode(), body)
        self.assertTrue(all("id" in step and "status" in step for step in run["steps"]))

    def test_native_json_timeline_and_junit_downloads(self):
        for format in ("json", "markdown", "junit"):
            status, body = self.request("/api/export?format=" + format)
            self.assertEqual(200, status)
            self.assertNotIn(self.owner["run_key"].encode(), body)
            if format == "json":
                self.assertEqual(self.owner["run_id"], json.loads(body)["run_id"])
            elif format == "junit":
                self.assertEqual(self.owner["run_id"], ET.fromstring(body).attrib["id"])

    def test_revocation_disables_viewer_without_revoking_native_run(self):
        self.assertEqual(200, self.request("/api/run")[0])
        self.assertEqual(200, self.request("/api/revoke", method="POST")[0])
        self.assertEqual(401, self.request("/api/run")[0])
        self.assertEqual(401, self.request("/api/export?format=json")[0])
        run = self.client.call("get_run", self.owner, allow_error=True)
        self.assertEqual(self.owner["run_id"], run["run_id"])


if __name__ == "__main__":
    unittest.main()
