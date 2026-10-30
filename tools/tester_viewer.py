#!/usr/bin/env python3
"""Authenticated loopback Viewer for one owned native run. No capture or UI execution."""

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from tester_ci import Client, ClientError, load_state

ROOT = Path(__file__).with_name("viewer")
SAFE_ID = re.compile(r"[a-f0-9-]{36}")
FORMATS = {"json": ("application/json", "run.json"),
           "markdown": ("text/markdown", "timeline.md"), "junit": ("application/xml", "junit.xml")}


class Viewer:
    def __init__(self, client, owner, ttl=600, clock=time.monotonic):
        if not SAFE_ID.fullmatch(owner.get("run_id", "")) or not re.fullmatch(
                r"[A-Za-z0-9_-]{32,128}", owner.get("run_key", "")):
            raise ClientError("Viewer requires a retained native run ID and valid recovery key.")
        if not 1 <= ttl <= 900:
            raise ClientError("Viewer lifetime must be 1-900 seconds.")
        self.client, self.owner = client, dict(owner)
        self.token = secrets.token_urlsafe(32)
        self.clock, self.expires = clock, clock() + ttl
        self.lock, self.upstream = threading.Lock(), threading.Lock()
        self.revoked = False
        self.cache = {}

    def authorized(self, value):
        with self.lock:
            return (not self.revoked and self.clock() < self.expires
                    and hmac.compare_digest(value, "Bearer " + self.token))

    def revoke(self):
        with self.lock:
            self.revoked = True
            self.token = ""
            self.cache.clear()

    def fetch(self, authorization, kind, value=None):
        # Serialize requests to the native session; recheck after queueing and
        # after response so a revoked in-flight operation cannot disclose data.
        with self.upstream:
            if not self.authorized(authorization):
                raise PermissionError
            if kind == "run":
                data = self.client.call("get_run", self.owner, allow_error=True)
                if data.get("run_id") != self.owner["run_id"] or data.get("content_redacted") is not True:
                    raise ClientError("Native owned redacted run unavailable.")
                payload = ("application/json", json.dumps(data).encode(), None)
            elif kind == "export":
                run = self.client.call("get_run", self.owner, allow_error=True)
                if run.get("run_id") != self.owner["run_id"] or run.get("content_redacted") is not True:
                    raise ClientError("Native owned redacted run unavailable.")
                digest = hashlib.sha256(json.dumps(run, sort_keys=True).encode()).hexdigest()
                key = (value, digest)
                payload = self.cache.get(key)
                if payload is None:
                    data = self.client.call("export_run", {**self.owner, "format": value})
                    if data.get("run_id") != self.owner["run_id"] or data.get("content_redacted") is not True:
                        raise ClientError("Canonical native export unavailable.")
                    content = data.get("content")
                    if not isinstance(content, str) or len(content.encode()) > 2 * 1024 * 1024:
                        raise ClientError("Invalid bounded native export.")
                    mime, filename = FORMATS[value]
                    payload = (mime, content.encode(), filename)
                    # Keep at most one snapshot per format, never a polling archive.
                    with self.lock:
                        if self.revoked:
                            raise PermissionError
                        self.cache = {k: v for k, v in self.cache.items() if k[0] != value}
                        self.cache[key] = payload
            elif kind == "artifact":
                result = self.client.rpc("tools/call", {"name": "get_artifact", "arguments": {
                    **self.owner, "artifact_id": value}})
                if result.get("isError"):
                    raise ClientError("Native artifact unavailable or consent denied.")
                blocks = result.get("content")
                if not isinstance(blocks, list) or not 1 <= len(blocks) <= 2:
                    raise ClientError("Unsupported native artifact response.")
                images = [block for block in blocks if block.get("type") == "image"]
                if len(images) > 1:
                    raise ClientError("Unsupported native artifact response.")
                block = images[0] if images else blocks[0]
                if block.get("type") == "text" and isinstance(block.get("text"), str):
                    payload = ("text/plain", block["text"].encode(), "artifact.txt")
                elif block.get("type") == "image" and block.get("mimeType") == "image/jpeg":
                    try:
                        image = base64.b64decode(block["data"], validate=True)
                    except (ValueError, KeyError, TypeError) as error:
                        raise ClientError("Invalid native image artifact.") from error
                    if not image.startswith(b"\xff\xd8"):
                        raise ClientError("Invalid native JPEG artifact.")
                    payload = ("image/jpeg", image, "artifact.jpg")
                else:
                    raise ClientError("Unsupported native artifact type.")
                if len(payload[1]) > 2 * 1024 * 1024:
                    raise ClientError("Native artifact exceeded size limit.")
            else:
                raise ClientError("Unsupported Viewer operation.")
            if not self.authorized(authorization):
                raise PermissionError
            return payload


