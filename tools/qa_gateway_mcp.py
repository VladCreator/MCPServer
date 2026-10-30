#!/usr/bin/env python3
"""stdio MCP gateway for Android and iOS Tester backends."""

import argparse
import datetime
import json
import os
import re
import select
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PROTOCOLS = ("2024-11-05", "2025-03-26", "2025-06-18")
GATEWAY_VERSION = "0.2.2"
PRIVATE_HISTORY_TOOLS = {
    "run_route", "get_run", "cancel_run", "collect_failure_bundle",
    "export_run", "get_artifact", "wait_for_state", "device_state", "qa_session", "open_link", "clipboard", "route_policy",
}


RECONNECT_INTERVAL_S = 2.0


class BackendError(Exception):
    pass


class SessionLost(BackendError):
    """The backend no longer knows our session (restart/expiry); the call was not executed."""


class ReconnectMixin:
    last_attempt = 0.0

    def ensure_connected(self, force=False):
        if self.connected:
            return True
        now = time.monotonic()
        if not force and now - self.last_attempt < RECONNECT_INTERVAL_S:
            return False
        self.last_attempt = now
        return self.connect()


def iso_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def truncate(value, limit=500):
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    return text[:limit]


CONNECT_TIMEOUT_S = 10


def unreachable_hint(url, reason):
    """Explain the usual cause of an unreachable local backend."""
    parsed = urllib.parse.urlparse(url)
    if parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
        return ""
    port = parsed.port
    text = str(reason).lower()
    if isinstance(reason, ConnectionRefusedError) or "refused" in text:
        return (f" | Hint: nothing listens on Mac port {port}. Start the tunnel"
                f" (iproxy / tools/ios_tunnel.py, or adb forward) or the simulator app.")
    if isinstance(reason, TimeoutError) or "timed out" in text:
        return (f" | Hint: port {port} accepts connections but nothing answers. Unlock the device and"
                f" keep Tester in front with the server Live; if it still times out the iproxy tunnel is"
                f" stale: run `python3 tools/ios_tunnel.py --check --local-port {port} --udid <udid>`.")
    if isinstance(reason, ConnectionResetError) or "reset" in text:
        return (" | Hint: the tunnel is up but Tester is not serving on the device. Unlock it, open Tester"
                " and tap Start server.")
    return ""


class HttpBackend(ReconnectMixin):
    def __init__(self, name, platform, url, token=None, token_env=None, headers=None, timeout=60):
        self.name = name
        self.platform = platform
        self.url = url.rstrip("/")
        self.token = token or (os.environ.get(token_env) if token_env else None)
        self.headers = headers or {}
        self.timeout = timeout
        self.session_id = None
        self.tools = []
        self.connected = False
        self.error = None

    def _headers(self):
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        headers.update(self.headers)
        return headers

    def _post(self, payload, timeout=None):
        request = urllib.request.Request(
            self.url, data=json.dumps(payload).encode(), method="POST", headers=self._headers()
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout or self.timeout) as response:
                body = response.read().decode()
                session = response.headers.get("Mcp-Session-Id", self.session_id)
                content_type = response.headers.get("Content-Type", "")
                return self._parse(body, content_type), session
        except urllib.error.HTTPError as error:
            body = error.read().decode(errors="replace")
            error.close()
            if error.code == 404 and self.session_id:
                raise SessionLost(f"{self.name}: session expired (HTTP 404)") from error
            raise BackendError(f"{self.name}: HTTP {error.code}: {body[:500]}") from error
        except (urllib.error.URLError, ConnectionError, TimeoutError) as error:
            self.connected = False
            reason = getattr(error, "reason", error)
            self.error = f"{self.name}: unreachable at {self.url}: {reason}{unreachable_hint(self.url, reason)}"
            raise BackendError(self.error) from error

    @staticmethod
    def _parse(body, content_type):
        if not body.strip():
            return None
        if "text/event-stream" in content_type:
            for line in body.splitlines():
                if line.startswith("data: "):
                    return json.loads(line[6:])
        return json.loads(body)

    def connect(self):
        self.close()
        self.session_id = None
        self.tools = []
        try:
            reply, session = self._post({
                "jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {
                    "protocolVersion": PROTOCOLS[-1],
                    "capabilities": {},
                    "clientInfo": {"name": "Tester QA Gateway", "version": GATEWAY_VERSION},
                },
            }, timeout=min(self.timeout, CONNECT_TIMEOUT_S))
            if not reply or "error" in reply:
                raise BackendError(f"{self.name}: initialize failed: {reply}")
            self.session_id = session or reply.get("result", {}).get("sessionId")
            if not self.session_id:
                raise BackendError(f"{self.name}: backend did not return Mcp-Session-Id")
            self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})
            listing, _ = self._post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
            self.tools = (listing or {}).get("result", {}).get("tools", [])
            self.connected = True
            self.error = None
        except Exception as failure:
            self.connected = False
            self.error = str(failure)
        return self.connected

    def call(self, tool, arguments):
        if not self.connected:
            raise BackendError(self.error or f"{self.name}: backend is not connected")
        reply, _ = self._post({
            "jsonrpc": "2.0", "id": 100, "method": "tools/call",
            "params": {"name": tool, "arguments": arguments},
        })
        if not reply:
            raise BackendError(f"{self.name}: empty response")
        if "error" in reply:
            message = reply["error"].get("message", "unknown error")
            if "not initialized" in message.lower():
                raise SessionLost(f"{self.name}: {message}")
            raise BackendError(f"{self.name}: {message}")
        return reply.get("result", {})

    def close(self):
        if self.session_id and self.connected:
            try:
                request = urllib.request.Request(
                    self.url, method="DELETE", headers=self._headers()
                )
                urllib.request.urlopen(request, timeout=5).close()
            except Exception:
                pass


