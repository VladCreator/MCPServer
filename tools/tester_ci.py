#!/usr/bin/env python3
"""Thin native MCP scenario client. Never executes UI actions or replays lost submissions."""

import argparse
import hashlib
import http.client
import json
import os
import secrets
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ACTIVE = {"awaiting_approval", "queued", "running", "cancelling"}
MAX_RESPONSE = 4 * 1024 * 1024


class ClientError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ClientError("MCP redirects are not allowed.")


class Client:
    def __init__(self, url, session=None, token=None):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
            raise ClientError("Use an explicit HTTP(S) MCP URL without credentials.")
        if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
            raise ClientError("Plain HTTP is allowed only on loopback; use authenticated HTTPS remotely.")
        self.url, self.session, self.token = url, session, token
        self.opener = urllib.request.build_opener(NoRedirect)

    def rpc(self, method, params):
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.url, data=json.dumps({
            "jsonrpc": "2.0", "id": time.time_ns(), "method": method, "params": params,
        }).encode(), headers=headers)
        try:
            with self.opener.open(request, timeout=90) as response:
                body = response.read(MAX_RESPONSE + 1)
                self.session = response.headers.get("Mcp-Session-Id", self.session)
        except (OSError, http.client.HTTPException) as error:
            raise ClientError("MCP transport failed; request was NOT retried. Retain the state file and recover explicitly.") from error
        if len(body) > MAX_RESPONSE:
            raise ClientError("MCP response exceeded the size limit.")
        text = body.decode("utf-8")
        if text.startswith(("event:", "data:")):
            messages = [line[5:].strip() for line in text.splitlines() if line.startswith("data:")]
            if len(messages) != 1:
                raise ClientError("Expected one MCP SSE response.")
            text = messages[0]
        reply = json.loads(text)
        if "error" in reply:
            raise ClientError("MCP rejected the request; inspect the native dashboard.")
        if not isinstance(reply.get("result"), dict):
            raise ClientError("Invalid MCP result.")
        return reply["result"]

    def initialize(self):
        if not self.session:
            self.rpc("initialize", {
                "protocolVersion": "2025-06-18", "capabilities": {},
                "clientInfo": {"name": "Tester CI", "version": "0.2.2"},
            })
        if not self.session:
            raise ClientError("Server did not establish an approved MCP session.")

    def call(self, name, arguments, allow_error=False):
        result = self.rpc("tools/call", {"name": name, "arguments": arguments})
        if result.get("isError") and not allow_error:
            raise ClientError("Native tool failed; inspect the run/dashboard. No request was retried.")
        data = result.get("structuredContent")
        if not isinstance(data, dict):
            raise ClientError("Native tool did not return a structured result.")
        return data


def private_write(path, data):
    path = Path(path)
    if path.is_symlink():
        raise ClientError("Refusing to overwrite a symbolic link.")
    fd, temporary = tempfile.mkstemp(prefix=".tester-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load_state(path):
    if path.is_symlink():
        raise ClientError("Recovery state must not be a symbolic link.")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "r", encoding="utf-8") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_uid != os.geteuid() or info.st_size > 65536:
            raise ClientError("Recovery state must be a bounded regular owner-only file (mode 0600).")
        state = json.loads(stream.read(65537))
    if not isinstance(state, dict) or not all(isinstance(state.get(k), str) for k in ("run_key", "submission_id")):
        raise ClientError("Invalid recovery state.")
    return state


def save_state(path, state):
    private_write(path, json.dumps(state, sort_keys=True).encode())


def successful(run):
    return (run.get("status") == "completed" and not run.get("pending_cleanup")
            and not run.get("pending_state_restore")
            and run.get("cleanup_status") in (None, "not_configured", "completed"))


