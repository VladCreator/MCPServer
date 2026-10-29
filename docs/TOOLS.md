# Supported tools — Tester 0.1.6

Tester exposes **29 tools** over MCP on Android (17 device tools + 10 QA-pack tools,
YAML route execution and capability discovery).
The iOS QA service advertises a 13-tool subset: `get_device_info`, `health_check`,
`read_file`, `create_notification`, `take_photo`, `get_app_version`,
`get_screen_tree`, `take_screenshot`, `get_logs`, `qa_session`,
`audio_instruction`, `audio_state`, `get_device_state`; see
[`IOS_QA_SERVICE.md`](IOS_QA_SERVICE.md).

In DeepSeek Harness the names are namespaced as
`mcp__<serverName>__<toolName>`. With `serverName: android`:

```
mcp__android__get_device_info
mcp__android__read_file
...
```

The in-app **Tools** sheet (grid icon in the header) shows the same catalog with the
requirements of each tool.

## Catalog

| Tool | Arguments | Requires | Approval | Result |
| --- | --- | --- | --- | --- |
| `get_device_info` | none | — | No | Text: Android version, manufacturer, model, app version, local IP. |
| `read_file` | `path` (string, required) | Path inside the app sandbox; max 64 KB; UTF-8 text only | Yes | Text with path, size and contents. |
| `create_notification` | `title` (string, required), `message` (string) | `POST_NOTIFICATIONS` permission | No | Text with the notification id. |
| `take_photo` | `quality` (string, optional, currently ignored) | Camera permission; the app must be in the foreground | Yes | MCP `image` block (`image/jpeg`) plus the file path. |
| `touch_test` | `x`, `y` (integer, required; screen pixels) | **MCP Touch Service** enabled | Yes | Text confirming the tap. |
| `launch_app` | `package_name` or `app_name`; optional `wait_ms` (default 8000, 0 disables) | Installed app with a launcher activity; from the background also needs overlay permission or MCP Touch Service | Yes | Text confirming the launch. |
| `list_apps` | `query` (string, optional) | — | No | Launchable apps with visible name and package; optionally filtered. |
| `health_check` | none | — | No | Server time, uptime, version, Touch Service and special-access state. |
| `get_capabilities` | none | Android | No | Structured per-tool support, missing permissions, execution location, mutation/risk classification and unsupported controls. |
| `swipe` | `direction` or `x`/`y`/`to_x`/`to_y`; `duration_ms`, `distance`, `count`, `interval_ms` | **MCP Touch Service** enabled | Yes | Text confirming the swipe. |
| `get_app_version` | `package_name` (required) | Installed app or Tester | No | versionName, versionCode and install/update times. |
| `get_logs` | `package_name`, `level`, `query`, `lines`, `crashes` | `READ_LOGS` via ADB for other apps' logs | Yes | Filtered logcat text. |
| `get_screen_tree` | `max_nodes` (integer, optional; default 300, range 1–1000) | **MCP Touch Service** enabled | Yes | Indented text tree: index, class, text, content description, resource id, bounds and flags. |
| `take_screenshot` | `max_dimension` (integer, optional; default 1280, range 320–2400) | **MCP Touch Service** enabled; unavailable on `FLAG_SECURE` screens | Yes | MCP `image` block (`image/jpeg`) plus a scale hint. |
| `click_element` | One of `index`, `text`, `resource_id`, `content_desc`, `class_name`; optional `text_exact`, `nth`, `long_press` | **MCP Touch Service** enabled | Yes | Text describing the clicked element. |
| `input_text` | `text` (string, required, max 5000 chars); optional `submit`; optional `index`, `match_text`, `resource_id`, `content_desc` | **MCP Touch Service** enabled; target must be editable | Yes | Text describing the field and whether submit was pressed. |
| `scroll` | `direction` (`up`, `down`, `left`, `right`, required); optional `index`, `text`, `resource_id`, `content_desc` | **MCP Touch Service** enabled | Yes | Text with the scrolling method used. |
| `global_action` | `action`: `back`, `home`, `recents`, `notifications`, `quick_settings`, `lock_screen` (required) | **MCP Touch Service** enabled | Yes | Text confirming the action. |
| `qa_session` | `action`: `begin`, `step`, `status`, `end`; `test_case`, `title`, `expected`, `actual`, `status`, `result`, `notes` | App sandbox for reports | No | Session text; end writes JSON + Markdown. |
| `ui_find` | Selector: `text`, `text_exact`, `resource_id`, `content_desc`, `class_name`, `index`; `include_invisible`, `limit`, `nth` (absolute `match_index`) | **MCP Touch Service** enabled | No | JSON: count + matches with bounds/flags. |
| `ui_wait` | `condition`: `visible`, `absent`, `text`, `idle`; selector; `expected`, `text_mode`, `ignore_case`, `timeout_ms`, `interval_ms`, `quiet_ms`, `package_name` | **MCP Touch Service** enabled | No | Text plus structured predicate result; every condition checks foreground/window. |
| `ui_assert` | `condition`: `visible`, `absent`, `text`, `count`; selector; `expected`, `text_mode`, `ignore_case`, `package_name`, `timeout_ms`, `max_dimension` | **MCP Touch Service** enabled | No | Text plus structured predicate result; failure includes screenshot + tree excerpt when capture is available. |
| `ui_gesture` | `type`: `tap`, `double_tap`, `multi_tap`, `long_press`, `swipe`, `drag`, `pinch`; coordinates or selector; `count`, `interval_ms`, `duration_ms`, `direction`, `radius` | **MCP Touch Service** enabled | Yes | Text confirming the gesture. |
| `audio_instruction` | `action`: `speak`, `play`, `stop`, `set_volume`; `text`, `path`, `language`, `rate`, `volume`, `loop`, `wait` | Device TTS/audio; path inside sandbox | Yes | Text; TTS defaults to en-US. |
| `audio_state` | none | — | No | Volume, music active, TTS/player state. |
| `get_device_state` | none | — | No | Theme, rotation, font scale, volume, locale, accessibility, foreground package. |
| `device_state` | `action`: `set`, `restore`; `rotation`, `dark_mode`, `font_scale`, `animations`, `volume`, `keep_awake` | `WRITE_SETTINGS`/`WRITE_SECURE_SETTINGS` for some fields | Yes | Applied/restored fields or actionable error. |
| `ui_diff` | `action`: `save_baseline`, `compare`; `name`, `threshold`, `max_dimension` | **MCP Touch Service**; baseline in app sandbox | No | Diff percentage + PASS/FAIL; failure image. |
| `run_route` | `yaml` (required), `parameters` (object, optional) | Android; **MCP Touch Service** | Yes, once for expanded route | JSON step results/timings; stops at first failure. See [YAML routes](YAML_ROUTES.md). |

