# Synthetic Android QA target

`qaFixture` is a separate, local-only test app (`com.vlad.mcpserver.fixture`).
It is not bundled into Tester and does not request network or device permissions.
All displayed data is synthetic.

```bash
./gradlew :androidApp:assembleDebug :qaFixture:assembleDebug
adb -s <disposable-emulator> install -r qaFixture/build/outputs/apk/debug/qaFixture-debug.apk
```

The cards screen has duplicated Open buttons and editable fields under distinct
Alpha/Beta container IDs, Unicode text, and a clipped button. Android clips the
button's reported screen bounds; parent-local dimensions retain its original size,
so the visibility check must not mistake the clipped rectangle for the full button.
Buttons open separate Compose merged-semantics, local WebView and FLAG_SECURE screens.
Back returns to the cards screen.

On the Compose screen, the content-description node for the field is a semantic
child of the editable node. Input resolution can use the nearest editable ancestor;
text assertions must select the actual EditText because the child has no text.
Merged descendants are reported as Android exposes them, not reconstructed.

Enable accessibility and explicitly approve a disposable emulator's connection and
tool policy before running the live suite:

```bash
export TESTER_MCP_URL=http://127.0.0.1:18090/mcp
export TESTER_MCP_SESSION='<approved session>'
export TESTER_FIXTURE_TESTS=1
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -p test_android_targeting_mcp.py
```

These tests change only synthetic app state and foreground navigation. They do not
install builds, grant permissions or approve tool calls. Per-call confirmation UI
can itself invalidate observations; explicitly disable it only on a disposable
test environment when running unattended checks. Never use personal app content.

Optional `TESTER_MCP_SECOND_SESSION` enables cross-client reference rejection
checks; both sessions must already be approved. Set
`TESTER_FIXTURE_SETTINGS_PROVISIONED=1` only after explicitly provisioning
WRITE_SETTINGS/WRITE_SECURE_SETTINGS on the disposable device. That check changes
rotation and restores the snapshot in cleanup. It does not grant itself permissions.
