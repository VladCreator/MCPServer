# Supported tools — Tester 0.2.3

Tester exposes **43 tools** over MCP on Android, including combined waits,
durable scenarios, reviewed route discovery, scoped CI consent, private evidence,
owned recovery and explicit environment controls.
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

P5 adds an optional [authenticated loopback host Viewer](P5_0.2.3.md) consuming
`get_run`, `export_run` and existing `get_artifact`; it does not add another MCP
tool or grant run ownership. Android remains at 43 tools. Recording, live capture,
microphone and privileged advanced adapters remain unavailable.

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
| `observe_screen` | `include_screenshot` (default false), `max_nodes` (default 300, max 1000), `max_dimension` | Android **MCP Touch Service**; image denied for secure windows | Yes | Structured bounded tree with paths, references, package/window, rotation/dimensions, timestamps, visibility and instability. Optional window image with origin/scale. |
| `take_screenshot` | `max_dimension` (integer, optional; default 1280, range 320–2400) | **MCP Touch Service** enabled; unavailable on `FLAG_SECURE` screens | Yes | MCP `image` block (`image/jpeg`) plus a scale hint. |
| `click_element` | One of `index`, `text`, `resource_id`, `content_desc`, `class_name`; optional `text_exact`, `nth`, `long_press` | **MCP Touch Service** enabled | Yes | Text describing the clicked element. |
| `input_text` | `text` (string, required, max 5000 chars); optional `submit`; optional `index`, `match_text`, `resource_id`, `content_desc` | **MCP Touch Service** enabled; target must be editable | Yes | Text describing the field and whether submit was pressed. |
| `scroll` | `direction` (`up`, `down`, `left`, `right`, required); optional `index`, `text`, `resource_id`, `content_desc` | **MCP Touch Service** enabled | Yes | Text with the scrolling method used. |
| `global_action` | `action`: `back`, `home`, `recents`, `notifications`, `quick_settings`, `lock_screen` (required) | **MCP Touch Service** enabled | Yes | Text confirming the action. |
| `qa_session` | `action`: `begin`, `step`, `status`, `end`; `test_case`, `title`, `expected`, `actual`, `status`, `result`, `notes`, optional cleanup `state_key` | App sandbox for reports | No | Session text; end writes JSON + Markdown; owned pending cleanup needs key and makes report FAIL if restoration fails. |
| `ui_find` | Selector: `text`, `text_exact`, `resource_id`, `content_desc`, `class_name`, `index`, `within`, `visibility`; `include_invisible`, `limit`, `nth` (absolute `match_index`) | **MCP Touch Service** enabled | No | Structured JSON: count, observation metadata, matches with visibility/flags and references where a bounded tree path is available. |
| `ui_wait` | `condition`: `visible`, `absent`, `text`, `idle`; selector; `expected`, `text_mode`, `ignore_case`, `timeout_ms`, `interval_ms`, `quiet_ms`, `package_name` | **MCP Touch Service** enabled | No | Text plus structured predicate result; every condition checks foreground/window. |
| `ui_assert` | `condition`: `visible`, `absent`, `text`, `count`; selector; `expected`, `text_mode`, `ignore_case`, `package_name`, `timeout_ms`, `max_dimension` | **MCP Touch Service** enabled | No | Text plus structured predicate result; failure includes screenshot + tree excerpt when capture is available. |
| `ui_gesture` | `type`: `tap`, `double_tap`, `multi_tap`, `long_press`, `swipe`, `drag`, `pinch`; coordinates or selector; `count`, `interval_ms`, `duration_ms`, `direction`, `radius` | **MCP Touch Service** enabled | Yes | Text confirming the gesture. |
| `audio_instruction` | `action`: `speak`, `play`, `stop`, `set_volume`; `text`, `path`, `language`, `rate`, `volume`, `loop`, `wait` | Device TTS/audio; path inside sandbox | Yes | Text; TTS defaults to en-US. |
| `audio_state` | none | — | No | Volume, music active, TTS/player state. |
| `get_device_state` | none | — | No | Theme, rotation, font scale, volume, locale, accessibility, foreground package. |
| `device_state` | `action`: `set`, `restore`, `status`; required secret `state_key`; `rotation`, `font_scale`, `animations`, `volume`, `keep_awake` | `WRITE_SETTINGS` for rotation/font; `WRITE_SECURE_SETTINGS` for animations | Yes | Only touched originals persisted before writes; effective readback, owned recovery and pending failed fields. `dark_mode` explicitly unsupported. |
| `ui_diff` | `action`: `save_baseline`, `compare`; `name`, `threshold`, `max_dimension` | **MCP Touch Service**; baseline in app sandbox | No | Diff percentage + PASS/FAIL; failure image. |
| `run_route` | Exactly one of `yaml`, structured `scenario`, or catalog `route_id`/`version`/`digest`; optional `parameters`, `mode`, `run_key`, `submission_id`, `disconnect_policy`, `queue_timeout_ms`, `privacy_sensitive`, `policy_id` | Android; **MCP Touch Service** | Once for expanded plan, or exact approved catalog policy | Same native lifecycle for YAML/JSON/catalog; schema 2 phases and automatic touched-state cleanup. |
| `list_routes` | none | Bundled Android catalog | No | Three reviewed fixture route IDs, exact versions and SHA-256 digests; no execution authority. |
| `get_route` | `route_id`, exact `version` | Bundled Android catalog | No | Reviewed definition YAML and digest; immutable to clients. |
| `route_policy` | `action`: `grant`/`status`/`revoke`; grant: exact catalog identity, optional `parameters`, `privacy_sensitive`, `max_runs` (1–30), `ttl_ms` (1000–900000); status/revoke: `policy_id` | Approved session; exact expanded catalog plan | Grant **always** asks, irrespective of global confirmation setting | Session/process-scoped bounded attempts; privacy/parameter/plan changes fail closed. No global unattended bypass. |
| `wait_for_state` | `all` (1–10 predicates), `package_name`, `timeout_ms`, `interval_ms` | **MCP Touch Service** | No | One shared deadline, per-condition outcomes/timestamps and failed indices; unstable polls cannot pass. |
| `get_run` | `run_id`, `run_key` | Approved session and recovery key | No | Durable redacted status/timeline; no replay. |
| `cancel_run` | `run_id`, `run_key` | Approved session and recovery key | No | Cooperative cancellation; pending/unknown outcomes remain explicit. |
| `get_build_identity` | `package_name` | Installed/visible package | No | Package/version and optional schema-1 manifest SHA/artifact, labeled app-reported/unverified; absent metadata stays unknown. |
| `collect_failure_bundle` | `run_id`, `run_key`, approved `package_name`, optional `include_screenshot` | Owned run; accessibility/log permissions per source | Yes | Bounded redacted manifest and artifact ID; unavailable sources explicit, partial failures return `isError`. |
| `export_run` | `run_id`, `run_key`, `format`: `json`/`markdown`/`junit` | Owned run | No | Canonical redacted report artifact ID and identical bounded inline `content`; safe CI download without image-read approval. |
| `get_artifact` | `run_id`, `run_key`, `artifact_id` | Artifact belongs to owned run | Yes | Stored JSON/Markdown or explicitly approved unmasked image; no arbitrary paths. |
| `open_link` | Required `package_name`, `uri`; `wait_ms` (250–30000, default 5000) | Accessibility; one exported browsable target handler | Yes | Explicit app launch and foreground/quiet readiness; no arbitrary extras/flags or dangerous system/file URI schemes. |
| `clipboard` | `action`: `write`, `read_owned`, `clear_owned`; `text` for write (max 5000) | Tester foreground; same approved session/process owns read/clear | Yes | Plain-text write-first access, never unrelated clipboard snapshots; text omitted from command history. |
| `get_test_environment` | `package_name` | Installed/visible target; app-locale access can be denied | No | Manifest permission flags, inspectable locale override and explicit unsupported setup controls; no privileged mutations. |

