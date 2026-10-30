#!/usr/bin/env python3
"""Loopback Viewer HTTP, native ownership, expiry and revocation contracts."""

import base64
import http.client
import json
import threading
import unittest
import urllib.request
from unittest.mock import patch

import tester_viewer as viewer
from tester_ci import Client, ClientError

RUN_ID = "12345678-1234-1234-1234-123456789abc"
ARTIFACT = "abcdef01-1234-1234-1234-123456789abc"
OWNER = {"run_id": RUN_ID, "run_key": "s" * 43}


class Native:
    def __init__(self):
        self.calls = []
        self.run = {"run_id": RUN_ID, "content_redacted": True, "status": "completed", "steps": [
            {"id": "step_1", "tool": "ui_assert", "phase": "steps", "status": "completed"}]}
        self.artifact = {"content": [{"type": "text", "text": "owned artifact"}], "isError": False}

    def call(self, tool, args, allow_error=False):
        self.calls.append((tool, args))
        if tool == "get_run":
            return dict(self.run)
        if tool == "export_run":
            return {"run_id": RUN_ID, "content_redacted": True, "content": "native canonical " + args["format"]}
        raise AssertionError("Viewer must not execute UI, capture, cancel or arbitrary tools")

    def rpc(self, method, params):
        self.calls.append((params["name"], params["arguments"]))
        assert method == "tools/call" and params["name"] == "get_artifact"
        return self.artifact


