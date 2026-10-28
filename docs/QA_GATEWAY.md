# Unified QA Gateway — 0.1.5

The QA Gateway is a **stdio MCP server on the Mac** that presents Android and iOS
QA backends through one tool list and one gateway QA report.

It does not replace the device apps. It connects to them:

```
AI client (DSH)
  → qa_gateway_mcp.py (stdio MCP)
      → Android native MCP  (HTTP, usually http://127.0.0.1:18080/mcp)
      → iOS native MCP      (HTTP, http://127.0.0.1:8080/mcp)
      → iOS UI bridge       (stdio → Appium/XCUITest)
```

## Backends

| Name | Transport | Start first |
|---|---|---|
| `android` | Streamable HTTP | Android app + `adb forward tcp:18080 tcp:8080` |
| `ios` | Streamable HTTP | iOS app + simulator, or `iproxy` for physical |
| `ios-ui` | stdio | Appium server + simulator + app installed |

The gateway does not start Android, iOS, `iproxy` or Appium; start them first.
It does start the configured stdio bridge process.

## Configuration

Copy [`tools/qa_gateway.example.json`](../tools/qa_gateway.example.json) and edit:

```json
{
  "backends": [
    {
      "name": "ios",
      "platform": "ios",
      "transport": "http",
      "url": "http://127.0.0.1:8080/mcp",
      "token_env": "IOS_MCP_TOKEN"
    },
    {
      "name": "ios-ui",
      "platform": "ios",
      "transport": "stdio",
      "command": "python3",
      "args": [
        "/absolute/path/MCPServer/tools/ios_appium_mcp.py",
        "--udid",
        "878ADEDA-5433-4D14-A3A9-5D15301774B1",
        "--bundle-id",
        "com.vlad.mcpserver"
      ]
    }
  ],
  "report_dir": "qa-reports"
}
```

- `token_env` reads the backend bearer token from the environment; `token`
  supports a literal value for local testing.
- `headers` adds extra HTTP headers.
- `report_dir` is relative to the gateway process working directory.

For Android, start the app and run:

```bash
adb forward tcp:18080 tcp:8080
export ANDROID_MCP_TOKEN='...'
```

For the iOS UI backend, start Appium first:

```bash
tools/appium_ios_ctl.sh start
```

## MCP configuration

```yaml
- insert:
    - id: mcp-qa-gateway
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: qa
        transport: stdio
        command: python3
        args:
          - /absolute/path/to/MCPServer/tools/qa_gateway_mcp.py
          - --config
          - /absolute/path/to/qa_gateway.json
        toolCallTimeoutMs: 180000
```

Use a long timeout because the first Appium/XCUITest session can take 30–90 s.

## Tool list

Gateway tools:

| Tool | Purpose |
|---|---|
| `qa_capabilities` | Connected backends, platforms, tool counts and errors |
| `qa_session` | `begin` / `step` / `status` / `end`; one cross-platform report |

Backend tools are namespaced:

```
android__get_device_info
android__ui_assert
ios__qa_session
ios__audio_instruction
ios-ui__get_screen_tree
ios-ui__click_element
```

`qa_capabilities` is the source of truth when a backend is offline or lacks a
tool. The gateway does not pretend a tool exists on a disconnected backend.

## Gateway QA report

`qa_session begin` starts a host-side session. Every proxied backend call is
recorded automatically; `qa_session step` records manual expected/actual steps.
`qa_session end` writes JSON and Markdown under `report_dir`.

```
qa-reports/gw-YYYYMMDD-HHMMSS.json
qa-reports/gw-YYYYMMDD-HHMMSS.md
```

The report contains backend capability status, manual steps and tool calls with
duration/success/error.

## Tested in this environment

- iOS native backend: 13 tools, `ios__get_device_state` successful.
- iOS UI stdio backend: 14 tools, `ios-ui__get_screen_tree` successful through
  Appium + XCUITest.
- Gateway `tools/list`: 2 gateway + backend tools.
- Gateway QA session: begin → proxied call → end; report contains the tool call.
- `qa_capabilities`: iOS native connected with 13 tools; iOS UI connected with 14.
- 13 Python unit tests pass; Android still has 40 passing tests.
- Appium 3.9.0, XCUITest 12.22.0, Xcode 27.1, iPhone 17 simulator.

Android live backend and physical iOS were not tested in this pass.

## Limitations

- The gateway is a local stdio process; it does not expose a network port.
- Start order matters: backends and Appium before the first gateway tool call.
- Backend approvals remain on the device/app; the gateway does not bypass them.
- The gateway report does not replace device screenshots/logs; it references
  proxied results and steps.
- `ios-ui` requires Appium; see [`APPIUM_SETUP.md`](APPIUM_SETUP.md).
- Physical iOS remains 0.1.4 (signing/iproxy/WDA).
- Android and iOS may expose different tool subsets; use `qa_capabilities`.
- Appium sessions are one per device; delete stale sessions before creating new ones.
