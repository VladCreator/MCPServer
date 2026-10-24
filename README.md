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
- **TLS/HTTPS** with a self-signed certificate generated on first use. The SHA-256
  fingerprint is shown in the dashboard.
- **SQLite** persistence for settings and command history (WAL, real timestamps,
  bounded history).
- **Dashboard**: server status, pending requests with remote address, connected agents,
  tool execution log and connection URLs.

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
   header.

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
| `take_photo` | Not advertised yet | Needs CameraX, runtime permission and a foreground camera flow. |
| `touch_test` | Not advertised yet | Needs an AccessibilityService enabled by the user. |

Unavailable tools are intentionally omitted from `tools/list` instead of returning
fake success messages.

## TLS

Enable **SSL/TLS** in Settings and restart the server. On first use the app generates a
self-signed certificate valid for two years with SAN entries for the device's IPv4
addresses, `localhost` and `127.0.0.1`:

- The certificate and key are stored in `files/mcp-tls.p12`.
- The SHA-256 fingerprint is shown in the dashboard and in `/status`.
- Clients must trust the certificate (or use "insecure/trust on first use" for local
  testing). If the device IP changes, the certificate is regenerated and the
  fingerprint changes.

## Tests

```bash
./gradlew :shared:testAndroidHostTest   # protocol, auth, ViewModel
./gradlew :shared:compileKotlinIosSimulatorArm64
```

The suite covers JSON-RPC dispatch, protocol negotiation, tool schema validation,
errors/batches/notifications, bearer parsing and the ViewModel event flow.

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
  iosMain/           Unsupported host (server is Android-only)
```

The server owns a session registry and an approval gate. Transports only translate
bytes; they all call the same `McpDispatcher` and `ToolExecutor`, so protocol behavior
is consistent across HTTP, SSE and WebSocket.

## Known limitations / next steps

- The server is not a foreground service yet. It runs while the app process is alive;
  Android may kill it in the background. A foreground service with a persistent
  notification is the next P1 item.
- Camera capture and touch injection need additional platform services and user
  consent.
- The bearer token is stored in the app's SQLite database. App backup is disabled,
  but encrypting the token with the Android Keystore is still pending.
- Self-signed TLS requires client trust; for production, use a proper certificate or
  pin the fingerprint.

## Repository note

`recovery/` contains a forensic copy of an older Apply Changes overlay and its
decompiled classes. It is not part of the app and can be deleted once the source is
recovered/committed.