class StdioBackend(ReconnectMixin):
    def __init__(self, name, platform, command, args=None, env=None, timeout=120):
        self.name = name
        self.platform = platform
        self.command = command
        self.args = args or []
        self.env = {**os.environ, **(env or {})}
        self.timeout = timeout
        self.process = None
        self.next_id = 1
        self.tools = []
        self.connected = False
        self.error = None

    def _request(self, payload):
        # A dead process means the request was never sent, so the gateway may
        # safely restart the bridge and retry (SessionLost).
        if not self.process or self.process.poll() is not None:
            self.connected = False
            self.error = f"{self.name}: stdio process is not running"
            raise SessionLost(self.error)
        try:
            self.process.stdin.write(json.dumps(payload) + "\n")
            self.process.stdin.flush()
        except (BrokenPipeError, OSError) as error:
            self.connected = False
            self.error = f"{self.name}: stdio process is not running"
            raise SessionLost(self.error) from error
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            ready, _, _ = select.select([self.process.stdout], [], [], max(0.1, deadline - time.monotonic()))
            if not ready:
                continue
            line = self.process.stdout.readline()
            if not line:
                self.connected = False
                self.error = f"{self.name}: stdio process closed stdout"
                raise BackendError(self.error)
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                continue
            if message.get("id") == payload.get("id"):
                return message
        raise BackendError(f"{self.name}: timeout waiting for MCP response")

    def connect(self):
        self.close()
        self.tools = []
        try:
            self.process = subprocess.Popen(
                [self.command] + self.args,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                bufsize=1,
                env=self.env,
            )
            reply = self._request({
                "jsonrpc": "2.0", "id": self.next_id, "method": "initialize",
                "params": {
                    "protocolVersion": PROTOCOLS[-1],
                    "capabilities": {},
                    "clientInfo": {"name": "Tester QA Gateway", "version": GATEWAY_VERSION},
                },
            })
            self.next_id += 1
            if "error" in reply:
                raise BackendError(f"{self.name}: initialize failed: {reply['error']}")
            self.process.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
            self.process.stdin.flush()
            listing = self._request({"jsonrpc": "2.0", "id": self.next_id, "method": "tools/list"})
            self.next_id += 1
            self.tools = listing.get("result", {}).get("tools", [])
            self.connected = True
            self.error = None
        except Exception as failure:
            self.connected = False
            self.error = str(failure)
            self.close()
        return self.connected

    def call(self, tool, arguments):
        if not self.connected:
            raise BackendError(self.error or f"{self.name}: backend is not connected")
        reply = self._request({
            "jsonrpc": "2.0", "id": self.next_id, "method": "tools/call",
            "params": {"name": tool, "arguments": arguments},
        })
        self.next_id += 1
        if "error" in reply:
            raise BackendError(f"{self.name}: {reply['error'].get('message', 'unknown error')}")
        return reply.get("result", {})

    def close(self):
        if self.process:
            try:
                self.process.terminate()
                self.process.wait(timeout=3)
            except Exception:
                try:
                    self.process.kill()
                except Exception:
                    pass
            for stream in (self.process.stdin, self.process.stdout):
                try:
                    if stream:
                        stream.close()
                except Exception:
                    pass
            self.process = None