## Approval behavior

Sensitive tools are `read_file`, `take_photo`, `touch_test`, `launch_app`,
`get_screen_tree`, `take_screenshot`, `click_element`, `input_text`, `scroll`,
`global_action`, `swipe`, `get_logs`, `ui_gesture`, `audio_instruction` and
`device_state` and `run_route` (16 of 29).

When **Confirm sensitive tools** is enabled (default), the call pauses and the
dashboard shows **Tool approval**. Approve or reject within 60 seconds. The MCP client
must keep the call open; set `toolCallTimeoutMs` to at least 120000 in DSH.

`get_device_info`, `create_notification`, `list_apps`, `health_check`,
`get_app_version`, `get_capabilities`, `qa_session`, `ui_find`, `ui_wait`, `ui_assert`, `audio_state`,
`get_device_state` and `ui_diff` run without an extra approval.

## Requirements

- **MCP Touch Service**: Android Settings → Accessibility → MCP Touch Service. The app
  has a shortcut in **Settings → Device access**. Needed by seven tools:
  `touch_test`, `get_screen_tree`, `take_screenshot`, `click_element`, `input_text`,
  `scroll`, `global_action`.
- **Camera**: runtime permission; `take_photo` only runs while the Tester app is in
  the foreground.
- **Notifications**: runtime `POST_NOTIFICATIONS` permission.
- **Files**: `read_file` is restricted to the app's sandbox (`files`, `cache`,
  external files). It is not a general-purpose file browser.
