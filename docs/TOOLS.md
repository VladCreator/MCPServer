# Supported tools — MCPServer 0.0.2

MCPServer exposes **12 tools** over MCP. All are available in this version.

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
| `launch_app` | `package_name` (string, required) | Installed app with a launcher activity; from the background also needs overlay permission or MCP Touch Service | Yes | Text confirming the launch. |
| `get_screen_tree` | `max_nodes` (integer, optional; default 300, range 1–1000) | **MCP Touch Service** enabled | Yes | Indented text tree: index, class, text, content description, resource id, bounds and flags. |
| `take_screenshot` | `max_dimension` (integer, optional; default 1280, range 320–2400) | **MCP Touch Service** enabled; unavailable on `FLAG_SECURE` screens | Yes | MCP `image` block (`image/jpeg`) plus a scale hint. |
| `click_element` | One of `index`, `text`, `resource_id`, `content_desc`; optional `long_press` (boolean) | **MCP Touch Service** enabled | Yes | Text describing the clicked element. |
| `input_text` | `text` (string, required, max 5000 chars); optional `submit`; optional `index`, `match_text`, `resource_id`, `content_desc` | **MCP Touch Service** enabled; target must be editable | Yes | Text describing the field and whether submit was pressed. |
| `scroll` | `direction` (`up`, `down`, `left`, `right`, required); optional `index`, `text`, `resource_id`, `content_desc` | **MCP Touch Service** enabled | Yes | Text with the scrolling method used. |
| `global_action` | `action`: `back`, `home`, `recents`, `notifications`, `quick_settings`, `lock_screen` (required) | **MCP Touch Service** enabled | Yes | Text confirming the action. |

## Approval behavior

Sensitive tools are `read_file`, `take_photo`, `touch_test`, `launch_app`,
`get_screen_tree`, `take_screenshot`, `click_element`, `input_text`, `scroll` and
`global_action` (10 of 12).

When **Confirm sensitive tools** is enabled (default), the call pauses and the
dashboard shows **Tool approval**. Approve or reject within 60 seconds. The MCP client
must keep the call open; set `toolCallTimeoutMs` to at least 120000 in DSH.

`get_device_info` and `create_notification` run without an extra approval.

## Requirements

- **MCP Touch Service**: Android Settings → Accessibility → MCP Touch Service. The app
  has a shortcut in **Settings → Device access**. Needed by seven tools:
  `touch_test`, `get_screen_tree`, `take_screenshot`, `click_element`, `input_text`,
  `scroll`, `global_action`.
- **Camera**: runtime permission; `take_photo` only runs while the MCPServer app is in
  the foreground.
- **Notifications**: runtime `POST_NOTIFICATIONS` permission.
- **Files**: `read_file` is restricted to the app's sandbox (`files`, `cache`,
  external files). It is not a general-purpose file browser.
- **Launch app**: `package_name` must match
  `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$` and the app must have a
  launcher activity.

## Recommended UI automation loop

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