class GatewaySession:
    def __init__(self, session_id, test_case, platform, notes):
        self.id = session_id
        self.test_case = test_case
        self.platform = platform
        self.notes = notes
        self.started_at = iso_now()
        self.ended_at = None
        self.steps = []
        self.calls = []

    def add_step(self, title, status, expected="", actual="", notes=""):
        self.steps.append({
            "number": len(self.steps) + 1,
            "title": title,
            "status": status,
            "expected": expected,
            "actual": actual,
            "notes": notes,
            "at": iso_now(),
        })

    def add_call(self, backend, tool, arguments, ok, duration_ms, error=None, run_reference=None):
        self.calls.append({
            "backend": backend,
            "tool": tool,
            "arguments": "[private tool payload omitted]" if tool in PRIVATE_HISTORY_TOOLS else truncate(arguments, 1000),
            "ok": ok,
            "duration_ms": duration_ms,
            "error": "[private tool failure omitted]" if error and tool in PRIVATE_HISTORY_TOOLS else error,
            "at": iso_now(),
            "native_run": run_reference,
        })


class Gateway:
    def __init__(self, backends, report_dir):
        self.backends = backends
        self.report_dir = Path(report_dir)
        self.session = None
        self.notifications = []
        self._signature = None

    def _tool_signature(self):
        return tuple(
            (backend.name, backend.connected, tuple(tool.get("name") for tool in backend.tools))
            for backend in self.backends
        )

    def _note_changes(self):
        signature = self._tool_signature()
        if self._signature is not None and signature != self._signature:
            self.notifications.append({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
        self._signature = signature

    def start(self, force=False):
        """Connect backends that are down; retried on later calls (rate-limited)."""
        for backend in self.backends:
            if not backend.connected:
                backend.ensure_connected(force=force)
        self._note_changes()

    def drain_notifications(self):
        pending, self.notifications = self.notifications, []
        return pending

    def backend(self, name):
        return next((backend for backend in self.backends if backend.name == name), None)

    def capabilities(self, connect=True):
        if connect:
            self.start(force=True)
        return {
            "gateway_version": GATEWAY_VERSION,
            "backends": [
                {
                    "name": backend.name,
                    "platform": backend.platform,
                    "connected": backend.connected,
                    "tool_count": len(backend.tools),
                    "error": backend.error,
                }
                for backend in self.backends
            ],
            "gateway_tools": ["qa_capabilities", "qa_session"],
        }

    def backend_tools(self):
        self.start()
        result = []
        for backend in self.backends:
            if not backend.connected:
                continue
            for tool in backend.tools:
                copy = dict(tool)
                copy["name"] = f"{backend.name}__{tool['name']}"
                copy["description"] = f"[{backend.platform}] {tool.get('description', '')}"
                result.append(copy)
        return result

    def tools(self):
        return [
            {
                "name": "qa_capabilities",
                "description": "Show connected QA backends, platforms, tool counts and errors.",
                "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
            },
            {
                "name": "qa_session",
                "description": "Gateway QA session: begin/step/status/end and one cross-platform report.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "action": {"type": "string", "enum": ["begin", "step", "status", "end"]},
                        "test_case": {"type": "string"},
                        "platform": {"type": "string"},
                        "title": {"type": "string"},
                        "status": {"type": "string", "enum": ["pass", "fail", "blocked", "info"]},
                        "expected": {"type": "string"},
                        "actual": {"type": "string"},
                        "result": {"type": "string"},
                        "notes": {"type": "string"},
                    },
                    "required": ["action"],
                    "additionalProperties": False,
                },
            },
        ] + self.backend_tools()

    def qa_session(self, arguments):
        action = arguments.get("action")
        if action == "begin":
            if self.session:
                return self._text(f"Gateway session {self.session.id} is already active; end it first.", True)
            test_case = arguments.get("test_case", "").strip()
            if not test_case:
                return self._text("Missing test_case", True)
            session_id = "gw-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
            self.session = GatewaySession(
                session_id, test_case, arguments.get("platform", "android+ios"), arguments.get("notes", "")
            )
            return self._text(f"Gateway QA session {session_id} started for {test_case}.")
        if action == "step":
            if not self.session:
                return self._text("No active gateway session", True)
            status = arguments.get("status", "info")
            if status not in ("pass", "fail", "blocked", "info"):
                return self._text("status must be pass, fail, blocked or info", True)
            self.session.add_step(
                arguments.get("title", ""), status, arguments.get("expected", ""),
                arguments.get("actual", ""), arguments.get("notes", ""),
            )
            return self._text(f"Step {len(self.session.steps)} recorded [{status}].")
        if action == "status":
            if not self.session:
                return self._text("No active gateway session.")
            return self._text(json.dumps(self._report(), indent=2))
        if action == "end":
            if not self.session:
                return self._text("No active gateway session", True)
            self.session.ended_at = iso_now()
            result = arguments.get("result", "UNKNOWN") or "UNKNOWN"
            self.session.notes = (self.session.notes + "\n" + arguments.get("notes", "")).strip()
            report = self._report()
            report["result"] = result
            self.report_dir.mkdir(parents=True, exist_ok=True)
            json_path = self.report_dir / f"{self.session.id}.json"
            markdown_path = self.report_dir / f"{self.session.id}.md"
            json_path.write_text(json.dumps(report, indent=2))
            markdown_path.write_text(self._markdown(report))
            self.session = None
            return self._text(f"Gateway session closed: {result}. JSON: {json_path}\nMarkdown: {markdown_path}")
        return self._text("action must be begin, step, status or end", True)

    def call(self, name, arguments):
        if name == "qa_capabilities":
            return self._text(json.dumps(self.capabilities(), indent=2))
        if name == "qa_session":
            return self.qa_session(arguments)
        if "__" not in name:
            raise BackendError(f"Unknown gateway tool: {name}")
        prefix, tool = name.split("__", 1)
        backend = self.backend(prefix)
        if not backend:
            raise BackendError(f"Unknown backend: {prefix}")
        backend.ensure_connected(force=True)
        start = time.monotonic()
        try:
            try:
                result = backend.call(tool, arguments)
            except SessionLost:
                if tool in {"run_route", "route_policy", "collect_failure_bundle", "device_state", "open_link", "clipboard", "qa_session"}:
                    raise BackendError(
                        f"{prefix}: session lost during {tool}; execution outcome is unknown. "
                        "Tool was not retried because actions or capture may already have executed."
                    ) from None
                if not backend.connect():
                    raise BackendError(backend.error or f"{prefix}: reconnect failed")
                result = backend.call(tool, arguments)
            if self.session:
                data = result.get("structuredContent", {})
                run_reference = None
                if tool in {"run_route", "get_run", "cancel_run"} and isinstance(data, dict):
                    run_id = data.get("run_id")
                    if isinstance(run_id, str) and re.fullmatch(r"[a-f0-9-]{36}", run_id):
                        run_reference = {"run_id": run_id, "source": "native_canonical"}
                self.session.add_call(prefix, tool, arguments, not result.get("isError", False),
                                      int((time.monotonic() - start) * 1000), run_reference=run_reference)
            return result
        except Exception as failure:
            if self.session:
                self.session.add_call(prefix, tool, arguments, False, int((time.monotonic() - start) * 1000), str(failure))
            raise
        finally:
            self._note_changes()

    def _report(self):
        if not self.session:
            return {}
        return {
            "session": self.session.id,
            "test_case": self.session.test_case,
            "platform": self.session.platform,
            "started_at": self.session.started_at,
            "ended_at": self.session.ended_at,
            "notes": self.session.notes,
            "steps": self.session.steps,
            "tool_calls": self.session.calls,
            "backends": self.capabilities(connect=False)["backends"],
        }

    @staticmethod
    def _markdown(report):
        lines = [
            f"# QA Gateway report — {report['test_case']}",
            "",
            f"- Session: {report['session']}",
            f"- Platform: {report['platform']}",
            f"- Started: {report['started_at']}",
            f"- Ended: {report.get('ended_at')}",
            f"- Result: {report.get('result', 'UNKNOWN')}",
            "",
            "## Steps",
        ]
        for step in report["steps"]:
            lines.append(f"### {step['number']}. {step['title']} — {step['status'].upper()}")
            if step["expected"]:
                lines.append(f"- Expected: {step['expected']}")
            if step["actual"]:
                lines.append(f"- Actual: {step['actual']}")
            if step["notes"]:
                lines.append(f"- Notes: {step['notes']}")
        lines.append("")
        lines.append("## Tool calls")
        for call in report["tool_calls"]:
            native = call.get("native_run")
            reference = f" native_run={native['run_id']} (canonical)" if native else ""
            lines.append(f"- {call['backend']}__{call['tool']} ok={call['ok']} {call['duration_ms']}ms{reference}")
        return "\n".join(lines) + "\n"

    @staticmethod
    def _text(text, is_error=False):
        return {"content": [{"type": "text", "text": text}], "isError": is_error}

    def close(self):
        for backend in self.backends:
            try:
                backend.close()
            except Exception:
                pass


