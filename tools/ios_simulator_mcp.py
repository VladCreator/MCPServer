#!/usr/bin/env python3
"""Local stdio MCP server for screenshots of one explicitly selected iOS simulator."""

import argparse
import base64
import datetime
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid


PROTOCOLS = ("2024-11-05", "2025-03-26", "2025-06-18")
SCREENSHOT_TOOL = {
    "name": "take_screenshot",
    "description": (
        "Capture the selected iOS simulator's current display, including other apps. "
        "Returns a PNG and capture timestamp. Simulator only; no physical iPhones."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {},
        "additionalProperties": False,
    },
}


def screenshot(udid):
    with tempfile.TemporaryDirectory(prefix="tester-simulator-") as directory:
        output = Path(directory) / "screen.png"
        subprocess.run(
            ["xcrun", "simctl", "io", udid, "screenshot", "--type=png", str(output)],
            check=True, capture_output=True, text=True, timeout=30,
        )
        image = output.read_bytes()
        if not image.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("simctl did not produce a PNG screenshot")
    timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    return {
        "content": [
            {
                "type": "text",
                "text": f"Simulator: {udid}\nCaptured at: {timestamp}",
            },
            {
                "type": "image",
                "mimeType": "image/png",
                "data": base64.b64encode(image).decode("ascii"),
            },
        ],
        "isError": False,
    }


def error(request_id, code, message):
    return {
        "jsonrpc": "2.0", "id": request_id,
        "error": {"code": code, "message": message},
    }


def handle(request, udid):
    if not isinstance(request, dict) or request.get("jsonrpc") != "2.0":
        return error(None, -32600, "Invalid JSON-RPC request")
    method = request.get("method")
    if not isinstance(method, str):
        return error(request.get("id"), -32600, "method must be a string")
    if "id" not in request:
        return None
    request_id = request["id"]
    params = request.get("params", {})
    if not isinstance(params, dict):
        return error(request_id, -32602, "params must be an object")
    if method == "initialize":
        version = params.get("protocolVersion")
        result = {
            "protocolVersion": version if version in PROTOCOLS else PROTOCOLS[-1],
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "Tester iOS Simulator", "version": "0.1.0"},
            "instructions": (
                f"Screenshot bridge for simulator {udid}. "
                "Use the separate Tester iOS connection for qa_session and audio."
            ),
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": [SCREENSHOT_TOOL]}
    elif method == "tools/call":
        if params.get("name") != "take_screenshot":
            return error(request_id, -32602, "Unknown tool")
        arguments = params.get("arguments", {})
        if not isinstance(arguments, dict) or arguments:
            return error(request_id, -32602, "take_screenshot accepts an empty arguments object")
        try:
            result = screenshot(udid)
        except (OSError, ValueError, subprocess.SubprocessError) as failure:
            detail = str(failure)
            if isinstance(failure, subprocess.CalledProcessError):
                detail = failure.stderr.strip() or detail
            print(f"Screenshot failed for {udid}: {detail}", file=sys.stderr)
            result = {
                "content": [{
                    "type": "text",
                    "text": (
                        f"Screenshot failed for simulator {udid}: {detail}. "
                        "Ensure that simulator is booted and Xcode command-line tools are selected."
                    ),
                }],
                "isError": True,
            }
    else:
        return error(request_id, -32601, f"Unknown method: {method}")
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def simulator_udid(value):
    try:
        return str(uuid.UUID(value)).upper()
    except ValueError as failure:
        raise argparse.ArgumentTypeError("Expected a simulator UUID from xcrun simctl list devices") from failure


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--udid", required=True, type=simulator_udid)
    args = parser.parse_args()
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            response = error(None, -32700, "Invalid JSON")
        else:
            response = handle(request, args.udid)
        if response is not None:
            print(json.dumps(response), flush=True)


if __name__ == "__main__":
    main()
