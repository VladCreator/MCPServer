#!/usr/bin/env python3
"""stdio MCP bridge for Appium/XCUITest UI automation of an iOS simulator or device."""

import argparse
import base64
import contextlib
import datetime
import fcntl
import json
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

PROTOCOLS = ("2024-11-05", "2025-03-26", "2025-06-18")
DEFAULT_STATE_DIR = Path.home() / ".cache" / "tester-appium"
SELECTOR_PROPERTIES = {
    "index": {"type": "integer", "description": "Element index in the returned tree"},
    "text": {"type": "string", "description": "Label/name/value contains text"},
    "text_exact": {"type": "boolean", "description": "Require exact text"},
    "resource_id": {"type": "string", "description": "Accessibility identifier/name"},
    "content_desc": {"type": "string", "description": "Accessibility label contains text"},
    "class_name": {"type": "string", "description": "XCUIElementType..."},
}


def selector_schema():
    return dict(SELECTOR_PROPERTIES)


def tool(name, description, properties=None, required=None):
    return {
        "name": name,
        "description": description,
        "inputSchema": {
            "type": "object",
            "properties": properties or {},
            "required": required or [],
            "additionalProperties": False,
        },
    }


SCREEN_TREE = tool(
    "get_screen_tree",
    "Return the XCUITest accessibility tree as indented text. Appium/XCUITest only.",
    {"max_nodes": {"type": "integer", "description": "Default 300, max 1000"}},
)
SCREENSHOT = tool(
    "take_screenshot",
    "Capture the app under test as a PNG through Appium/XCUITest.",
)
UI_FIND = tool(
    "ui_find",
    "Find matching XCUITest elements and return their type, label, name, value and rect.",
    {**selector_schema(), "limit": {"type": "integer", "description": "Default 20, max 100"}},
)
UI_WAIT = tool(
    "ui_wait",
    "Wait for visible, absent, text or idle in the XCUITest tree.",
    {
        "condition": {"type": "string", "enum": ["visible", "absent", "text", "idle"]},
        **selector_schema(),
        "timeout_ms": {"type": "integer"},
        "interval_ms": {"type": "integer"},
        "quiet_ms": {"type": "integer"},
    },
    ["condition"],
)
UI_ASSERT = tool(
    "ui_assert",
    "Assert visible, absent, text or count; failure includes a screenshot and tree excerpt.",
    {
        "condition": {"type": "string", "enum": ["visible", "absent", "text", "count"]},
        **selector_schema(),
        "expected": {"type": "string"},
        "timeout_ms": {"type": "integer"},
    },
    ["condition"],
)
UI_GESTURE = tool(
    "ui_gesture",
    "Tap, double_tap, multi_tap, long_press, swipe, drag or pinch via XCUITest.",
    {
        "type": {"type": "string", "enum": ["tap", "double_tap", "multi_tap", "long_press", "swipe", "drag", "pinch"]},
        **selector_schema(),
        "x": {"type": "number"}, "y": {"type": "number"},
        "to_x": {"type": "number"}, "to_y": {"type": "number"},
        "count": {"type": "integer"}, "interval_ms": {"type": "integer"},
        "duration_ms": {"type": "integer"}, "direction": {"type": "string"},
        "radius": {"type": "number"},
    },
    ["type"],
)
CLICK = tool(
    "click_element",
    "Click or long-press an XCUITest element.",
    {**selector_schema(), "long_press": {"type": "boolean"}},
)
INPUT = tool(
    "input_text",
    "Clear and type text into an element, or into the active field.",
    {
        "text": {"type": "string"},
        **selector_schema(),
        "submit": {"type": "boolean"},
    },
    ["text"],
)
SCROLL = tool(
    "scroll",
    "Scroll the screen in a direction.",
    {"direction": {"type": "string", "enum": ["up", "down", "left", "right"]}},
    ["direction"],
)
SWIPE = tool(
    "swipe",
    "Swipe the screen by direction or explicit coordinates.",
    {
        "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
        "x": {"type": "number"}, "y": {"type": "number"},
        "to_x": {"type": "number"}, "to_y": {"type": "number"},
        "duration_ms": {"type": "integer"},
    },
)
GLOBAL = tool(
    "global_action",
    "Limited iOS system actions. 'home' is supported; Android-style back/recents are not.",
    {"action": {"type": "string", "enum": ["home", "lock"]}},
    ["action"],
)
LAUNCH = tool(
    "launch_app",
    "Launch an installed app by bundle identifier through XCUITest.",
    {"package_name": {"type": "string"}, "bundle_id": {"type": "string"}},
)
DEVICE = tool(
    "get_device_info",
    "Read Appium device/session information and the current orientation.",
)
STATE = tool(
    "device_state",
    "Read or set the XCUITest orientation; appearance is simulator/host controlled.",
    {
        "action": {"type": "string", "enum": ["get", "set"]},
        "rotation": {"type": "string", "enum": ["portrait", "landscape"]},
    },
    ["action"],
)