## Approval behavior

Sensitive tools are `read_file`, `take_photo`, `touch_test`, `launch_app`,
`get_screen_tree`, `take_screenshot`, `click_element`, `input_text`, `scroll`,
`global_action`, `swipe`, `get_logs`, `ui_gesture`, `audio_instruction` and
`device_state`, `run_route`, `observe_screen`, `collect_failure_bundle` and
`get_artifact`, `open_link`, `clipboard` and `route_policy` (22 of 43).

When **Confirm sensitive tools** is enabled (default), the call pauses and the
dashboard shows **Tool approval**. Approve or reject within 60 seconds. The MCP client
must keep the call open; set `toolCallTimeoutMs` to at least 120000 in DSH.

`get_device_info`, `create_notification`, `list_apps`, `health_check`,
`get_app_version`, `get_capabilities`, `qa_session`, `ui_find`, `ui_wait`, `ui_assert`, `audio_state`,
`get_device_state`, `ui_diff`, `wait_for_state`, `get_run`, `cancel_run`,
`get_build_identity`, `export_run`, `get_test_environment`, `list_routes` and `get_route`
run without an extra approval. `route_policy status/revoke` also need no dialog;
grant always shows the full expanded plan, attempt limit, privacy and expiry.
Policies authorize only fresh exact-plan catalog attempts in that approved session;
duplicates return the original run without consuming another attempt.

