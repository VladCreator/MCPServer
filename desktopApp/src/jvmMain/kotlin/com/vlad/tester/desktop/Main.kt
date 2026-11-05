package com.vlad.tester.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalTime
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

private data class InstallTask(val device: Device, val file: File, val identity: BuildIdentity)

fun main() = application {
    val store = remember { DesktopSettingsStore() }
    var settingsError by remember { mutableStateOf<String?>(null) }
    val initialSettings = remember {
        try { store.load() }
        catch (failure: Exception) {
            settingsError = "Cannot load Desktop settings: ${failure.message}"
            DesktopSettings()
        }
    }
    var settings by remember { mutableStateOf(initialSettings) }
    val restoredBounds = remember {
        val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
            val config = it.defaultConfiguration
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            Rectangle(config.bounds).apply {
                x += insets.left; y += insets.top
                width -= insets.left + insets.right; height -= insets.top + insets.bottom
            }
        }
        visibleWindowBounds(initialSettings.bounds, screens)
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var closeWithoutSaving by remember { mutableStateOf(false) }
    var closeWarning by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        delay(500)
        try {
            withContext(Dispatchers.IO) { store.save(settings) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            settingsError = "Cannot save Desktop settings: ${failure.message}"
        }
    }
    Window(
        onCloseRequest = {
            if (busy) closeWarning = true else if (!closing) {
                closing = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.save(settings) }
                        exitApplication()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        settingsError = "Cannot save Desktop settings before closing: ${failure.message}"
                        closeWithoutSaving = true
                    } finally { closing = false }
                }
            }
        },
        title = "Tester Desktop",
        icon = painterResource("tester-icon.png"),
        state = rememberWindowState(
            width = (restoredBounds?.width ?: 1100).dp,
            height = (restoredBounds?.height ?: 800).dp,
            position = restoredBounds?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault
        )
    ) {
        DisposableEffect(window) {
            val listener = object : ComponentAdapter() {
                private fun capture() {
                    if (window.extendedState == java.awt.Frame.NORMAL) {
                        settings = settings.copy(bounds = Rectangle(window.bounds))
                    }
                }
                override fun componentMoved(event: ComponentEvent) = capture()
                override fun componentResized(event: ComponentEvent) = capture()
            }
            window.addComponentListener(listener)
            onDispose { window.removeComponentListener(listener) }
        }
        val dark = isSystemInDarkTheme()
        MaterialTheme(colorScheme = if (dark) darkColorScheme(
            primary = Color(0xFF6366F1), onPrimary = Color.White,
            secondary = Color(0xFF3DD9EB), tertiary = Color(0xFF4ADE80),
            background = Color(0xFF090A0F), surface = Color(0xFF11131C),
            surfaceVariant = Color(0xFF191B26), onBackground = Color(0xFFF8FAFC),
            surfaceContainerLowest = Color(0xFF090A0F), surfaceContainerLow = Color(0xFF11131C),
            surfaceContainer = Color(0xFF11131C), surfaceContainerHigh = Color(0xFF191B26),
            surfaceContainerHighest = Color(0xFF191B26), surfaceTint = Color.Transparent,
            outline = Color(0xFF262938), outlineVariant = Color(0xFF262938),
            onSurface = Color(0xFFF8FAFC), onSurfaceVariant = Color(0xFF94A3B8),
            error = Color(0xFFFCA5A5)
        ) else lightColorScheme(
            primary = Color(0xFF4F46E5), onPrimary = Color.White,
            secondary = Color(0xFF0891B2), tertiary = Color(0xFF059669),
            background = Color(0xFFF8FAFC), surface = Color.White,
            surfaceVariant = Color(0xFFF8FAFC), onBackground = Color(0xFF0F172A),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
            surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
            surfaceContainerHighest = Color.White, surfaceTint = Color.Transparent,
            outline = Color(0xFFE2E8F0), outlineVariant = Color(0xFFE2E8F0),
            onSurface = Color(0xFF0F172A), onSurfaceVariant = Color(0xFF5B6678)
        )) {
            Surface(Modifier.fillMaxSize()) {
                DesktopDashboard(busy || closing, { busy = it }, settings.showAllDevices,
                    { settings = settings.copy(showAllDevices = it) }, settingsError,
                    { settingsError = null })
            }
            if (closeWarning) AlertDialog(
                onDismissRequest = { closeWarning = false },
                title = { Text("Operation in progress") },
                text = { Text("Wait for the current device operation to finish before closing Tester Desktop.") },
                confirmButton = { TextButton(onClick = { closeWarning = false }) { Text("OK") } }
            )
            if (closeWithoutSaving) AlertDialog(
                onDismissRequest = { closeWithoutSaving = false },
                title = { Text("Display settings could not be saved") },
                text = { Text("Close without saving window and filter preferences? Your QA evidence is not affected.") },
                dismissButton = { TextButton(onClick = { closeWithoutSaving = false }) { Text("Keep open") } },
                confirmButton = { TextButton(onClick = { exitApplication() }) { Text("Close without saving") } }
            )
        }
    }
}

