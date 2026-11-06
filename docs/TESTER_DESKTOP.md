# Tester Desktop

Tester Desktop **0.5.0** is a separate macOS Compose Desktop companion, aligned
with the Android and iOS release. It manages local
installation files and devices; it does not replace the mobile MCP servers or
change the existing gateway. Mobile and desktop modules remain independent.

## Run

From the repository root:

```bash
./gradlew :desktopApp:run
```

Requirements: macOS, JDK 21, Android SDK platform-tools and build-tools for Android,
and full Xcode with its command-line tools selected for iOS. Accept Xcode's license
and complete its first-launch setup yourself. Tester never installs SDKs, changes
Xcode settings, downloads builds, or signs in to external services.
Desktop launching and packaging honor `JAVA_HOME` when set.

ADB and aapt2 are found through PATH, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or
`~/Library/Android/sdk`. Missing tools and failed discovery sources appear on the
dashboard without hiding devices from other sources. After changing SDK locations,
restart the desktop app.

In Android Studio, sync Gradle and select the shared **Tester Desktop** run
configuration in the toolbar, then press Run. It runs `:desktopApp:run`.
The macOS window, Dock and packaged app reuse Tester's Android launcher artwork.

For a local macOS installer:

```bash
./gradlew :desktopApp:packageDmg
```

Output: `desktopApp/build/compose/binaries/main/dmg/`. This is a local unsigned
distribution, not a notarized release. Packaging targets the host architecture.
The DMG filename uses the internal jpackage version `1.5.0` (jpackage rejects a
zero major version); the packaged app's Info.plist and dashboard show `0.5.0`,
with build number `21`.
Release builds leave dependency JARs unoptimized so signed cryptographic
libraries retain their valid embedded signatures.

## ADB bridge

The bottom status bar shows the detected ADB executable path and app version.
The path can be selected/copied; hover to read the full path if space is limited.
For each Android device the dashboard
checks `adb forward --list` every scan for the exact device-owned mapping
`tcp:8080 -> tcp:8080`. A mapping is not a continuously running shell command.

For Android, select **Run Tester** first. It opens the updated installed Tester
APK through a shell-permission-protected activity and requests MCP server startup
after stored settings are loaded. Authentication and approval settings are
preserved; TLS-enabled installations continue using their configured HTTPS port.
Install the updated Android APK to use this entry point.

Under each Android device's checkbox, the **Play icon** runs Tester and the
**Connect/Disconnect icon** controls its MCP bridge. Tooltips identify the device
and current action. When the 8080 mapping is missing, **Connect** runs:

```bash
adb -s SELECTED_SERIAL forward --no-rebind tcp:8080 tcp:8080
```

The device must be online and authorized. The mapping is rechecked immediately
before and after creation. Port 8080 can belong to only one device; conflicts are
shown explicitly and never replaced or removed. Other ports, including a gateway's
18080 mapping, do not count as the requested 8080 mapping.

For an active, verified mapping, **Disconnect** removes only
`adb -s SELECTED_SERIAL forward --remove tcp:8080` and verifies that it is absent.
It can interrupt MCP connectivity but does not stop Tester, remove USB evidence
reverse forwarding, or unpair evidence. Conflicting or unverified mappings are
non-actionable; a fresh ownership check also protects against stale UI state.

An active mapping does **not** prove the mobile Tester server or agent is connected.
Run Tester confirms activity launch and the server's foreground service, not an
authenticated agent connection; inspect the mobile dashboard for gateway/agent
status. Unconfirmed startup is reported as an error.
Android OS versions are queried on authorized devices; simulator versions come
from runtime identifiers, and physical iOS versions from devicectl. Unavailable
versions are shown explicitly. Platform icons identify Android and Apple devices.

## Install

1. Connect an Android device and accept USB debugging authorization, or start
   an Android emulator. For iOS, select an available simulator and use the **Power icon (Boot)**
   if necessary. Devices are scanned once at startup; select the **Refresh icon**
   in the Devices header (tooltip: Refresh devices, with last scan time)
   to update the list afterward. There is no periodic automatic device refresh.
   Device operations still recheck readiness and refresh their results.
   The list defaults to connected devices: online/unauthorized Android devices,
   booted iOS simulators, and connected physical iOS devices. Enable **Show all
   devices** to see offline Android devices or boot shutdown simulators.
   Hiding devices removes their selections.
2. Drag a local Android `.apk` or an iOS **simulator-compatible `.app` directory**
   onto the single drop area. File selection is drag-and-drop only. The extension
   selects all currently ready matching devices; review and uncheck destinations
   before installing. Installation actions require a matching platform. Replacing the file resets
   the selected targets. Device checkboxes are enabled before selecting a file.
   Checking any device disables drag-and-drop; uncheck all devices before dropping
   or replacing a build. After a drop, compatible ready devices are selected.
   Clear removes the file and selection without deleting the file.
   IPA, AAB, and physical iOS builds are not supported. Android files are
   inspected with aapt2; iOS identity and the `iphonesimulator` platform are read
   from Info.plist before installation.