Run recovery keys are bearer capabilities, not client names: another approved
session must explicitly possess the key. Private run/evidence tool payloads and
results are omitted from native history and gateway reports. Evidence omits all
free text and log message bodies. Images are omitted by default; unmasked capture
requires admitting the run with `privacy_sensitive:false` and explicitly requesting
it in collection. Direct synchronous responses can still contain approved live UI
text. See [P2 behavior and limits](P2_0.2.0.md).

Since 0.2.1, environment tool payloads/results (`device_state`, `qa_session`,
`open_link`, `clipboard`) are also omitted from command history/gateway reports.
This does not redact caller-authored QA step text from explicit QA reports.
The gateway does not replay environment mutations after session loss. Retain
`state_key` before setup; recovery requires it even in a new approved session.
See [P3 controls and recovery](P3_0.2.1.md) for the deliberate migration and limits.
Since 0.2.2, scenario setup uses its retained `run_key` as the P3 `state_key` for
automatic restoration and manual recovery. Policy payloads/results are omitted
from history. See [P4 scenarios and CI](P4_0.2.2.md).

## Requirements

- **MCP Touch Service**: Android Settings → Accessibility → MCP Touch Service. The app
  has a shortcut in **Settings → Device access**. Core examples:
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
  `WRITE_SECURE_SETTINGS` for animations. Global dark-mode setting-only writes are
  rejected because they do not prove the visible UI changed. Device policies can
  also block volume/wake-lock operations. Setup never grants its own privileges.
- **Visual baselines**: `ui_diff` stores JPEG baselines under `files/qa/baselines`.
- **Launch app**: `package_name` must match
  `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$` and the app must have a
  launcher activity.

## Recommended UI automation loop

### P1 observation and targeting

Prefer `observe_screen` (tree-only by default), then one action using `element_ref`,
then observe again. `ui_find` also supplies references. References belong to the
approved client session, expire after 30 seconds, and are replaced by that client's
next observation/find. Window/content/text/scroll changes and successful actions
invalidate them conservatively. Resolution checks current window, rotation, screen
dimensions, generation, node identity, tree path and fingerprint immediately before
dispatch. Stale/foreign references and ambiguity return an error with
`action_dispatched=false`; no action is retried.

`click_element`, `input_text`, `scroll` and `ui_gesture` accept `element_ref` instead
of a selector. Input still uses `text` for the value. Do not mix a reference with
selector fields or selector gestures with x/y coordinates. Legacy `index` still
uses the process-global last `get_screen_tree` list: it is **not session-bound**
and is unsuitable for saved scenarios. Observation `node_index`/`path` and find
`match_index` are not legacy tree indices.

All selector-based find/wait/assert/action tools accept `within` (an ancestor
selector with text, text_exact, resource_id, content_desc and/or class_name).
It matches the ancestor itself, not arbitrary sibling text. For example:

```json
{"text":"Open","text_exact":true,"within":{"resource_id":"project_beta"}}
```

Actions require one candidate unless `nth` is explicitly supplied; there is no
silent exact-match preference. Input also rejects containers with multiple editable
descendants. A semantic child can resolve to its nearest editable ancestor; the
result identifies the actual field. Click fallback never uses an unrelated large
ancestor.

`visibility` supports `accessibility` (find/check default), `intersecting` and
`fully_visible` (action default). Results expose accessibility visibility, bounds,
ancestor/viewport intersection, clipping detected from parent-local dimensions,
fully-visible reported geometry and advisory actionability. Android may already
clip bounds or omit original geometry: these fields cannot prove full original
visibility or detect every overlay. `occlusion_known=false` makes this limitation
explicit. Actions always require a nonempty viewport intersection and an enabled
target, even with a looser policy.

Observations are **not atomic**: start/tree/end timestamps and instability flags
describe their capture interval. Unstable observations omit actionable references.
Paths retain ancestry through omitted non-meaningful nodes; truncation is explicit.
WebView metadata reports readable accessibility descendants, not DOM access or
complete content. No guessed-coordinate/OCR/DevTools fallback is attempted.

Optional images use Android's window screenshot API so FLAG_SECURE denial is
explicit. `screenshot.status=failed` sets `isError=true` while retaining tree
evidence. Map an image point using `screen_x=origin_x+image_x*scale_x` and the
corresponding y fields. Tree-only mode does not request image permission/capture.
Per-call consent UI can itself invalidate a reference; observe again rather than
replay. Unattended fixture runs require an explicitly configured consent policy.

### Predicate semantics (introduced in P0)

Text checks require a selector identifying one candidate. `expected`
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
observe_screen
  → click_element / input_text / scroll (element_ref)
  → observe_screen again
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