TOOLS = [
    SCREEN_TREE, SCREENSHOT, UI_FIND, UI_WAIT, UI_ASSERT, UI_GESTURE,
    CLICK, INPUT, SCROLL, SWIPE, GLOBAL, LAUNCH, DEVICE, STATE,
]


class ToolFailure(Exception):
    pass


STALE_SESSION_MARKERS = (
    "invalid session id",
    "session is either terminated or not started",
    "a session is either terminated",
    "not authorized for performing ui testing actions",
    'previously found element "application',
    "could not proxy command to the remote server",
    "socket hang up",
    "econnrefused",
)


def is_stale_session_error(message):
    text = message.lower()
    return any(marker in text for marker in STALE_SESSION_MARKERS)


def device_hint(message):
    if "not authorized for performing ui testing actions" in str(message).lower():
        return (" | Device action needed: unlock the device and keep it unlocked, and enable"
                " Settings > Developer > Enable UI Automation. This is an iOS restriction,"
                " not a Tester error.")
    return ""


def signing_capabilities(xcode_org_id=None, xcode_signing_id=None, wda_bundle_id=None,
                         show_xcode_log=False):
    """WebDriverAgent signing capabilities needed for physical devices."""
    capabilities = {}
    if xcode_org_id:
        capabilities["appium:xcodeOrgId"] = xcode_org_id
        capabilities["appium:xcodeSigningId"] = xcode_signing_id or "Apple Development"
        capabilities["appium:allowProvisioningDeviceRegistration"] = True
        capabilities["appium:wdaLaunchTimeout"] = 240000
        capabilities["appium:wdaConnectionTimeout"] = 240000
    if wda_bundle_id:
        capabilities["appium:updatedWDABundleId"] = wda_bundle_id
    if show_xcode_log:
        capabilities["appium:showXcodeLog"] = True
    return capabilities