3. Check the target devices. Only authorized/online Android devices and booted
   iOS simulators are selectable. Connected physical iOS devices are detected
   using devicectl but are read-only in this release. Shutdown simulators are
   available destinations, not reported as connected devices.
4. Select the **Download icon (Install)** on a selected device to inspect the build
   for that destination. The toolbar's Review installation button has been removed.
   Installation requires a matching build file. Confirm the displayed package, version,
   build, file paths, and exact device IDs. Review inspects the file but does not
   install it. Only the subsequent **Install** confirmation dispatches commands.
   Repeat the per-device action to install the same build on other selected devices.
   The **Play icon (Run Tester)** opens Tester and requests its server startup on
   Android; it does not run a QA test or launch the dropped app. Both icons have
   tooltips and accessible device-specific labels.
5. Inspect each result in **Activity**. ADB uses `-s <serial> install -r` and
   simctl uses the selected simulator UDID. The target's readiness and file identity
   are checked again before dispatch. After dispatch, Android version/build is
   checked with dumpsys and iOS with the installed app container's Info.plist.

There is no uninstall, downgrade flag, automatic launch, automatic retry, or
cross-platform rollback. Existing app data is not deliberately cleared, but
app migrations can still change it. A command timeout leaves the outcome uncertain:
inspect the device before retrying. Verification checks metadata, not signing
provenance or that the app starts successfully.

The **Activity/history icon** opens a dismissible left-side Activity drawer;
use the icon again or **Close** to hide it. It starts closed and is not saved
across launches. The workspace remains usable while Activity is open. Logs remain
in memory when the drawer closes.

The **QA Evidence icon** opens a dismissible right-side drawer with receiving,
pairing and received-flow controls. Only one drawer opens at a time; opening one
closes the other. The receiver and pairing state stay alive while either drawer
is hidden. Both drawers have an explicit **Close** action and start closed.
The open panel's toolbar button uses a filled accent background and selected
accessibility state. Closing it clears that feedback, including keyboard dismissal.

The Devices card wraps short lists and empty states, grows to available height,
and then scrolls for longer lists. Closing a drawer restores workspace width.
Installation files stays in the main workspace above Devices.

Activity is bounded to the most recent 100 entries in the current desktop session
and uses compact 12 sp log text. Category icons distinguish installation (Download),
Tester launch (Play), bridge connection/disconnection (Link/Unlink), simulator
startup (Power), evidence receiver (Document), pairing (Key), errors (Warning),
and general information (Info). Timestamped messages remain selectable; error
icons also use the theme's error color. It is not persisted or synced to mobile QA Evidence. Closing is blocked while a
device operation is in progress. Desktop follows the macOS light/dark appearance,
including the drop area. The 42 dp Android/Apple icons, device names, kind and
state badges share one identity line where space permits, wrapping at narrower
widths. Android's Play icon stays under its checkbox, followed by its bridge icon.
Shutdown iOS simulators have a Power icon under the checkbox with a device-specific
Boot tooltip; physical iOS devices have no Boot action.
Kind/state badges replace redundant platform text: **Device** or **Simulator**
(including Android emulators), followed by **Connected**, **Shutdown**,
**Unauthorized**, **Offline**, or the discovered diagnostic state. Raw readiness,
connected-only filtering and physical-iOS installation restrictions are unchanged.
The light palette uses slate `#F8FAFC`, white cards, `#E2E8F0` borders,
`#0F172A` text and indigo `#4F46E5`. The dark palette uses obsidian
`#090A0F`, `#11131C` / `#191B26` surfaces, `#262938` borders,
`#F8FAFC` text and indigo `#6366F1`.

## Saved display preferences

Window size/position and **Show all devices** are saved locally in
`~/Library/Application Support/Tester Desktop/settings.properties`.
Writes are debounced and atomically replace the previous file; closing flushes
pending preferences. Restored bounds are constrained to visible displays.
Unreadable or invalid settings and write failures appear on the dashboard.
The first launch defaults to connected-only and a 1100 by 800 window.
Files, selected devices, Activity, receiver activation and pairing secrets are
not restored. Receiving remains off after restart.

## Send QA Evidence to Desktop

The receiver is **off by default** and is separate from MCP and its port 8080.

### Simple USB pairing (Android)

1. Connect and authorize your Android device, and install the updated Tester APK.
2. On Android, select **Send** beside a flow. Leave the pairing dialog open.
3. In Desktop's **QA Evidence**, press **Pair** beside that device.
   Desktop enables receiving and configures the USB route automatically.
4. Both screens show a two-digit number, for example **54**. On Android, compare
   it with Desktop and press **Matches - pair and send**. This explicitly approves
   sending the displayed flow and file count; no second Send action is required.
