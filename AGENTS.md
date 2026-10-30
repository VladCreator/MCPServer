# AGENTS.md - Tester (Version 0.2.3)

## Project Objective
Tester (repository/package `MCPServer` / `com.vlad.mcpserver`) is an Android (Kotlin Multiplatform) application designed to act as a Model Context Protocol (MCP) server host on mobile devices. 

Its primary objectives are:
1. **Tester Integration**: Provide a robust local Tester allowing external AI agents to connect, discover, and invoke capabilities on the device.
2. **Interactive Capabilities for AI**: Empower connected AI agents with device tools and touch areas to:
   - Perform automated UI and functional tests.
   - Capture photos using camera integration.
   - Read device/app files.
   - Create system notifications.
   - (Extensible for future tools and commands).
3. **Live Dashboard**: Display connected AIs on the main screen in real-time, along with transparency on the tools/commands currently being executed.
4. **Modern, Clean, Intuitive UI**: Deliver a sleek, modern user experience with a custom modern color palette (avoiding default Material 3 color schemes), utilizing dark/deep tones, smooth cards, and clear status indicators.
5. **SQLite Persistence**: Implement a SQLite database (via Room KMP or SQLite driver) to persistently store app settings, execution logs, and command histories.

## Architectural Decisions & Security
- **Hybrid Security Model**:
  - **Bearer Token Authentication**: AI clients sending a valid `Authorization: Bearer <token>` header are automatically authorized and connected.
  - **Manual Approval Fallback**: AI clients connecting without a valid token are placed in a pending queue. The user can interactively review and tap **Approve** or **Reject** directly from the main dashboard.
- **USB Connection Support (ADB Port Forwarding)**:
  - Supports USB cable connections via ADB port forwarding (`adb forward tcp:8080 tcp:8080`).
  - Allows AI clients on the computer to connect via `http://localhost:8080/sse` or `ws://localhost:8080/mcp` without requiring a Wi-Fi network.

## Engineering Standards & Rules
- **Language**: All source code, variable names, functions, classes, and code comments must be written strictly in **English**.

## Architecture & Versioning
- **Version**: 0.2.3
- **Platform**: Kotlin Multiplatform (Android primary target)
- **UI Framework**: Jetpack Compose Multiplatform
- **Communication Protocol**: MCP over SSE / WebSockets / HTTP for AI client connections.
- **Database**: SQLite for local persistence (settings, logs, histories).