class Appium:
    def __init__(self, base_url, udid, bundle_id, app=None, session_id=None,
                 device_name=None, platform_version=None, signing=None):
        self.signing = signing or {}
        self.base_url = base_url.rstrip("/")
        self.udid = udid
        self.bundle_id = bundle_id
        self.app = app
        self.session_id = session_id
        self.device_name = device_name
        self.platform_version = platform_version
        self.state_dir = DEFAULT_STATE_DIR

    def request(self, method, path, payload=None, timeout=60):
        data = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                body = response.read().decode()
                return json.loads(body) if body.strip() else {}
        except urllib.error.HTTPError as error:
            body = error.read().decode(errors="replace")
            try:
                value = json.loads(body).get("value")
                message = value.get("message") if isinstance(value, dict) else value
            except Exception:
                message = body[:500]
            raise ToolFailure(f"Appium HTTP {error.code}: {message or body[:500]}{device_hint(message or body)}") from error
        except urllib.error.URLError as error:
            raise ToolFailure(f"Appium is not reachable at {self.base_url}: {error.reason}") from error

    def ensure_session(self, exclude=None):
        """Return a session, sharing one per device across bridge processes.

        Every chat starts its own gateway and bridge. A second XCUITest session on
        the same device makes iOS revoke the first one ("Not authorized for
        performing UI testing actions"), so bridges reuse the session recorded in
        a per-device state file instead of creating their own.
        """
        if self.session_id and self.session_id != exclude:
            return self.session_id
        with self.session_lock():
            shared = self.read_shared_session()
            if shared and shared != exclude and self.session_alive(shared):
                self.session_id = shared
                return shared
            for stale in {exclude, shared} - {None}:
                self.delete_session(stale)
            self.session_id = self.create_session()
            self.write_shared_session(self.session_id)
            return self.session_id

    def create_session(self):
        capabilities = {
            "platformName": "iOS",
            "appium:automationName": "XCUITest",
            "appium:udid": self.udid,
            "appium:noReset": True,
            "appium:newCommandTimeout": 600,
        }
        if self.bundle_id:
            capabilities["appium:bundleId"] = self.bundle_id
        if self.app:
            capabilities["appium:app"] = str(Path(self.app).resolve())
        if self.device_name:
            capabilities["appium:deviceName"] = self.device_name
        if self.platform_version:
            capabilities["appium:platformVersion"] = self.platform_version
        capabilities.update(signing_capabilities(**self.signing))
        result = self.request("POST", "/session", {"capabilities": {"alwaysMatch": capabilities}}, timeout=600)
        return result["value"]["sessionId"]

    def session_alive(self, sid):
        try:
            self.request("GET", f"/session/{sid}/timeouts", timeout=15)
            return True
        except (ToolFailure, OSError):
            return False

    def delete_session(self, sid):
        try:
            self.request("DELETE", f"/session/{sid}", timeout=30)
        except (ToolFailure, OSError):
            pass

    def state_file(self):
        if self.state_dir is None:
            return None
        safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in self.udid)
        return Path(self.state_dir) / f"{safe}.json"

    @contextlib.contextmanager
    def session_lock(self):
        path = self.state_file()
        if path is None:
            yield
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path.with_suffix(".lock"), "w") as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(handle, fcntl.LOCK_UN)

    def read_shared_session(self):
        path = self.state_file()
        if path is None or not path.exists():
            return None
        try:
            data = json.loads(path.read_text())
        except (OSError, ValueError):
            return None
        return data.get("session_id") if data.get("appium_url") == self.base_url else None

    def write_shared_session(self, sid):
        path = self.state_file()
        if path is not None:
            path.write_text(json.dumps({"appium_url": self.base_url, "session_id": sid}))

    def call(self, method, endpoint, payload=None, timeout=60):
        sid = self.ensure_session()
        try:
            return self.request(method, f"/session/{sid}{endpoint}", payload, timeout)
        except ToolFailure as failure:
            if not is_stale_session_error(str(failure)):
                raise
        # The session died (device locked, WDA restarted, idle timeout): adopt a
        # newer shared session or create one, then retry once.
        self.session_id = None
        sid = self.ensure_session(exclude=sid)
        return self.request(method, f"/session/{sid}{endpoint}", payload, timeout)

    def source(self):
        return self.call("GET", "/source")["value"]

    def screenshot(self):
        return self.call("GET", "/screenshot")["value"]

    def rect(self):
        return self.call("GET", "/window/rect")["value"]

    def orientation(self):
        return self.call("GET", "/orientation")["value"]

    def find_elements(self, using, value):
        result = self.call("POST", "/elements", {"using": using, "value": value})
        return result.get("value", [])

    def find_one(self, using, value):
        result = self.call("POST", "/element", {"using": using, "value": value})
        element = result.get("value") or {}
        element_id = element.get("element-6066-11e4-a52e-4f735466cecf") or element.get("ELEMENT")
        if not element_id:
            raise ToolFailure("Element was not found")
        return element_id

    def element_rect(self, element_id):
        return self.call("GET", f"/element/{element_id}/rect")["value"]

    def click(self, element_id):
        self.call("POST", f"/element/{element_id}/click", {})

    def clear(self, element_id):
        self.call("POST", f"/element/{element_id}/clear", {})

    def type_text(self, element_id, text):
        self.call("POST", f"/element/{element_id}/value", {"text": text, "value": list(text)})

    def actions(self, actions):
        self.call("POST", "/actions", {"actions": actions})

    def execute(self, script, args):
        return self.call("POST", "/execute/sync", {"script": script, "args": args})


def parse_xml(source):
    try:
        return ET.fromstring(source)
    except ET.ParseError as error:
        raise ToolFailure(f"Invalid XCUITest source: {error}") from error


