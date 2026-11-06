package com.vlad.tester.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DismissibleDrawerSheet
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Desktop
import java.io.File

@Composable
private fun ActivityPanel(activity: List<DesktopActivity>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This session only. Installations are never automatically retried.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (activity.isEmpty()) item { Text("No activity yet.") }
            items(activity) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            entry.kind.icon,
                            contentDescription = entry.kind.label,
                            modifier = Modifier.size(18.dp),
                            tint = activityKindColor(entry.kind)
                        )
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(entry.displayText, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp))
                        }
                    }
                    screenshotPath(entry.message)?.let { path ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectionContainer(Modifier.weight(1f)) {
                                Text(path, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {
                                runCatching { Desktop.getDesktop().open(File(path).parentFile ?: File(path)) }
                            }) { Text("Open") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun activityKindColor(kind: ActivityKind) = when (kind) {
    ActivityKind.ERROR -> MaterialTheme.colorScheme.error
    ActivityKind.SCREENSHOT -> MaterialTheme.colorScheme.secondary
    ActivityKind.BRIDGE_CONNECT, ActivityKind.RUN, ActivityKind.EVIDENCE -> MaterialTheme.colorScheme.tertiary
    ActivityKind.PAIRING -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun screenshotPath(message: String): String? {
    val marker = ": /"
    val index = message.indexOf(marker)
    return if (message.contains("Saved screenshot") && index >= 0) {
        message.substring(index + 2).trim().takeIf { it.startsWith("/") }
    } else null
}

@Composable
internal fun DesktopPanels(
    activity: List<DesktopActivity>, evidence: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (
        activityActive: Boolean, evidenceActive: Boolean,
        toggleActivity: () -> Unit, toggleEvidence: () -> Unit
    ) -> Unit
) {
    val left = rememberDrawerState(DrawerValue.Closed)
    val right = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val transitions = remember { Mutex() }
    fun toggle(panel: DrawerState, other: DrawerState) {
        scope.launch {
            transitions.withLock {
                if (panel.isOpen) panel.close() else {
                    other.close()
                    panel.open()
                }
            }
        }
    }
    fun close(panel: DrawerState) {
        scope.launch { transitions.withLock { panel.close() } }
    }
    DesktopDrawer(left, "Activity", fromRight = false,
        onClose = { close(left) }, modifier = modifier,
        panel = { ActivityPanel(activity) }) {
        DesktopDrawer(right, "QA Evidence", fromRight = true,
            onClose = { close(right) }, modifier = Modifier.fillMaxSize(), panel = evidence) {
            content(left.currentValue == DrawerValue.Open || left.targetValue == DrawerValue.Open,
                right.currentValue == DrawerValue.Open || right.targetValue == DrawerValue.Open,
                { toggle(left, right) }, { toggle(right, left) })
        }
    }
}

@Composable
private fun DesktopDrawer(
    state: DrawerState, title: String, fromRight: Boolean, onClose: () -> Unit,
    modifier: Modifier, panel: @Composable () -> Unit, content: @Composable () -> Unit
) {
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier) {
        val panelWidth = (maxWidth * 0.36f).coerceIn(300.dp, if (fromRight) 420.dp else 360.dp)
            .coerceAtMost((maxWidth - 400.dp).coerceAtLeast(0.dp))
        val revealedWidth = with(LocalDensity.current) {
            val offset = state.currentOffset
            if (offset.isNaN()) 0.dp else (panelWidth.toPx() + offset)
                .coerceIn(0f, panelWidth.toPx()).toDp()
        }
        CompositionLocalProvider(LocalLayoutDirection provides
            if (fromRight) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            DismissibleNavigationDrawer(
                drawerState = state,
                gesturesEnabled = false,
                drawerContent = {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        DismissibleDrawerSheet(drawerState = state,
                            modifier = Modifier.width(panelWidth).fillMaxHeight()) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row {
                                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                    TextButton(onClick = onClose) { Text("Close") }
                                }
                                Box(Modifier.weight(1f).fillMaxWidth()) { panel() }
                            }
                        }
                    }
                }
            ) {
                // Material shifts the body; reserve its exposed width so content stays in the viewport.
                Box(Modifier.fillMaxSize().padding(end = revealedWidth)) {
                    CompositionLocalProvider(LocalLayoutDirection provides direction, content = content)
                }
            }
        }
    }
}