5. Desktop displays pairing confirmation and the received flow. Original evidence
   stays on Android. Future Send actions use the existing pairing and a transfer
   confirmation. If Desktop has restarted, Send opens pairing again.

No URL, secret or fingerprint needs to be copied for USB. The number is visual
confirmation, **not an authentication password**. A shell-permission-protected
Android receiver accepts the bootstrap only over authorized ADB, with a random
device-specific 192-bit key and certificate pin. Offers expire after two minutes.
Android stores the approved key encrypted. Unconfirmed keys cannot upload files.
Pairing another device does not revoke already paired devices; pairing the same
device again replaces its key. Stopping Desktop receiving revokes all pairings.

Desktop hides **Pair** and displays a green **Connected** badge only after a
confirmed device sends a valid authenticated status request or accepted upload.
After 30 seconds without activity, Pair reappears; stale presence does not itself
revoke the key. Stopping receiving resets presence and pairings. Invalid requests
do not refresh presence. Wi-Fi clients using the shared advanced key are not
attributed to individual USB device rows.

The Desktop green **Active - ready to receive** badge indicates the receiver is
running, not that any device or AI agent is connected. Android highlights
**Tester Desktop active** in green only after authenticated pairing/status
succeeds; unavailable/unpaired states are not green. Status text conveys the
state independently of color, in both light and dark themes.

### Wi-Fi / advanced pairing

1. In Desktop, select **QA Evidence > Enable receiving**. This explicitly
   starts an HTTPS listener on IPv4 interfaces, port **8766**, using a temporary
   self-signed certificate and a random private 192-bit pairing key.
2. Select **Wi-Fi / advanced** to display the HTTPS URLs, private key and certificate
   SHA-256 fingerprint. Do not share the key in chat or commit it.
3. For Wi-Fi, use the displayed Mac LAN address on the same trusted network.
   macOS Firewall may ask to allow incoming connections. For Android USB, select
   **Enable USB for [device]**, then use `https://localhost:8766` on Android.
   This creates a device-pinned `adb reverse --no-rebind tcp:8766 tcp:8766`,
   refuses conflicts and verifies the mapping; it never removes existing routes.
4. On Android, open **QA Evidence > Desktop > Wi-Fi / advanced setup**, copy the URL, key, and fingerprint,
   compare the fingerprint with Desktop, and select **Pair**. The client verifies
   the pinned certificate before sending the key. Redirects are rejected. The key
   is stored using Android Keystore encryption.
5. Android checks authenticated receiver availability every ten seconds. An
   unavailable Desktop, changed certificate, bad key, or network error is shown
   explicitly. This reports receiver availability, not agent connectivity.
6. Expand a day and select **Send** beside a linked **Flow #**. Review the
   destination and file count, then confirm **Send**. This sends all saved
   evidence linked to that flow across dates, not just the visible day's files.
   Unlinked evidence cannot be sent as a flow. Nothing is sent automatically.
7. Desktop lists received flows. Select one to open its folder in Finder.
   Files persist under `~/Library/Application Support/Tester Desktop/QA Evidence`.
   Restarting receiving reloads saved inbox folders. The `manifest.json` preserves
   the original names, MIME types, creation times, origins, and flow ID; short
   transfer names avoid collisions. Images, step traces, JSON and Markdown are
   included if they are saved and linked to that flow.

Limits: **200 evidence files**, a 256 KiB manifest and **100 MiB total** (both
compressed and expanded). The receiver rejects traversal, nested paths,
executables, unsupported extensions, duplicate names, malformed manifests and
partial uploads. A flow appears only after validation and atomic publication.
Failed uploads are cleaned; failures are visible. No automatic extraction/opening
of external apps, deletion of originals, retry or cloud service is used. If a
response is lost after publication, check the inbox before sending again.

Select **Stop** to disable receiving. A restart changes the key and certificate;
pair again on each device. The shared receiver key authorizes any device holding
it, and stopping/restarting revokes all old pairings. Certificates expire after
24 hours; restart and pair again for long-running sessions. Check storage usage
and delete received flow folders yourself when no longer needed.

On iOS, the bottom **Desktop** button opens a pairing/status panel using the same
HTTPS key and certificate pin. It checks status every ten seconds, holds pairing
only in memory for the app session, and offers **Forget Desktop**. Physical iOS
uses Wi-Fi; the simulator can use localhost. iOS evidence sending is not added
because its on-device evidence browser is not implemented. Android implements
the requested **Send flow** action.

## Development

```bash
./gradlew :desktopApp:jvmTest :desktopApp:packageDistributionForCurrentOS
```

Opt-in, read-only discovery against your installed Mac tools:

```bash
TESTER_DESKTOP_LIVE_DISCOVERY=1 ./gradlew :desktopApp:jvmTest --rerun-tasks
```

`DeviceManager` owns device discovery, file inspection, installation, and result
verification. Its injected `CommandRunner` makes those operations independently
testable. New device capabilities can be added behind this boundary without
coupling the desktop UI to Android/iOS mobile implementations.
