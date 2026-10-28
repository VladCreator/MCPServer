# iOS UI Bridge — 0.1.3 (Appium/XCUITest, simulator)

The iOS native service (0.1.2) covers own-app state, audio, session and evidence.
Real cross-app UI interaction uses **Appium + the XCUITest driver** on the Mac.
This phase is simulator-first; physical-device signing remains 0.1.4.

## Architecture

```
AI client (DSH) → stdio MCP bridge (tools/ios_appium_mcp.py)
                → Appium 3.9 (http://127.0.0.1:4723)
                → XCUITest / WebDriverAgent
                → app under test on the iOS Simulator
```

The bridge is a separate MCP server. Keep the embedded iOS service connected for
`qa_session`, audio, state and own-app evidence; use **Tester iOS UI** for UI.

## Install Appium (once per Mac)

```bash
mkdir -p ~/tester-appium && cd ~/tester-appium
npm init -y
npm install appium@latest
npx appium driver install xcuitest
```

Start it with Xcode's developer directory:

```bash
export DEVELOPER_DIR=/Volumes/Mac_Ext/Applications/Xcode.app/Contents/Developer
cd ~/tester-appium
npx appium --port 4723 --log-level info
```

The first session builds WebDriverAgent for the simulator. This is expected to
take 30–90 seconds.

## MCP configuration

Add the bridge as a separate stdio MCP server:

```yaml
- insert:
    - id: mcp-ios-ui
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: ios-ui
        transport: stdio
        command: python3
        args:
          - /absolute/path/to/MCPServer/tools/ios_appium_mcp.py
          - --udid
          - 878ADEDA-5433-4D14-A3A9-5D15301774B1
          - --bundle-id
          - com.vlad.mcpserver
        toolCallTimeoutMs: 120000
```

For an existing Appium session, add `--session-id <id>`. Without it the bridge
creates a session lazily on the first UI call.

## Tools (14)

| Tool | Notes |
|---|---|
| `get_screen_tree` | XCUITest page source as indented text |
| `take_screenshot` | PNG screenshot of the app under test |
| `ui_find` | Selector search; returns type/label/name/value/rect |
| `ui_wait` | `visible`, `absent`, `text`, `idle` |
| `ui_assert` | Assertion with screenshot + tree excerpt on failure |
| `ui_gesture` | tap/double/multi/long, swipe, drag, pinch |
| `click_element` | Click/long-press by selector |
| `input_text` | Clear + type text |
| `scroll` | Directional swipe |
| `swipe` | Direction or explicit coordinates |
| `global_action` | `home`, `lock`; no Android-style back/recents |
| `launch_app` | `bundle_id`/`package_name` via `mobile: launchApp` |
| `get_device_info` | Appium URL, UDID, bundle, orientation, window |
| `device_state` | Get/set orientation (portrait/landscape) |

## Tested in this environment

- macOS, Xcode 27.1, iPhone 17 simulator (iOS 26.5).
- Appium 3.9.0, `xcuitest@12.22.0`; WDA 16.17.5 built and connected.
- Appium session `com.vlad.mcpserver`.
- `tools/list`: 14 tools.
- `get_screen_tree` (XCUITest XML), `take_screenshot` (PNG), `ui_find` (Settings),
  `click_element` (Close), `ui_wait` idle, `ui_gesture` tap, `swipe`,
  `device_state` landscape/portrait and `get_device_info`.
- `ui_assert` correctly failed when the expected text was absent and attached a
  screenshot/tree.

Not exercised in this pass:

- `input_text` against a real field.
- `launch_app` against a second app.
- `global_action` home/lock (would background the service).
- `global_action` back/recents is intentionally unsupported on iOS.

## Limitations

- Appium must be installed and running; it is not bundled in the app.
- Simulator only in this phase; physical devices need signing/WDA (0.1.4).
- Compose/SwiftUI accessibility output can be sparse; prefer stable labels and
  accessibility identifiers in the app under test.
- `take_screenshot` is full-resolution PNG; `max_dimension` is not applied.
- `ui_diff` is not in this bridge yet.
- `device_state` only handles orientation; dark mode/rotation matrix for the host
  app is simulator/host controlled.
- `global_action` is home/lock only.

## Run the bridge tests

```bash
python3 -B -m unittest discover -s tools -p 'test_*.py'
```

The current suite has 9 unit tests (bridge helpers and protocol); the live
Appium smoke checks were run separately against the iPhone 17 simulator.
