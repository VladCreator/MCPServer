# Appium setup and operation — iOS UI bridge reference

Appium is an **external Node.js tool**. It is not embedded in the Tester app,
Xcode, or the Python bridge. The Python script
[`tools/ios_appium_mcp.py`](../tools/ios_appium_mcp.py) is the MCP bridge; it
talks to a running Appium server.

## Components

| Component | Role | Where |
|---|---|---|
| Appium Server | WebDriver server / session manager | npm, Homebrew, or `~/tester-appium` |
| `appium-xcuitest-driver` | XCUITest/WebDriverAgent backend | installed by Appium |
| `tools/appium_ios_ctl.sh` | start/stop/status/logs command | this repository |
| `tools/ios_appium_mcp.py` | MCP stdio bridge exposed to DSH | this repository |
| `appium-inspector` | optional GUI inspector | separate cask/app |

## Install

Choose one option.

### Option A — global npm

```bash
npm install -g appium@latest
appium driver install xcuitest
appium --version
appium driver list --installed
```

### Option B — stable local directory (recommended for a lab Mac)

```bash
mkdir -p ~/tester-appium
cd ~/tester-appium
npm init -y
npm install appium@latest
npx appium driver install xcuitest
```

### Option C — Homebrew

```bash
brew install appium
appium driver install xcuitest
```

Tested with Appium **3.9.0**, `xcuitest@12.22.0`, Xcode **27.1**, and an iPhone 17
simulator on iOS 26.5.

## Start and stop

Use the repository command:

```bash
tools/appium_ios_ctl.sh start
tools/appium_ios_ctl.sh status
tools/appium_ios_ctl.sh logs
tools/appium_ios_ctl.sh stop
```

Environment variables:

```bash
APPIUM_PORT=4723 \
APPIUM_HOME="$HOME/tester-appium" \
tools/appium_ios_ctl.sh start
```

- PID and logs: `~/Library/Logs/Tester/appium-4723.{pid,log}`.
- The command auto-detects `/Volumes/Mac_Ext/Applications/Xcode.app/Contents/Developer`
  when `DEVELOPER_DIR` is not set.
- A failed start is visible in the log; the command waits up to 30 seconds for
  `/status`.

Manual start is also valid:

```bash
export DEVELOPER_DIR=/Volumes/Mac_Ext/Applications/Xcode.app/Contents/Developer
appium --port 4723
```

or, for a local install:

```bash
cd ~/tester-appium
npx appium --port 4723
```

## Prepare the simulator

```bash
export DEVELOPER_DIR=/Volumes/Mac_Ext/Applications/Xcode.app/Contents/Developer
xcrun simctl boot "iPhone 17"
xcrun simctl install booted /path/to/MCPServer.app
```

Appium can launch the app through its bundle id:

```
com.vlad.mcpserver
```

## Configure the MCP bridge

Add a **separate** stdio MCP server:

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

Start Appium before using the bridge. The bridge creates its Appium session
lazily on the first UI tool call; the first session may build WebDriverAgent and
take 30–90 seconds.

If a session already exists, add:

```
--session-id <appium-session-id>
```

## Typical workflow

```bash
# 1. Start Appium
tools/appium_ios_ctl.sh start

# 2. Boot/install the simulator app (if needed)
export DEVELOPER_DIR=/Volumes/Mac_Ext/Applications/Xcode.app/Contents/Developer
xcrun simctl boot "iPhone 17"
xcrun simctl install booted /path/to/MCPServer.app

# 3. Use the ios-ui MCP server from DSH
#    get_screen_tree → ui_find → click_element / input_text → ui_assert

# 4. Stop Appium when finished
tools/appium_ios_ctl.sh stop
```

## Tools exposed by the bridge

`get_screen_tree`, `take_screenshot`, `ui_find`, `ui_wait`, `ui_assert`,
`ui_gesture`, `click_element`, `input_text`, `scroll`, `swipe`, `global_action`,
`launch_app`, `get_device_info`, `device_state`.

See [`IOS_UI_BRIDGE.md`](IOS_UI_BRIDGE.md) for capabilities and limits.

## Troubleshooting

| Symptom | Check |
|---|---|
| `Appium is not installed` | Install per option A/B/C; run `appium --version` |
| Start fails | `tools/appium_ios_ctl.sh logs`; check `DEVELOPER_DIR` |
| Port 4723 busy | `APPIUM_PORT=4724 tools/appium_ios_ctl.sh start` and update bridge URL |
| Appium ready but no session | Ensure the simulator is booted and the app installed |
| WDA build fails | Xcode selected correctly; first build can take 90 s |
| `Element was not found` | `get_screen_tree`; use accessibility labels/identifiers |
| Session already exists | Appium supports one session per device; delete it or use `--session-id` |
| `input_text` fails | Field may not expose a settable value; tap it first or use XCUITest |
| Physical device | Signing, `iproxy`, and WDA signing are 0.1.4; not covered here |

## Is there already a Python command?

No. There are two different Python files:

- `tools/ios_appium_mcp.py` — **MCP bridge**, does not start/stop Appium.
- `tools/test_ios_appium_mcp.py` — unit tests for the bridge.

`tools/appium_ios_ctl.sh` is the start/stop command. It is shell, not Python,
because it only manages the Appium process and PID/log files.
