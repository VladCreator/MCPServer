# iOS QA Service — Phase 2 (simulator)

The iOS target now ships an **embedded MCP QA service** (0.1.2) built with Ktor CIO on
Kotlin/Native. It listens on `127.0.0.1:8080` while the host app is in the
foreground.

This is the on-device equivalent of “service type Android” that iOS allows:
the service lives **inside our app**, not in a separate app that controls other
apps. Real UI interaction remains in XCUITest on the Mac.

## Tools advertised on iOS (13)

| Tool | Purpose |
|---|---|
| `get_device_info` | iOS version, device model, app version, locale, display, volume, VoiceOver. |
| `get_device_state` | Same QA state snapshot used for evidence. |
| `health_check` | Platform, app/server version, foreground state, camera permission, Documents path. |
| `read_file` | Read UTF-8 text inside the Tester sandbox (max 64 KB). |
| `create_notification` | Post a local notification after user permission; shown as a banner even while Tester is in the foreground. |
| `take_photo` | Capture a JPEG with the device camera; simulator has no camera. |
| `get_app_version` | Version/build/install dates for Tester (other app bundles are not readable). |
| `get_screen_tree` | Own-app tree (white-box): UIKit views with content plus accessibility elements, so Compose/SwiftUI content appears with role, label, value, id and screen bounds. Empty containers are collapsed. |
| `take_screenshot` | Own-app JPEG screenshot of the Tester window. |
| `get_logs` | In-memory Tester QA log (tool calls); other app logs need the host. |
| `qa_session` | `begin` / `step` / `status` / `end`; JSON + Markdown report in the app container. |
| `audio_instruction` | English TTS for the tester; `stop`; `speak` supports `wait`. |
| `audio_state` | Output volume estimate, other-audio-playing, TTS speaking. |

Not advertised on iOS (by design):

- `ui_find`, `ui_wait`, `ui_assert`, `ui_gesture`, `ui_diff`: the host app cannot
  inspect or inject into another app. XCUITest owns real UI interaction.
- `device_state` set/restore: iOS does not expose system rotation, dark-mode or
  volume changes to a normal app.
- `audio_instruction` file playback and `set_volume`: not implemented; iOS does
  not expose a public system-volume setter.
- Android-only tools (`take_photo`, notifications, files, etc.).

## Build and run

Xcode 27.1 is available on this Mac via:

```bash
export DEVELOPER_DIR=/Volumes/Mac_Ext/Applications/Xcode.app/Contents/Developer
```

Build for the booted simulator:

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,id=<UDID>' \
  -derivedDataPath /tmp/MCPServerDerived CODE_SIGNING_ALLOWED=NO build
```

Install and launch:

```bash
xcrun simctl install booted /tmp/MCPServerDerived/Build/Products/Debug-iphonesimulator/MCPServer.app
xcrun simctl launch --terminate-running-process booted com.vlad.mcpserver
```

Then tap **Start** in the app, copy the token from Settings, and use:

```
http://127.0.0.1:8080/mcp
Authorization: Bearer <token>
```

The simulator shares localhost with the Mac, so this works without `iproxy`.

### QA-lab launch shortcuts (debug only)

For unattended simulator QA the service accepts environment variables:

```bash
SIMCTL_CHILD_MCPSERVER_AUTOSTART=1 \
SIMCTL_CHILD_MCPSERVER_TOKEN=qa-ios-token-1234567890 \
SIMCTL_CHILD_MCPSERVER_CONFIRM_TOOLS=0 \
xcrun simctl launch --terminate-running-process booted com.vlad.mcpserver
```

- `MCPSERVER_AUTOSTART=1`: starts the MCP service at launch.
- `MCPSERVER_TOKEN`: fixed token for the QA run (otherwise random per launch).
- `MCPSERVER_CONFIRM_TOOLS=0`: disables the per-tool approval gate for automated
  tests. Use only with a supervised QA run; the normal app keeps the gate.

These variables do nothing unless explicitly set by the QA harness. They are not
a production configuration.

## Physical iPhone/iPad

The service binds to localhost. To reach it from the Mac over USB:

```bash
brew install libimobiledevice
iproxy 8080:8080 -u <device-udid>
```

Then use `http://127.0.0.1:8080/mcp` on the Mac.

A simulator running Tester (or `adb forward` for Android) also uses Mac port
8080. To keep both available, forward the device to another local port, for
example `iproxy 8081:8080 -u <device-udid>`, and point that gateway backend at
`http://127.0.0.1:8081/mcp`.

### Keeping the tunnel healthy

An `iproxy` tunnel can go stale: it keeps the port but stops forwarding, so
calls time out and a new `iproxy` fails with "Address already in use". Use the
supervisor instead of plain `iproxy`:

```bash
python3 tools/ios_tunnel.py --udid <device-udid> --local-port 8081 --replace
```

It starts `iproxy`, probes Tester every 10 s, prints status changes, restarts
`iproxy` when it exits or after two timed-out probes (at most every 30 s), and
with `--replace` stops an old `iproxy` holding the port. To diagnose once:

```bash
python3 tools/ios_tunnel.py --check --local-port 8081 --udid <device-udid>
```

It names the process holding the port and compares with a temporary fresh
tunnel, so the verdict says whether the tunnel is stale or the device side
(locked device, Tester in the background, server off) is not answering. The
gateway's `unreachable` errors include the same hints.

Requirements:

- A development team/provisioning profile to install the app on the physical
  device (a personal/free team is enough for a short test; paid for longer).
- The app must stay in the foreground. iOS suspends the service when the app
  goes to the background.
- WebDriverAgent/XCUITest must be signed with the same team.
- `MCPSERVER_*` environment variables are set through the Xcode scheme or a
  debug launch mechanism, not through the App Store build.

This environment has no physical iPhone/iPad attached and no `iproxy` installed,
so physical-device validation remains pending.

## QA reports

`qa_session` writes:

```
<app Documents>/qa/reports/<session>.json
<app Documents>/qa/reports/<session>.md
```

For the simulator:

```bash
xcrun simctl get_app_container booted com.vlad.mcpserver data
# then inspect Documents/qa/reports
```

## Verified in this environment

- Xcode 27.1, iPhone 17 simulator (iOS 26.5).
- `/status`: `platform=ios`, `tools=5`, port 8080.
- `get_device_state`: iOS 26.5, iPhone 17, locale, display, volume, VoiceOver.
- `audio_state`; English TTS returned “finished”.
- `qa_session` begin/step/end produced JSON + Markdown.
- `tools/list` returns exactly the 13 iOS tools.
- No-token request stays pending for human approval (60 s); with the QA token it
  is authorized immediately.
- UI starts the server and shows connected agents/activity.

## Pending for full Phase 2

- XCUITest/WebDriverAgent runner for real UI interaction on the host app.
- Physical-device signing, `iproxy` validation and a real iPhone/iPad.
- Host QA Gateway unifying Android + iOS sessions/reports.
- Keychain storage for the iOS token; currently the token is regenerated per
  launch unless `MCPSERVER_TOKEN` is set.
- Dark/light, rotation and font matrix driven by XCUITest launch configuration.
- Audio-file playback/recording and system-audio verification (limited by iOS).