def node_record(element, index):
    attrs = element.attrib
    try:
        x = float(attrs.get("x", 0)); y = float(attrs.get("y", 0))
        width = float(attrs.get("width", 0)); height = float(attrs.get("height", 0))
    except ValueError:
        x = y = width = height = 0
    return {
        "index": index,
        "type": attrs.get("type", ""),
        "label": attrs.get("label", ""),
        "name": attrs.get("name", ""),
        "value": attrs.get("value", ""),
        "enabled": attrs.get("enabled") == "true",
        "visible": attrs.get("visible") == "true",
        "rect": {"x": x, "y": y, "width": width, "height": height},
    }


def all_nodes(source):
    root = parse_xml(source)
    nodes = []
    for element in root.iter():
        nodes.append(node_record(element, len(nodes)))
    return nodes


def selector_matches(node, selector):
    text = " ".join(str(node.get(key, "")) for key in ("label", "name", "value"))
    wanted = selector.get("text")
    if wanted:
        if selector.get("text_exact"):
            if wanted not in (node.get("label"), node.get("name"), node.get("value")):
                return False
        elif wanted.lower() not in text.lower():
            return False
    resource_id = selector.get("resource_id")
    if resource_id and resource_id not in (node.get("name"), node.get("label")):
        return False
    desc = selector.get("content_desc")
    if desc and desc.lower() not in (node.get("label") or "").lower():
        return False
    class_name = selector.get("class_name")
    if class_name and class_name != node.get("type"):
        return False
    return True


def find_matches(source, selector, limit=100):
    nodes = all_nodes(source)
    index = selector.get("index")
    if index is not None:
        return nodes[index:index + 1] if 0 <= index < len(nodes) else []
    return [node for node in nodes if selector_matches(node, selector)][:limit]


def tree_text(source, max_nodes):
    root = parse_xml(source)
    out = []
    def visit(element, depth):
        if len(out) >= max_nodes:
            return
        attrs = element.attrib
        label = attrs.get("label") or attrs.get("name") or ""
        value = attrs.get("value") or ""
        rect = f"[{attrs.get('x','0')},{attrs.get('y','0')}][{attrs.get('width','0')}x{attrs.get('height','0')}]"
        line = "  " * depth + attrs.get("type", "Element")
        if label:
            line += f' "{label[:120]}"'
        if value:
            line += f' value="{value[:120]}"'
        line += f" rect={rect}"
        if attrs.get("enabled") == "false":
            line += " disabled"
        out.append(line)
        for child in list(element):
            visit(child, depth + 1)
    visit(root, 0)
    if len(out) >= max_nodes:
        out.append(f"... truncated at {max_nodes} elements")
    return "\n".join(out)


def xpath_literal(text):
    if "'" not in text:
        return f"'{text}'"
    if '"' not in text:
        return f'"{text}"'
    parts = text.split("'")
    return "concat(" + ", \"'\", ".join(f"'{part}'" for part in parts) + ")"


def locator(selector):
    if selector.get("index") is not None:
        return "xpath", "//*"
    if selector.get("resource_id"):
        return "accessibility id", selector["resource_id"]
    if selector.get("text"):
        literal = xpath_literal(selector["text"])
        op = "=" if selector.get("text_exact") else "contains"
        if op == "=":
            expr = f"@label={literal} or @name={literal}"
        else:
            expr = f"contains(@label, {literal}) or contains(@name, {literal})"
        return "xpath", f"//*[{expr}]"
    if selector.get("content_desc"):
        literal = xpath_literal(selector["content_desc"])
        return "xpath", f"//*[contains(@label, {literal})]"
    if selector.get("class_name"):
        return "xpath", f"//*[@type={xpath_literal(selector['class_name'])}]"
    raise ToolFailure("Provide a selector: text, resource_id, content_desc, class_name or index")


def element_id(app, selector):
    using, value = locator(selector)
    elements = app.find_elements(using, value)
    if not elements:
        raise ToolFailure(f"No element matches {selector}")
    if using == "xpath" and value == "//*":
        position = selector.get("index", 0)
        if not (0 <= position < len(elements)):
            raise ToolFailure(f"Element index {position} is out of range")
        element = elements[position]
    else:
        element = elements[0]
    result = element.get("element-6066-11e4-a52e-4f735466cecf") or element.get("ELEMENT")
    if not result:
        raise ToolFailure("Element id was not returned")
    return result