class ViewerTests(unittest.TestCase):
    def setUp(self):
        self.native = Native()
        self.now = 0
        self.viewer = viewer.Viewer(self.native, OWNER, ttl=10, clock=lambda: self.now)
        self.server = viewer.Server(self.viewer)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": .01})
        self.thread.start()
        self.addCleanup(self.close)
        self.auth = "Bearer " + self.viewer.token

    def close(self):
        self.viewer.revoke()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def request(self, path, method="GET", headers=None, auth=True):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        self.addCleanup(connection.close)
        h = {"Authorization": self.auth} if auth else {}
        h.update(headers or {})
        connection.request(method, path, headers=h)
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read()

    def test_listener_is_ipv4_loopback_and_shell_never_contains_keys(self):
        self.assertEqual("127.0.0.1", self.server.server_address[0])
        for path in ("/", "/viewer.js", "/viewer.css"):
            status, headers, body = self.request(path, auth=False)
            self.assertEqual(200, status)
            self.assertNotIn(OWNER["run_key"].encode(), body)
            self.assertNotIn(self.viewer.token.encode(), body)
            self.assertEqual("no-store", headers["Cache-Control"])
            self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
        self.assertEqual([], self.native.calls)

    def test_authentication_required_on_every_native_endpoint(self):
        for path in ("/api/run", "/api/export?format=json", "/api/artifact?id=" + ARTIFACT):
            self.assertEqual(401, self.request(path, auth=False)[0])
            self.assertEqual(401, self.request(path, headers={"Authorization": "Bearer wrong"})[0])
        self.assertEqual([], self.native.calls)

    def test_wrong_host_cross_origin_fetch_and_request_bodies_fail_before_native(self):
        for headers in ({"Host": "evil.example"}, {"Host": "localhost:" + str(self.server.server_port)},
                        {"Origin": "https://evil.example"}, {"Sec-Fetch-Site": "cross-site"},
                        {"Transfer-Encoding": "chunked"}, {"Content-Length": "2"}):
            self.assertEqual(403, self.request("/api/run", headers=headers)[0])
        self.assertEqual([], self.native.calls)
        self.assertEqual(200, self.request("/api/run", headers={"Origin": self.server.origin})[0])

    def test_run_uses_only_retained_owner_and_returns_native_timeline(self):
        status, _, body = self.request("/api/run")
        self.assertEqual(200, status)
        self.assertEqual(self.native.run, json.loads(body))
        self.assertEqual([("get_run", OWNER)], self.native.calls)
        self.assertNotIn(OWNER["run_key"].encode(), body)
        self.assertNotIn(self.viewer.token.encode(), body)

    def test_query_cannot_override_owner_or_invoke_capture(self):
        for path in ("/api/run?run_key=other", "/api/run?run_id=other", "/api/take_screenshot",
                     "/api/export?format=html", "/api/artifact?id=../../secret",
                     "/api/export?format=json&format=junit", "/../../tester_ci.py",
                     "/api/export?format=json&run_key=other"):
            self.assertEqual(404, self.request(path)[0])
        self.assertEqual([], self.native.calls)
        self.assertEqual(405, self.request("/api/run", method="POST")[0])

    def test_native_ownership_denial_is_not_success_shaped_or_leaked(self):
        self.native.call = lambda *a, **k: {"reason": "run_unavailable", "secret": "NATIVE_PRIVATE"}
        status, _, body = self.request("/api/run")
        self.assertEqual(502, status)
        self.assertNotIn(b"NATIVE_PRIVATE", body)

    def test_exports_are_native_canonical_downloads_and_cached_per_snapshot(self):
        for format in viewer.FORMATS:
            for _ in range(2):
                status, headers, body = self.request("/api/export?format=" + format)
                self.assertEqual(200, status)
                self.assertEqual(("native canonical " + format).encode(), body)
                self.assertIn(viewer.FORMATS[format][1], headers["Content-Disposition"])
        self.assertEqual(3, sum(tool == "export_run" for tool, _ in self.native.calls))
        self.native.run["status"] = "failed"
        self.request("/api/export?format=json")
        self.assertEqual(4, sum(tool == "export_run" for tool, _ in self.native.calls))
        self.assertEqual(3, len(self.viewer.cache))

    def test_existing_text_artifact_requires_native_consent_and_same_owner(self):
        status, headers, body = self.request("/api/artifact?id=" + ARTIFACT)
        self.assertEqual((200, b"owned artifact"), (status, body))
        self.assertEqual("text/plain; charset=utf-8", headers["Content-Type"])
        self.assertEqual(("get_artifact", {**OWNER, "artifact_id": ARTIFACT}), self.native.calls[-1])
        self.native.artifact = {"isError": True, "content": [{"type": "text", "text": "PRIVATE denied"}]}
        status, _, body = self.request("/api/artifact?id=" + ARTIFACT)
        self.assertEqual(502, status)
        self.assertNotIn(b"PRIVATE", body)

    def test_existing_image_download_has_no_capture_or_html_interpretation(self):
        jpeg = b"\xff\xd8synthetic\xff\xd9"
        self.native.artifact = {"content": [
            {"type": "image", "mimeType": "image/jpeg", "data": base64.b64encode(jpeg).decode()},
            {"type": "text", "text": "Unmasked image explicitly captured for owned run."}]}
        status, headers, body = self.request("/api/artifact?id=" + ARTIFACT)
        self.assertEqual((200, jpeg), (status, body))
        self.assertEqual("image/jpeg", headers["Content-Type"])
        self.assertIn("attachment", headers["Content-Disposition"])
        self.native.artifact = {"content": [{"type": "text", "text": "<script>unsafe()</script>"}]}
        _, headers, _ = self.request("/api/artifact?id=" + ARTIFACT)
        self.assertTrue(headers["Content-Type"].startswith("text/plain"))

    def test_expiry_blocks_all_native_reads(self):
        self.now = 10
        self.assertEqual(401, self.request("/api/run")[0])
        self.assertEqual([], self.native.calls)

    def test_revoke_clears_cache_and_irrevocably_disables_current_token(self):
        self.request("/api/export?format=json")
        status, _, body = self.request("/api/revoke", method="POST")
        self.assertEqual(200, status)
        self.assertTrue(json.loads(body)["revoked"])
        self.assertEqual({}, self.viewer.cache)
        self.assertEqual("", self.viewer.token)
        for path in ("/api/run", "/api/export?format=json", "/api/artifact?id=" + ARTIFACT):
            self.assertEqual(401, self.request(path)[0])

    def test_get_cannot_revoke_and_stop_cannot_cancel_native_run(self):
        self.assertEqual(404, self.request("/api/revoke")[0])
        self.assertEqual(200, self.request("/api/stop", method="POST")[0])
        self.thread.join(2)
        self.assertFalse(self.thread.is_alive())
        self.assertTrue(self.viewer.revoked)
        self.assertEqual([], self.native.calls)

    def test_revoked_inflight_upstream_response_is_discarded(self):
        begun, release = threading.Event(), threading.Event()
        original = self.native.call

        def delayed(*a, **k):
            begun.set()
            release.wait(3)
            return original(*a, **k)

        self.native.call = delayed
        results = []
        thread = threading.Thread(target=lambda: results.append(self.request("/api/run")))
        thread.start()
        self.assertTrue(begun.wait(2))
        self.assertEqual(200, self.request("/api/revoke", method="POST")[0])
        release.set()
        thread.join(3)
        self.assertEqual(401, results[0][0])
        self.assertNotIn(RUN_ID.encode(), results[0][2])

    def test_invalid_export_and_oversized_artifact_fail_explicitly(self):
        original = self.native.call

        def invalid(tool, args, **kw):
            if tool == "export_run":
                return {"run_id": "wrong", "content_redacted": True, "content": "PRIVATE"}
            return original(tool, args, **kw)

        self.native.call = invalid
        self.assertEqual(502, self.request("/api/export?format=json")[0])
        self.native.artifact = {"content": [{"type": "text", "text": "x" * (2 * 1024 * 1024 + 1)}]}
        self.assertEqual(502, self.request("/api/artifact?id=" + ARTIFACT)[0])

    def test_client_side_has_no_url_tokens_storage_or_html_injection(self):
        source = (viewer.ROOT / "viewer.js").read_text()
        for forbidden in ("innerHTML", "localStorage", "sessionStorage", "document.cookie", "location.hash",
                          "take_screenshot", "observe_screen", "collect_failure_bundle"):
            self.assertNotIn(forbidden, source)
        self.assertIn("textContent", source)
        self.assertIn('credentials: "omit"', source)


class NativeHttpTests(unittest.TestCase):
    def test_viewer_reuses_real_mcp_http_shape_with_private_run_owner(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        received = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                received.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"jsonrpc": "2.0", "id": received[-1]["id"], "result": {
                    "isError": False, "structuredContent": {
                        "run_id": RUN_ID, "content_redacted": True, "status": "completed", "steps": []}}}).encode())

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever)
            thread.start()
            try:
                client = Client(f"http://127.0.0.1:{server.server_port}/mcp", session="approved-session")
                v = viewer.Viewer(client, OWNER)
                payload = v.fetch("Bearer " + v.token, "run")
                self.assertEqual(RUN_ID, json.loads(payload[1])["run_id"])
                self.assertEqual({"name": "get_run", "arguments": OWNER}, received[0]["params"])
            finally:
                server.shutdown()
                thread.join(2)


if __name__ == "__main__":
    unittest.main()