- **TTS/audio**: `audio_instruction` uses the device TTS/audio; no extra permission. Audio
  paths must be inside the app sandbox.
- **Device state**: `device_state` needs `WRITE_SETTINGS` for rotation/font and
  `WRITE_SECURE_SETTINGS` for dark mode/animations. On Android, the reliable dark-mode
  path is `adb shell cmd uimode night yes|no`; the app's secure-setting write may need a
  configuration change before the UI follows.
- **Visual baselines**: `ui_diff` stores JPEG baselines under `files/qa/baselines`.
- **Launch app**: `package_name` must match
  `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$` and the app must have a
  launcher activity.

## Recommended UI automation loop

In 0.1.6, text checks require a selector identifying one candidate. `expected`
compares its actual text, exactly and case-sensitively by default. Use
`text_mode: "contains"` and/or `ignore_case: true` to change the value comparison.
These options are independent of `text_exact`, which controls selector matching.
Without `expected`, a `text` selector retains its selector-based behavior; multiple
text candidates fail as ambiguous rather than returning a false positive.
For compatibility, `ui_assert` can infer an exact text selector from a nonempty
`expected` when no selector is supplied; empty-text expectations need an explicit
field selector.

All wait/assert conditions require an active window and the requested
`package_name`, or bind to the initial foreground package if omitted. A missing
window cannot satisfy `absent` or zero count. Counts of 100 or more are conservatively
reported as `truncated_count`, not an exact total.

Predicate results include MCP `structuredContent` alongside existing text/images:
`condition`, `passed`, `reason`, `expected`, `actual`, `truncated`, `elapsed_ms`
and `retryable`. No action is automatically retried. `device_state restore` now
returns an error when no snapshot exists or restoration is partial; partial
failures retain the snapshot. `launch_app` readiness timeout is also an error,
although the app launch itself may already have occurred.

Capability discovery does not grant authorization. `conditional` indicates target,
screen or operation-specific requirements; `blocked` includes missing permissions.
`requires_confirmation_when_enabled` reflects the sensitive-tool policy, not the
current Settings toggle. Restoration checks stored settings and media volume;
effective UI configuration changes can still be OS-dependent.

```
get_screen_tree
  → click_element / input_text / scroll
  → get_screen_tree again
```

Use `take_screenshot` only when the tree is empty, ambiguous, or the screen is
graphical (games, maps, canvas). `get_screen_tree` is cheaper in tokens and gives the
indices used by `click_element`/`input_text`.

## Examples

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
  "name":"get_device_info","arguments":{}}}
```

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
  "name":"click_element","arguments":{"text":"Sign in"}}}
```

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
  "name":"input_text","arguments":{"match_text":"Email","text":"user@example.com","submit":true}}}
```

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
  "name":"global_action","arguments":{"action":"home"}}}
```

## Transport notes

- Streamable HTTP: `POST/GET/DELETE /mcp` (recommended).
- Legacy SSE: `GET /sse` + `POST /message`.
- WebSocket: `/mcp` and `/ws` (legacy; HTTP is the simplest for USB/ADB testing).
- USB: `adb forward tcp:8080 tcp:8080`, then `http://localhost:8080/mcp`.
  The app's **Connect** sheet shows the URL and the command.
- TLS: `https://<ip>:8443/mcp`; the client must trust the self-signed certificate.
