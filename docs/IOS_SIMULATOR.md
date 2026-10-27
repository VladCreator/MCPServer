# iOS simulator screenshots

The embedded Tester iOS server exposes sessions, audio and device state, but no
cross-app screenshot API. A separate Mac-side **stdio MCP server** provides
`take_screenshot` using Xcode's `simctl`. It captures the selected simulator's
display even when another app is in front; it does not support physical iPhones.

## Setup

1. Install Xcode and select its developer directory. Run
   `xcrun simctl list devices booted` and copy the intended simulator's UUID.
   If necessary, boot that simulator in the Simulator app.
2. Register this additional MCP server in your client's configuration, replacing
   the absolute script path and UUID:

   ```json
   {
     "mcpServers": {
       "Tester-iOS-Simulator": {
         "command": "python3",
         "args": [
           "/absolute/path/to/MCPServer/tools/ios_simulator_mcp.py",
           "--udid",
           "00000000-0000-0000-0000-000000000001"
         ]
       }
     }
   }
   ```

3. Reconnect or reload the client's MCP servers and verify `tools/list` exposes
   `take_screenshot` on **Tester-iOS-Simulator**. Call it with `{}`.
   It returns PNG image content, the simulator UUID and a UTC capture timestamp.

The bridge uses Python 3's standard library and opens no network port. Run it
through the MCP client, not as an HTTP server. The existing **Tester-iOS** server
remains separate: keep that connection for `qa_session`, audio and device state.
The bridge does not add screenshot support to the embedded app or automatically
attach screenshots to QA reports. Do not select the Android screenshot tool.

Only configure this bridge in trusted local clients: permission to call its tool
allows capture of whatever is displayed in the selected simulator. Captures are
returned to the client; temporary image files are removed after each call.
Capture failures (including an unbooted simulator) return a tool error with details.
An explicit UUID prevents accidentally capturing a different booted simulator.

For physical-device or richer cross-app automation, a separate XCUITest/device
automation integration is still needed.

## Checks

Run `python3 -B -m unittest discover -s tools -p 'test_*.py'`.