class Server(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False
    request_queue_size = 4

    def __init__(self, viewer, port=0):
        self.viewer = viewer
        self.slots = threading.BoundedSemaphore(4)
        super().__init__(("127.0.0.1", port), Handler)
        self.origin = f"http://127.0.0.1:{self.server_port}"

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(5)
        return connection, address

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        print("Viewer request failed; no request payload logged.", file=sys.stderr)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def respond(self, status, content=b"", mime="text/plain", filename=None):
        self.send_response(status)
        self.send_header("Content-Type", mime + ("; charset=utf-8" if mime.startswith("text/") else ""))
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; "
                         "connect-src 'self'; img-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        self.send_header("Connection", "close")
        if filename:
            self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        self.end_headers()
        self.close_connection = True
        if self.command != "HEAD":
            self.wfile.write(content)

    def boundary(self):
        if (self.headers.get_all("Host") != [self.server.origin.removeprefix("http://")]
                or len(self.headers.get_all("Origin", [])) > 1
                or len(self.headers.get_all("Sec-Fetch-Site", [])) > 1
                or self.headers.get("Origin") not in (None, self.server.origin)
                or self.headers.get("Sec-Fetch-Site") not in (None, "same-origin", "none")
                or self.headers.get("Transfer-Encoding") is not None
                or self.headers.get("Content-Length") not in (None, "0")):
            self.respond(403, b"Viewer request boundary rejected.")
            return False
        return True

    def do_GET(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def dispatch(self):
        if not self.boundary():
            return
        try:
            path = urlsplit(self.path)
        except ValueError:
            self.respond(400, b"Invalid Viewer URL.")
            return
        static = {"/": ("index.html", "text/html"), "/viewer.js": ("viewer.js", "text/javascript"),
                  "/viewer.css": ("viewer.css", "text/css")}
        if self.command == "GET" and path.path in static and not path.query:
            file, mime = static[path.path]
            self.respond(200, (ROOT / file).read_bytes(), mime)
            return
        authorization = self.headers.get("Authorization", "")
        if not authorization.isascii():
            self.respond(401, b"Invalid Viewer authorization.")
            return
        if len(self.headers.get_all("Authorization", [])) != 1 or not self.server.viewer.authorized(authorization):
            self.respond(401, b"Viewer access expired or revoked; authentication required.")
            return
        if self.command == "POST" and path.path in ("/api/revoke", "/api/stop") and not path.query:
            self.server.viewer.revoke()
            self.respond(200, b'{"revoked":true}', "application/json")
            if path.path == "/api/stop":
                threading.Thread(target=self.server.shutdown, daemon=True).start()
            return
        if self.command != "GET":
            self.respond(405, b"Unsupported Viewer operation.")
            return
        try:
            query = parse_qs(path.query, strict_parsing=True, max_num_fields=4)
            if path.path == "/api/run" and not path.query:
                payload = self.server.viewer.fetch(authorization, "run")
            elif path.path == "/api/export" and set(query) == {"format"} and len(query["format"]) == 1 and query["format"][0] in FORMATS:
                payload = self.server.viewer.fetch(authorization, "export", query["format"][0])
            elif path.path == "/api/artifact" and set(query) == {"id"} and len(query["id"]) == 1 and SAFE_ID.fullmatch(query["id"][0]):
                payload = self.server.viewer.fetch(authorization, "artifact", query["id"][0])
            else:
                self.respond(404, b"Unknown Viewer endpoint.")
                return
            if not self.server.viewer.authorized(authorization):
                raise PermissionError
            self.respond(200, payload[1], payload[0], payload[2])
        except PermissionError:
            self.respond(401, b"Viewer access expired or revoked.")
        except (ClientError, OSError, ValueError, KeyError, TypeError):
            self.respond(502, b"Native request failed or consent denied. Nothing retried; inspect Tester.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, required=True, help="Existing owner-only CI recovery file; never served")
    parser.add_argument("--url", default=os.environ.get("TESTER_MCP_URL", "http://127.0.0.1:8080/mcp"))
    parser.add_argument("--port", type=int, default=0, help="Loopback port, default random free port")
    parser.add_argument("--ttl", type=int, default=600, help="Lifetime in seconds, 1-900, default 600")
    args = parser.parse_args(argv)
    viewer = None
    try:
        if not 0 <= args.port <= 65535:
            raise ClientError("port must be 0-65535.")
        state = load_state(args.state)
        owner = {key: state.get(key, "") for key in ("run_id", "run_key")}
        client = Client(args.url, os.environ.get("TESTER_MCP_SESSION"), os.environ.get("TESTER_MCP_TOKEN"))
        viewer = Viewer(client, owner, args.ttl)
        client.initialize()
        # Verify ownership before exposing a listener, with no tool/capture execution.
        viewer.fetch("Bearer " + viewer.token, "run")
        with Server(viewer, args.port) as server:
            timer = threading.Timer(args.ttl, lambda: (viewer.revoke(), server.shutdown()))
            timer.daemon = True
            timer.start()
            print(f"Viewer: {server.origin}\nViewer access token (paste into the page; do not share): {viewer.token}",
                  flush=True)
            print("One owned native run; no recording/capture. Expires automatically. Ctrl-C stops access.", flush=True)
            try:
                server.serve_forever(poll_interval=.1)
            finally:
                timer.cancel()
                viewer.revoke()
        return 0
    except KeyboardInterrupt:
        return 0
    except (ClientError, OSError, ValueError):
        print("Viewer could not start; verify private state, native authorization and endpoint. No request retried.",
              file=sys.stderr)
        return 2
    finally:
        if viewer:
            viewer.revoke()


if __name__ == "__main__":
    sys.exit(main())
