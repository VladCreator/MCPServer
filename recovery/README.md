# Recovered code from the running device (2026-10-23)

> **This is not production source.** It is a forensic recovery of the newest compiled
> server code that was running on the Android tablet, because that code is **not** in
> the Git repository (HEAD `3c58e77`, 2026-10-23 09:42).

## Why this exists

The app installed on the device (`com.vlad.mcpserver`, versionName 1.0, installed
2026-10-23 09:40, updated through Android Studio) was executing an
**Android Studio Apply Changes overlay**:

```
/data/user/0/com.vlad.mcpserver/code_cache/.overlay/base.apk/classes2.dex
```

That overlay (13:19 on 2026-10-23, 200,200 bytes) contains a newer `McpServerEngine`
with `initialize`, `tools/list`, `tools/call`, `GitHub Copilot Agent`, `MCPServer-Android`,
etc. None of those strings exist in the installed base APK nor in the repository.
The base APK matches the repository code (the stub engine).

Applying changes again, clearing app data, force-stopping during an apply, or
reinstalling the app can destroy the only local copy of that code.

## Contents

| Path | Description |
| --- | --- |
| `overlay/classes2.dex` | Byte-for-byte copy of the Apply Changes overlay (the newest code). |
| `decompiled/McpServerEngine.java` | JADX decompilation of the newest server engine. |
| `decompiled/AppViewModel.java` | JADX decompilation of the newest ViewModel. |
| `decompiled/AppDatabase.java` | JADX decompilation of the database layer. |
| `decompiled/McpServerEngine$configureServerModule$1$*.java` | Route handlers (`/sse`, `/status`, `POST /message`, WebSocket `/mcp`). |

The WebSocket handler (`...1$6.java`) could not be fully decompiled by JADX
(`JadxOverflowException`); its behaviour was verified at runtime instead.

## How it was obtained

```bash
adb exec-out run-as com.vlad.mcpserver \
  cat code_cache/.overlay/base.apk/classes2.dex > classes2.dex

brew install jadx
jadx -d decompiled classes2.dex --no-res
```

The installed base APK was also backed up for comparison at
`/tmp/mcpserver-installed-backup.apk` (temporary).

## What to do with it

1. Recover the Kotlin source for `McpServerEngine.android.kt`, `AppViewModel.kt`,
   `AppDatabase.kt` and `App.kt` from the IDE/another machine if possible.
2. If not possible, use `decompiled/` as a reference to re-implement them; the
   decompiled Java preserves method names, string literals and control flow.
3. **Commit and push the recovered source.** The repository must be the source of
   truth, not the device overlay.
4. Delete this `recovery/` directory once the source is restored and committed.

Do not ship the `.dex` or the decompiled Java in the app.
