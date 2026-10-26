# MCPServer

A local [Model Context Protocol](https://modelcontextprotocol.io) server that runs on an
Android device. It exposes device tools to AI agents over the local network and shows
what is connected and what is being executed on a Compose dashboard.

Current version: **0.0.1** (Android-first; the iOS target compiles but the server is
not implemented there yet).

## Features

- **MCP over Streamable HTTP** at `POST/GET/DELETE /mcp` (current MCP transport, with
  `Mcp-Session-Id` sessions).
- **MCP over HTTP+SSE** (legacy transport) at `GET /sse` + `POST /message`.
- **WebSocket** at `/ws` for legacy/custom clients (not the primary transport).
- **JSON-RPC 2.0** dispatcher: `initialize`, `notifications/initialized`, `ping`,
  `tools/list`, `tools/call`, batching and standard error codes.
- **Hybrid authentication**: `Authorization: Bearer <token>` or manual approval from
  the dashboard (pending requests wait up to 60 seconds).
- **Per-tool confirmation**: sensitive tools (`read_file`, `take_photo`,
  `touch_test`, `launch_app`, screen navigation tools) pause and ask for **Approve tool / Reject** in the dashboard before
  executing. The confirmation can be disabled in Settings.
- **TLS/HTTPS** with a self-signed certificate generated on first use. The SHA-256
  fingerprint is shown in the dashboard.
- **SQLite** persistence for settings and command history (WAL, real timestamps,
  bounded history).
- **Dashboard**: server status, pending requests with remote address, connected agents,
  tool execution log and connection URLs.
- **Foreground service**: while the server runs, a `dataSync` foreground service keeps
  the process alive with a persistent notification.
- **Device tools**: camera capture (CameraX), tap injection (AccessibilityService),
  sandboxed file reads and notifications.

## UI

Material 3 with a custom obsidian/indigo palette, compact cards and bottom sheets for
secondary information. The main column is capped at 900 dp and centered on tablets,
so the dashboard stays readable instead of stretching across a large screen.

| Dashboard | Connection sheet |
| --- | --- |
| ![Dashboard](docs/screenshots/dashboard-dark.png) | ![Connection](docs/screenshots/connect.png) |
| **Settings sheet** | **Tablet layout** |
| ![Settings](docs/screenshots/settings.png) | ![Tablet](docs/screenshots/tablet.png) |

Settings, endpoints and the TLS fingerprint live in sheets so the dashboard only shows
what is actionable: server state, pending approvals, connected agents and recent
activity.

## Requirements

- macOS/Linux with JDK 21+ (the project was built with JDK 25).
- Android SDK with API 36 (the app `minSdk` is 36) and build-tools.
- An Android 16+ device or emulator on the same network as the AI client.

## Build and install

```bash
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

If an older build of the app is installed with a different signing key, Android
rejects the update with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the old app
first (this deletes its local database and settings) or install from the same
development environment.

## Run

1. Open **MCPServer** and tap **Start**. The server listens on:
   - `http://<device-ip>:8080/mcp` (Streamable HTTP)
   - `http://<device-ip>:8080/sse` (legacy SSE)
   - `ws://<device-ip>:8080/ws` (legacy WebSocket)
2. Open **Settings** and copy the bearer token. It is generated on first launch and
   stored in the app's database.
3. Configure your MCP client with the `/mcp` URL and the `Authorization: Bearer <token>`
   header. The app requests notification and camera permissions on first launch. For
   `touch_test`, open **Settings → Touch injection → Open** and enable
   **MCP Touch Service** in Android accessibility settings.

Example client configuration (the exact schema depends on the client):

```json
{
  "mcpServers": {
    "android-device": {
      "type": "http",
      "url": "http://192.168.1.50:8080/mcp",
      "headers": {
        "Authorization": "Bearer <your-token>"
      }
    }
  }
}
```

If the token is missing or incorrect, the request appears in **Pending Connection
Requests**. Tap **Approve** to let it continue or **Reject** to return `401` and close
it. The MCP client must keep the request open while you decide (the server times out
after 60 seconds).

### curl examples

```bash
TOKEN="<your-token>"
BASE="http://192.168.1.50:8080"

# Initialize and capture the session id
curl -i -X POST "$BASE/mcp" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'

# List tools (use the Mcp-Session-Id returned above)
curl -X POST "$BASE/mcp" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Mcp-Session-Id: <session-id>" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# Call a tool
curl -X POST "$BASE/mcp" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Mcp-Session-Id: <session-id>" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_device_info","arguments":{}}}'
```

## Endpoints

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/mcp` | Streamable HTTP JSON-RPC requests. Returns JSON or SSE. |
| `GET` | `/mcp` | Streamable HTTP server-to-client SSE stream (requires `Mcp-Session-Id`). |
| `DELETE` | `/mcp` | Ends a session. |
| `GET` | `/sse` | Legacy SSE stream; emits `event: endpoint` with `/message?sessionId=...`. |
| `GET` | `/mcp/sse` | Alias of `/sse`. |
| `POST` | `/message?sessionId=...` | Legacy SSE request endpoint; returns `202` and pushes the response to the stream. |
| `WS` | `/ws` | Legacy WebSocket transport. |
| `GET` | `/` | Human-readable server info. |
| `GET` | `/health` | `{"status":"ok"}`. |
| `GET` | `/status` | JSON status, port, session counts and TLS fingerprint. |

All MCP endpoints reject browser requests whose `Origin` is not a local development
origin (DNS-rebinding/CSRF protection) and limit request bodies to 256 KB.

## Tools

| Tool | Status | Description |
| --- | --- | --- |
| `get_device_info` | Available | Android version, manufacturer, model, app version and local IP. |
| `read_file` | Available | Reads UTF-8 text files inside the app sandbox (`files`, `cache`, external files). Max 64 KB. |
| `create_notification` | Available | Posts a system notification. Requires the notification permission. |
| `take_photo` | Available (foreground) | Captures a JPEG with CameraX and returns an MCP `image` content block plus the file path. The app must be in the foreground. |
| `touch_test` | Available (accessibility) | Injects a tap at `x,y`. Requires the user to enable **MCP Touch Service** in Android accessibility settings. |
| `launch_app` | Available (foreground, overlay or accessibility) | Launches an installed app by `package_name`. From the background it requires **Display over other apps** (Settings → Device access) or **MCP Touch Service** (Android background activity-start restrictions). |
| `get_screen_tree` | Available (accessibility) | Returns the foreground app's UI as indented text: index, class, text, description, resource id, bounds and flags. Preferred way for an AI to "see" the screen. |
| `take_screenshot` | Available (accessibility) | Captures the screen as a JPEG (`max_dimension`, default 1280). Fallback for games, maps or canvas UIs. Fails on `FLAG_SECURE` screens. |
| `click_element` | Available (accessibility) | Clicks or long-presses an element by `index` (from the last tree), `text`, `resource_id` or `content_desc`. |
| `input_text` | Available (accessibility) | Sets the text of an editable field (focused field or selector via `match_text`/`index`/`resource_id`/`content_desc`); optional `submit`. |
| `scroll` | Available (accessibility) | Scrolls `up`/`down`/`left`/`right` on the largest or selected scrollable element. |
| `global_action` | Available (accessibility) | `back`, `home`, `recents`, `notifications`, `quick_settings`, `lock_screen`. |

Recommended navigation loop for agents: `get_screen_tree` → `click_element` /
`input_text` / `scroll` → `get_screen_tree` again; use `take_screenshot` only when
the tree is empty or ambiguous.

Sensitive tools (`read_file`, `take_photo`, `touch_test`, `launch_app` and all
screen navigation tools) wait for an explicit
**Approve tool** action in the dashboard before running. The **Confirm sensitive
tools** switch in Settings disables this gate. Tools that cannot run in the current
state return `isError: true` with an actionable message instead of a fake success.
Touch injection is powerful: enable the accessibility service only if you trust the
clients that hold the bearer token.

## TLS

Enable **SSL/TLS** in Settings and restart the server. On first use the app generates a
self-signed certificate valid for two years with SAN entries for the device's IPv4
addresses, `localhost` and `127.0.0.1`:

- The certificate and key are stored in `files/mcp-tls.p12`.
- The SHA-256 fingerprint is shown in the dashboard and in `/status`.
- Clients must trust the certificate (or use "insecure/trust on first use" for local
  testing). If the device IP changes, the certificate is regenerated and the
  fingerprint changes.
- The bearer token is encrypted at rest with an AES-256-GCM key in the Android
  Keystore; the app database only stores the `enc:v1:` payload.

## Tests

```bash
./gradlew :shared:testAndroidHostTest   # protocol, auth, ViewModel
./gradlew :shared:compileKotlinIosSimulatorArm64
```

The suite (37 tests) covers JSON-RPC dispatch, protocol negotiation, tool schema
validation, errors/batches/notifications, bearer parsing, token storage behavior,
tool-approval events and the ViewModel event flow. Camera, accessibility, foreground
service and approval UI are verified manually on a device/emulator because they need
platform services.

## Architecture

```
androidApp/          Activity + Compose entry point
shared/
  commonMain/
    mcp/             JSON-RPC dispatcher, tools, sessions, auth, host interface
    App.kt           Compose dashboard and settings
    AppViewModel.kt  State, event reduction, persistence coordination
    Database.kt      SQLite (WAL, serialized access)
  androidMain/
    mcp/             Ktor server (CIO/Netty), Streamable HTTP/SSE/WS, TLS, tools
    McpServerKeepAliveService.kt      Foreground service + notification
    McpTouchAccessibilityService.kt   Tap injection
    CameraCapture.kt                  CameraX still capture
    SecretStorage.android.kt          Keystore AES/GCM token encryption
  iosMain/           Unsupported host (server is Android-only)
```

The server owns a session registry and an approval gate. Transports only translate
bytes; they all call the same `McpDispatcher` and `ToolExecutor`, so protocol behavior
is consistent across HTTP, SSE and WebSocket.

## Known limitations / next steps

- The keep-alive service uses `dataSync`; Android 15+ limits that foreground type to a
  few hours per day. A long-running host should use a dedicated foreground type and a
  battery-optimization exemption.
- `take_photo` only runs while the app is in the foreground. Capturing from the
  background would require a camera foreground service and Android's background
  camera restrictions.
- `touch_test` requires the user to enable the accessibility service manually.
- The per-tool confirmation can be disabled; when disabled, an approved session can
  call sensitive tools without another prompt.
- The app does not advertise itself over mDNS/Bonjour or show a QR code yet.
- Self-signed TLS requires client trust; for production, use a proper certificate or
  pin the fingerprint.

## Repository note

`recovery/` contains a forensic copy of an older Apply Changes overlay and its
decompiled classes. It is not part of the app and can be deleted once the source is
recovered/committed.