@Composable
private fun DesktopDashboard(
    busy: Boolean, setBusy: (Boolean) -> Unit, showAllDevices: Boolean,
    setShowAllDevices: (Boolean) -> Unit, settingsError: String?, dismissSettingsError: () -> Unit
) {
    val manager = remember { DeviceManager() }
    val scope = rememberCoroutineScope()
    var discovery by remember { mutableStateOf(Discovery(emptyList(), emptyList())) }
    var scanning by remember { mutableStateOf(false) }
    var scannedAt by remember { mutableStateOf("Not scanned") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var apk by remember { mutableStateOf<File?>(null) }
    var app by remember { mutableStateOf<File?>(null) }
    var pending by remember { mutableStateOf<List<InstallTask>>(emptyList()) }
    var activity by remember { mutableStateOf(emptyList<DesktopActivity>()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun record(kind: ActivityKind, message: String) {
        activity = activity.record(DesktopActivity(kind, message))
    }
    fun fail(message: String) {
        error = message
        record(ActivityKind.ERROR, "FAILED: $message")
    }
    fun review(targets: List<Device>) {
        val files = targets.map { it to if (it.platform == Platform.ANDROID) apk else app }
        if (files.any { it.second == null }) {
            fail("Drop a matching APK / simulator .app before installing.")
            return
        }
        scope.launch {
            setBusy(true)
            error = null
            try {
                pending = withContext(Dispatchers.IO) {
                    files.map { (device, file) ->
                        val build = requireNotNull(file)
                        InstallTask(device, build.canonicalFile, manager.inspect(build, device.platform))
                    }
                }
            } catch (failure: DesktopFailure) {
                fail(failure.message ?: "Build inspection failed.")
            } catch (failure: IOException) {
                fail("Cannot read selected build: ${failure.message}")
            } finally { setBusy(false) }
        }
    }
    fun onBuildDropped(file: File) {
        val platform = if (file.extension.equals("apk", true)) Platform.ANDROID else Platform.IOS_SIMULATOR
        apk = if (platform == Platform.ANDROID) file else null
        app = if (platform == Platform.IOS_SIMULATOR) file else null
        selected = discovery.devices.filter { it.platform == platform && it.canInstall }.map { it.key }.toSet()
    }
    suspend fun refresh() {
        scanning = true
        try {
            discovery = withContext(Dispatchers.IO) { manager.discover() }
            selected = visibleSelection(selected, discovery.devices, showAllDevices)
            scannedAt = LocalTime.now().withNano(0).toString()
        } catch (failure: IOException) {
            fail("Discovery failed: ${failure.message}")
        } finally {
            scanning = false
        }
    }
    LaunchedEffect(Unit) {
        refresh()
    }
    val devices = visibleDevices(discovery.devices, showAllDevices)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DesktopPanels(activity, evidence = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                EvidenceInbox(manager, discovery.devices, busy, ::record)
            }
        }, modifier = Modifier.weight(1f).fillMaxWidth()) { activityActive, evidenceActive, toggleActivity, toggleEvidence ->
            Column(Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DeviceActionIcon(Icons.Rounded.History, "Toggle Activity",
                        enabled = true, onClick = toggleActivity, selected = activityActive)
                    BuildDropZone(
                        !busy && pending.isEmpty() && selected.isEmpty(),
                        ::onBuildDropped,
                        ::fail,
                        java.awt.Color.WHITE,
                        java.awt.Color(0x1F, 0x29, 0x37),
                        java.awt.Color(MaterialTheme.colorScheme.outline.toArgb(), true),
                        Modifier.weight(1f).height(44.dp)
                    )
                    DeviceActionIcon(Icons.Rounded.Description, "Toggle QA Evidence",
                        enabled = true, onClick = toggleEvidence, selected = evidenceActive)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                settingsError?.let { message ->
                    Row {
                        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = dismissSettingsError) { Text("Dismiss") }
                    }
                }
                if (error != null) Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectionContainer(Modifier.weight(1f)) { Text(error!!, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { error = null }) { Text("Dismiss") }
                    }
                }
                Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Installation files", style = MaterialTheme.typography.titleLarge)
                        BuildSelection(apk ?: app, !busy && pending.isEmpty()) { apk = null; app = null; selected = emptySet() }
                        if (selected.isNotEmpty()) Text("Uncheck all devices to drop a different file.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("${selected.size} selected device(s)", color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    Card(
                        Modifier.fillMaxWidth().heightIn(max = maxHeight)
                            .semantics { contentDescription = "Devices container" },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row {
                                Text("Devices", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                DeviceActionIcon(Icons.Rounded.Refresh,
                                    "Refresh devices. Last scan: $scannedAt",
                                    enabled = !busy && !scanning && pending.isEmpty(),
                                    onClick = { scope.launch { refresh() } })
                                Checkbox(checked = showAllDevices, enabled = !busy && pending.isEmpty(),
                                    modifier = Modifier.semantics { contentDescription = "Show all devices" },
                                    onCheckedChange = {
                                        selected = visibleSelection(selected, discovery.devices, it)
                                        setShowAllDevices(it)
                                    })
                                Text("Show all devices", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                            }
                            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Physical iOS devices are read-only.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LazyColumn(Modifier.weight(1f, fill = false)
                                .semantics { contentDescription = "Device list" },
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(discovery.warnings) { warning ->
                                    SelectionContainer { Text(warning, color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall) }
                                }
                                if (devices.isEmpty()) item {
                                    Text(if (showAllDevices) "No devices found. Connect Android or install Xcode for iOS simulators."
                                        else "No connected devices. Connect a device, then Refresh devices, or enable Show all devices to boot a simulator.",
                                        modifier = Modifier.padding(vertical = 16.dp))
                                }
                                items(devices, key = { it.key }) { device ->
                                    Column {
                                        val bridge = discovery.bridges[device.key] ?: BridgeState.UNVERIFIED
                                        DeviceRow(device, selected = device.key in selected,
                                            selectionEnabled = !busy && pending.isEmpty(),
                                            actionsEnabled = !busy && !scanning && pending.isEmpty(),
                                            hasBuild = (if (device.platform == Platform.ANDROID) apk else app) != null,
                                            bridge = bridge,
                                            onSelect = { selected = if (it) selected + device.key else selected - device.key },
                                            onInstall = { review(listOf(device)) },
                                            onRun = {
                                                setBusy(true)
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) { manager.runTester(device) }
                                                        record(ActivityKind.RUN, "Confirmed Tester server foreground service on ${device.name}. Connect the MCP bridge next.")
                                                    } catch (failure: DesktopFailure) {
                                                        fail(failure.message ?: "Cannot run Tester.")
                                                    } catch (failure: IOException) {
                                                        fail("Cannot run Tester: ${failure.message}")
                                                    } finally { setBusy(false); refresh() }
                                                }
                                            },
                                            onBridge = {
                                                setBusy(true)
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            if (bridge == BridgeState.ACTIVE) manager.disableBridge(device)
                                                            else manager.enableBridge(device)
                                                        }
                                                        record(if (bridge == BridgeState.ACTIVE) ActivityKind.BRIDGE_DISCONNECT else ActivityKind.BRIDGE_CONNECT,
                                                        if (bridge == BridgeState.ACTIVE)
                                                            "Verified MCP bridge disconnected on ${device.name}. Tester and evidence pairing were not changed."
                                                        else "Verified MCP bridge tcp:8080 -> tcp:8080 on ${device.name} (${device.id})")
                                                    } catch (failure: DesktopFailure) {
                                                        fail(failure.message ?: "MCP bridge operation failed.")
                                                    } catch (failure: IOException) {
                                                        fail("MCP bridge operation failed: ${failure.message}")
                                                    } finally { setBusy(false); refresh() }
                                                }
                                            },
                                            onBoot = {
                                                setBusy(true)
                                                scope.launch {
                                                    record(ActivityKind.BOOT, "Booting ${device.name}")
                                                    try {
                                                        withContext(Dispatchers.IO) { manager.boot(device) }
                                                        record(ActivityKind.BOOT, "Booted ${device.name}")
                                                    } catch (failure: DesktopFailure) {
                                                        fail(failure.message ?: "Simulator boot failed.")
                                                    } catch (failure: IOException) {
                                                        fail("Simulator boot failed: ${failure.message}")
                                                    } finally { setBusy(false); refresh() }
                                                }
                                            },
                                            onShutdown = {
                                                setBusy(true)
                                                scope.launch {
                                                    record(ActivityKind.SHUTDOWN, "Shutting down ${device.name}")
                                                    try {
                                                        withContext(Dispatchers.IO) { manager.shutdown(device) }
                                                        record(ActivityKind.SHUTDOWN, "Shutdown requested for ${device.name}")
                                                    } catch (failure: DesktopFailure) {
                                                        fail(failure.message ?: "Simulator shutdown failed.")
                                                    } catch (failure: IOException) {
                                                        fail("Simulator shutdown failed: ${failure.message}")
                                                    } finally { setBusy(false); refresh() }
                                                }
                                            },
                                            onRotate = {
                                                setBusy(true)
                                                scope.launch {
                                                    record(ActivityKind.ROTATE, "Rotating ${device.name}")
                                                    try {
                                                        withContext(Dispatchers.IO) { manager.rotate(device) }
                                                        record(ActivityKind.ROTATE, "Rotate requested for ${device.name}")
                                                    } catch (failure: DesktopFailure) {
                                                        fail(failure.message ?: "Screen rotate failed.")
                                                    } catch (failure: IOException) {
                                                        fail("Screen rotate failed: ${failure.message}")
                                                    } finally { setBusy(false); refresh() }
                                                }
                                            },
                                            onScreenshot = {
                                                setBusy(true)
                                                scope.launch {
                                                    record(ActivityKind.SCREENSHOT, "Capturing screenshot on ${device.name}")
                                                    try {
                                                        val saved = withContext(Dispatchers.IO) { manager.screenshot(device) }
                                                        record(ActivityKind.SCREENSHOT, "Saved screenshot from ${device.name}: ${saved.path}")
                                                    } catch (failure: DesktopFailure) {
                                                        fail(failure.message ?: "Screenshot capture failed.")
                                                    } catch (failure: IOException) {
                                                        fail("Screenshot capture failed: ${failure.message}")
                                                    } finally { setBusy(false); refresh() }
                                                }
                                            })
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        DesktopStatusBar(manager.adbPath)
    }
    if (pending.isNotEmpty()) {
        val tasks = pending
        AlertDialog(
            onDismissRequest = { pending = emptyList() },
            title = { Text("Confirm installation on ${tasks.size} device(s)") },
            text = {
                Column {
                    Text("This may replace an existing app. No uninstall, downgrade flag, or automatic app launch is used.")
                    LazyColumn(Modifier.height(260.dp)) {
                        items(tasks) { task ->
                            Column(Modifier.padding(vertical = 10.dp)) {
                                Text("${task.device.name} (${task.device.id})")
                                Text("${task.identity.packageName} ${task.identity.version} (${task.identity.build})")
                                SelectionContainer { Text(task.file.path, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { pending = emptyList() }) { Text("Cancel") } },
            confirmButton = { Button(onClick = {
                pending = emptyList()
                setBusy(true)
                scope.launch {
                    try {
                        for (task in tasks) {
                            record(ActivityKind.INSTALL, "Installing ${task.file.name} on ${task.device.name} (${task.device.id})")
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    manager.install(task.device, task.file, task.identity)
                                }
                                record(ActivityKind.INSTALL, result)
                            } catch (failure: DesktopFailure) {
                                fail("${task.device.name}: ${failure.message}")
                            } catch (failure: IOException) {
                                fail("${task.device.name}: ${failure.message}")
                            }
                        }
                    } finally {
                        setBusy(false)
                        refresh()
                    }
                }
            }) { Text("Install") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DeviceActionIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    selected: Boolean? = null,
    buttonSize: androidx.compose.ui.unit.Dp = 40.dp,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp
) {
    TooltipArea(tooltip = {
        Surface(shadowElevation = 4.dp) { Text(label, Modifier.padding(8.dp)) }
    }) {
        IconButton(enabled = enabled, onClick = onClick,
            modifier = Modifier.size(buttonSize).semantics { selected?.let { this.selected = it } },
            colors = if (selected == null) IconButtonDefaults.iconButtonColors() else IconButtonDefaults.iconButtonColors(
                containerColor = if (selected == true) MaterialTheme.colorScheme.primary else Color.Transparent,
                contentColor = if (selected == true) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )) { Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize)) }
    }
}

@Composable
private fun BuildSelection(file: File?, enabled: Boolean, clear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            SelectionContainer { Text(file?.path ?: "No file selected", style = MaterialTheme.typography.bodySmall) }
        }
        if (file != null) {
            DeviceActionIcon(
                icon = Icons.Rounded.Close,
                label = "Clear selected build file",
                enabled = enabled,
                onClick = clear,
                buttonSize = 30.dp,
                iconSize = 16.dp
            )
        }
    }
}