def center(app, selector):
    eid = element_id(app, selector)
    rect = app.element_rect(eid)
    return rect["x"] + rect["width"] / 2, rect["y"] + rect["height"] / 2


def pointer_actions(points, duration_ms=50, interval_ms=100):
    actions = []
    for index, (x, y) in enumerate(points):
        if index:
            actions.append({"type": "pause", "duration": interval_ms})
        actions.extend([
            {"type": "pointerMove", "duration": 0, "x": int(x), "y": int(y)},
            {"type": "pointerDown", "button": 0},
            {"type": "pause", "duration": duration_ms},
            {"type": "pointerUp", "button": 0},
        ])
    return [{"type": "pointer", "id": "finger1", "parameters": {"pointerType": "touch"}, "actions": actions}]


def swipe_actions(start, end, duration_ms=350):
    return [{
        "type": "pointer", "id": "finger1", "parameters": {"pointerType": "touch"},
        "actions": [
            {"type": "pointerMove", "duration": 0, "x": int(start[0]), "y": int(start[1])},
            {"type": "pointerDown", "button": 0},
            {"type": "pointerMove", "duration": duration_ms, "x": int(end[0]), "y": int(end[1])},
            {"type": "pointerUp", "button": 0},
        ],
    }]


def pinch_actions(center_x, center_y, start_radius, end_radius, duration_ms=350):
    def source(identifier, sign):
        return {
            "type": "pointer", "id": identifier, "parameters": {"pointerType": "touch"},
            "actions": [
                {"type": "pointerMove", "duration": 0, "x": int(center_x + sign * start_radius), "y": int(center_y)},
                {"type": "pointerDown", "button": 0},
                {"type": "pointerMove", "duration": duration_ms, "x": int(center_x + sign * end_radius), "y": int(center_y)},
                {"type": "pointerUp", "button": 0},
            ],
        }
    return [source("finger1", -1), source("finger2", 1)]


def wait_result(app, condition, selector, expected, timeout_ms, interval_ms, quiet_ms):
    started = time.monotonic()
    deadline = started + timeout_ms / 1000
    last_hash = None
    changed_at = started
    actual = "not evaluated"
    while time.monotonic() <= deadline:
        source = app.source()
        if condition == "idle":
            import hashlib
            digest = hashlib.sha256(source.encode()).hexdigest()
            now = time.monotonic()
            if digest != last_hash:
                last_hash = digest
                changed_at = now
            actual = f"stable {int((now - changed_at) * 1000)}ms"
            if (now - changed_at) * 1000 >= quiet_ms:
                return True, actual, int((now - started) * 1000)
        else:
            matches = find_matches(source, selector, 100)
            actual = f"matches={len(matches)}"
            passed = {
                "visible": bool(matches),
                "absent": not matches,
                "text": bool(matches),
                "count": len(matches) == int(expected or -1),
            }.get(condition, False)
            if passed:
                return True, actual, int((time.monotonic() - started) * 1000)
        time.sleep(interval_ms / 1000)
    return False, actual, int((time.monotonic() - started) * 1000)


def tool_result(text, is_error=False, image=None):
    content = []
    if image:
        content.append({"type": "image", "mimeType": "image/png", "data": image})
    content.append({"type": "text", "text": text})
    return {"content": content, "isError": is_error}