def load_config(path):
    with open(path) as handle:
        config = json.load(handle)
    backends = []
    for entry in config.get("backends", []):
        if entry.get("transport") == "stdio":
            backends.append(StdioBackend(
                entry["name"], entry.get("platform", "ios"), entry["command"],
                entry.get("args", []), entry.get("env"),
            ))
        else:
            backends.append(HttpBackend(
                entry["name"], entry.get("platform", "android"), entry["url"],
                entry.get("token"), entry.get("token_env"), entry.get("headers"),
                entry.get("timeout", 60),
            ))
    return Gateway(backends, config.get("report_dir", "qa-reports"))


def error(request_id, code, message):
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def handle(gateway, request):
    if not isinstance(request, dict) or request.get("jsonrpc") != "2.0":
        return error(None, -32600, "Invalid JSON-RPC request")
    if "id" not in request:
        return None
    method = request.get("method")
    request_id = request["id"]
    params = request.get("params", {})
    if method == "initialize":
        version = params.get("protocolVersion")
        result = {
            "protocolVersion": version if version in PROTOCOLS else PROTOCOLS[-1],
            "capabilities": {"tools": {"listChanged": True}},
            "serverInfo": {"name": "Tester QA Gateway", "version": GATEWAY_VERSION},
            "instructions": "Unified QA gateway. Use qa_capabilities, then <backend>__<tool>.",
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": gateway.tools()}
    elif method == "tools/call":
        name = params.get("name")
        arguments = params.get("arguments", {})
        try:
            result = gateway.call(name, arguments)
        except BackendError as failure:
            result = Gateway._text(str(failure), True)
        except Exception as failure:
            result = Gateway._text(f"{type(failure).__name__}: {failure}", True)
    else:
        return error(request_id, -32601, f"Unknown method: {method}")
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, help="JSON gateway configuration")
    args = parser.parse_args()
    gateway = load_config(args.config)
    try:
        for line in sys.stdin:
            if not line.strip():
                continue
            try:
                request = json.loads(line)
            except json.JSONDecodeError:
                response = error(None, -32700, "Invalid JSON")
            else:
                response = handle(gateway, request)
            if response is not None:
                print(json.dumps(response), flush=True)
            for notification in gateway.drain_notifications():
                print(json.dumps(notification), flush=True)
    finally:
        gateway.close()


if __name__ == "__main__":
    main()