def execute(client, arguments, state_path, output, timeout=120, poll_ms=200, resume=False, cancel=False):
    state_path, output = Path(state_path), Path(output)
    digest = hashlib.sha256(json.dumps(
        {k: v for k, v in arguments.items() if k != "policy_id"}, sort_keys=True,
        separators=(",", ":"), ensure_ascii=False,
    ).encode()).hexdigest()
    if state_path.exists() or state_path.is_symlink():
        state = load_state(state_path)
        if not resume and state.get("request_digest") != digest:
            raise ClientError("State belongs to a different submission. Use a new state file for a fresh attempt.")
    else:
        if resume or cancel:
            raise ClientError("Recovery state does not exist.")
        state = {"run_key": secrets.token_urlsafe(32), "submission_id": secrets.token_hex(16),
                 "request_digest": digest}
        # Durable client ownership precedes the first network submission.
        save_state(state_path, state)
    client.initialize()
    if not state.get("run_id"):
        if resume or cancel:
            raise ClientError("Initial response was lost: resupply the SAME definition/parameters to deduplicate safely.")
        run = client.call("run_route", {
            **arguments, "mode": "async", "disconnect_policy": "continue_until_terminal",
            "run_key": state["run_key"], "submission_id": state["submission_id"],
        }, allow_error=True)
        if not isinstance(run.get("run_id"), str):
            raise ClientError("Submission was not accepted. State retained; inspect native rejection before retrying.")
        state["run_id"] = run["run_id"]
        save_state(state_path, state)
    owner = {k: state[k] for k in ("run_id", "run_key")}
    if cancel:
        client.call("cancel_run", owner, allow_error=True)
    deadline = time.monotonic() + timeout
    while True:
        run = client.call("get_run", owner, allow_error=True)
        if run.get("status") not in ACTIVE:
            break
        if time.monotonic() >= deadline:
            raise ClientError("Polling deadline exceeded; run may still be active. Retain state; use --resume or --cancel.")
        time.sleep(poll_ms / 1000)
    output.mkdir(parents=True, exist_ok=True)
    for format, filename in (("json", "run.json"), ("markdown", "timeline.md"), ("junit", "junit.xml")):
        artifact = client.call("export_run", {**owner, "format": format})
        if artifact.get("run_id") != owner["run_id"] or artifact.get("content_redacted") is not True:
            raise ClientError("Export did not reference the canonical redacted native run.")
        content = artifact.get("content")
        if not isinstance(content, str) or len(content.encode()) > 2 * 1024 * 1024:
            raise ClientError("Invalid bounded export.")
        private_write(output / filename, content.encode())
    print(f"Native run {run['run_id']}: {run.get('status')}; cleanup={run.get('cleanup_status', 'not_configured')}")
    return 0 if successful(run) else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--yaml", type=Path)
    source.add_argument("--scenario", type=Path, help="Structured JSON definition")
    source.add_argument("--route", help="Exact bundled route ID; requires --version and --digest")
    source.add_argument("--resume", action="store_true")
    source.add_argument("--cancel", action="store_true")
    parser.add_argument("--version")
    parser.add_argument("--digest")
    parser.add_argument("--parameters", default="{}", help="JSON typed parameter object")
    parser.add_argument("--policy-id", help="Exact-plan policy already approved through route_policy")
    parser.add_argument("--url", default=os.environ.get("TESTER_MCP_URL", "http://127.0.0.1:8080/mcp"))
    parser.add_argument("--state", required=True, type=Path, help="Owner-only recovery file; KEEP PRIVATE, never commit")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--poll-ms", type=int, default=200)
    args = parser.parse_args(argv)
    try:
        if not 1 <= args.timeout <= 3600 or not 100 <= args.poll_ms <= 5000:
            raise ClientError("Use timeout 1-3600 seconds and poll-ms 100-5000.")
        parameters = json.loads(args.parameters)
        if not isinstance(parameters, dict):
            raise ClientError("parameters must be a JSON object.")
        arguments = {"parameters": parameters, "privacy_sensitive": True}
        if args.yaml or args.scenario:
            path = args.yaml or args.scenario
            text = path.read_text(encoding="utf-8")
            if len(text) > 32768:
                raise ClientError("Definition exceeds 32768 characters.")
            arguments["yaml" if args.yaml else "scenario"] = text if args.yaml else json.loads(text)
        elif args.route:
            if not args.version or not args.digest:
                raise ClientError("Catalog execution requires exact --version and --digest.")
            arguments.update(route_id=args.route, version=args.version, digest=args.digest)
        if args.policy_id:
            arguments["policy_id"] = args.policy_id
        client = Client(args.url, os.environ.get("TESTER_MCP_SESSION"), os.environ.get("TESTER_MCP_TOKEN"))
        return execute(client, arguments, args.state, args.output, args.timeout, args.poll_ms,
                       resume=args.resume or args.cancel, cancel=args.cancel)
    except (ClientError, OSError, ValueError, KeyError) as error:
        # Do not echo backend bodies, user payloads, recovery keys or authorization headers.
        print(str(error) if isinstance(error, ClientError) else
              f"CI client failed ({type(error).__name__}); recovery state retained.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