def handle_tool(app, name, arguments):
    if name == "get_screen_tree":
        return tool_result(tree_text(app.source(), int(arguments.get("max_nodes", 300))))
    if name == "take_screenshot":
        return tool_result("Appium screenshot captured.", image=app.screenshot())
    if name == "ui_find":
        limit = min(max(int(arguments.get("limit", 20)), 1), 100)
        matches = find_matches(app.source(), arguments, limit)
        return tool_result(json.dumps({"count": len(matches), "matches": matches}, indent=2))
    if name == "ui_wait":
        condition = arguments.get("condition")
        passed, actual, elapsed = wait_result(
            app, condition, arguments, arguments.get("expected", ""),
            int(arguments.get("timeout_ms", 5000)), int(arguments.get("interval_ms", 250)),
            int(arguments.get("quiet_ms", 700)),
        )
        detail = f"condition={condition} elapsed={elapsed}ms actual={actual}"
        return tool_result(("WAIT OK: " if passed else "WAIT TIMEOUT: ") + detail, not passed)
    if name == "ui_assert":
        condition = arguments.get("condition")
        selector = dict(arguments)
        expected = arguments.get("expected", "")
        if condition == "text" and not selector.get("text") and expected:
            selector["text"] = expected
        if not selector.get("text") and not selector.get("resource_id") and not selector.get("content_desc") and not selector.get("class_name") and selector.get("index") is None and condition != "count":
            raise ToolFailure("Provide a selector")
        if condition == "count" and not expected.isdigit():
            raise ToolFailure("condition=count requires an integer expected")
        passed, actual, elapsed = wait_result(
            app, condition, selector, expected, int(arguments.get("timeout_ms", 5000)), 250, 700,
        )
        if passed:
            return tool_result(f"ASSERTION PASSED: condition={condition} expected={expected} actual={actual} elapsed={elapsed}ms")
        tree = tree_text(app.source(), 80)[:4000]
        return tool_result(
            f"ASSERTION FAILED: condition={condition} expected={expected} actual={actual} elapsed={elapsed}ms\n--- tree ---\n{tree}",
            True, app.screenshot(),
        )
    if name == "ui_gesture":
        kind = arguments.get("type")
        if arguments.get("x") is not None and arguments.get("y") is not None:
            x, y = float(arguments["x"]), float(arguments["y"])
        else:
            x, y = center(app, arguments)
        duration = int(arguments.get("duration_ms", 50 if kind == "tap" else 350))
        if kind == "tap":
            app.actions(pointer_actions([(x, y)], duration))
        elif kind == "double_tap":
            app.actions(pointer_actions([(x, y), (x, y)], duration, 120))
        elif kind == "multi_tap":
            count = min(max(int(arguments.get("count", 3)), 2), 10)
            app.actions(pointer_actions([(x, y)] * count, duration, int(arguments.get("interval_ms", 120))))
        elif kind == "long_press":
            app.actions(pointer_actions([(x, y)], max(duration, 800)))
        elif kind in ("swipe", "drag"):
            to_x = arguments.get("to_x"); to_y = arguments.get("to_y")
            if to_x is None or to_y is None:
                raise ToolFailure(f"{kind} requires to_x and to_y")
            app.actions(swipe_actions((x, y), (float(to_x), float(to_y)), duration))
        elif kind == "pinch":
            radius = float(arguments.get("radius", 220))
            direction = arguments.get("direction", "out")
            start, end = (radius, 80) if direction == "in" else (80, radius)
            app.actions(pinch_actions(x, y, start, end, duration))
        else:
            raise ToolFailure("type must be tap, double_tap, multi_tap, long_press, swipe, drag or pinch")
        return tool_result(f"Gesture '{kind}' dispatched at ({int(x)}, {int(y)}).")
    if name == "click_element":
        eid = element_id(app, arguments)
        if arguments.get("long_press"):
            rect = app.element_rect(eid)
            app.actions(pointer_actions([(rect["x"] + rect["width"] / 2, rect["y"] + rect["height"] / 2)], 800))
        else:
            app.click(eid)
        return tool_result("Element clicked.")
    if name == "input_text":
        text = arguments.get("text", "")
        eid = element_id(app, arguments)
        app.clear(eid)
        app.type_text(eid, text)
        return tool_result(f"Text entered ({len(text)} chars).")
    if name == "scroll":
        direction = arguments.get("direction")
        rect = app.rect()
        x = rect["x"] + rect["width"] / 2
        y = rect["y"] + rect["height"] / 2
        dx, dy = rect["width"] * 0.35, rect["height"] * 0.35
        starts = {"down": (x, y + dy), "up": (x, y - dy), "right": (x + dx, y), "left": (x - dx, y)}
        ends = {"down": (x, y - dy), "up": (x, y + dy), "right": (x - dx, y), "left": (x + dx, y)}
        if direction not in starts:
            raise ToolFailure("direction must be up, down, left or right")
        app.actions(swipe_actions(starts[direction], ends[direction], 350))
        return tool_result(f"Scrolled {direction}.")
    if name == "swipe":
        rect = app.rect()
        x = arguments.get("x"); y = arguments.get("y")
        to_x = arguments.get("to_x"); to_y = arguments.get("to_y")
        if x is not None and y is not None and to_x is not None and to_y is not None:
            start, end = (float(x), float(y)), (float(to_x), float(to_y))
        else:
            direction = arguments.get("direction", "up")
            cx, cy = rect["x"] + rect["width"] / 2, rect["y"] + rect["height"] / 2
            dx, dy = rect["width"] * 0.35, rect["height"] * 0.35
            starts = {"down": (cx, cy + dy), "up": (cx, cy - dy), "right": (cx + dx, cy), "left": (cx - dx, cy)}
            ends = {"down": (cx, cy - dy), "up": (cx, cy + dy), "right": (cx - dx, cy), "left": (cx + dx, cy)}
            if direction not in starts:
                raise ToolFailure("direction must be up, down, left or right")
            start, end = starts[direction], ends[direction]
        app.actions(swipe_actions(start, end, int(arguments.get("duration_ms", 350))))
        return tool_result("Swipe dispatched.")
    if name == "global_action":
        action = arguments.get("action")
        if action == "home":
            app.call("POST", "/wda/pressButton", {"name": "home"})
            return tool_result("Pressed home.")
        if action == "lock":
            app.call("POST", "/wda/lock")
            return tool_result("Locked device.")
        raise ToolFailure("Only home and lock are supported on iOS.")
    if name == "launch_app":
        bundle = arguments.get("bundle_id") or arguments.get("package_name")
        if not bundle:
            raise ToolFailure("Provide bundle_id or package_name")
        app.execute("mobile: launchApp", [{"bundleId": bundle}])
        return tool_result(f"Launched {bundle}.")
    if name == "get_device_info":
        return tool_result(json.dumps({
            "appium": app.base_url,
            "udid": app.udid,
            "bundle_id": app.bundle_id,
            "orientation": app.orientation(),
            "window": app.rect(),
        }, indent=2))
    if name == "device_state":
        if arguments.get("action") == "set":
            rotation = arguments.get("rotation", "portrait").upper()
            app.call("POST", "/orientation", {"orientation": rotation})
            return tool_result(f"Orientation set to {rotation}.")
        return tool_result(json.dumps({"orientation": app.orientation(), "window": app.rect()}, indent=2))
    raise ToolFailure(f"Unknown tool: {name}")


def error(request_id, code, message):
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def handle(app, request):
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
            "serverInfo": {"name": "Tester iOS UI Bridge", "version": "0.1.5"},
            "instructions": "Appium/XCUITest UI bridge for one iOS app. Use get_screen_tree first.",
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        name = params.get("name")
        arguments = params.get("arguments", {})
        if not isinstance(arguments, dict):
            return error(request_id, -32602, "arguments must be an object")
        try:
            result = handle_tool(app, name, arguments)
        except ToolFailure as failure:
            result = tool_result(str(failure), True)
        except Exception as failure:
            result = tool_result(f"{type(failure).__name__}: {failure}", True)
    else:
        return error(request_id, -32601, f"Unknown method: {method}")
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--appium-url", default="http://127.0.0.1:4723")
    parser.add_argument("--udid", required=True)
    parser.add_argument("--bundle-id", default=None)
    parser.add_argument("--app", default=None)
    parser.add_argument("--session-id", default=None)
    parser.add_argument("--device-name", default=None)
    parser.add_argument("--platform-version", default=None)
    parser.add_argument("--xcode-org-id", default=None,
                        help="Apple Team ID used to sign WebDriverAgent (physical devices)")
    parser.add_argument("--xcode-signing-id", default=None,
                        help='Signing identity, default "Apple Development"')
    parser.add_argument("--wda-bundle-id", default=None,
                        help="Unique WebDriverAgent bundle id for your team")
    parser.add_argument("--show-xcode-log", action="store_true")
    args = parser.parse_args()
    signing = {
        "xcode_org_id": args.xcode_org_id,
        "xcode_signing_id": args.xcode_signing_id,
        "wda_bundle_id": args.wda_bundle_id,
        "show_xcode_log": args.show_xcode_log,
    }
    app = Appium(args.appium_url, args.udid, args.bundle_id, args.app,
                 args.session_id, args.device_name, args.platform_version, signing)
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            response = error(None, -32700, "Invalid JSON")
        else:
            response = handle(app, request)
        if response is not None:
            print(json.dumps(response), flush=True)


if __name__ == "__main__":
    main()
