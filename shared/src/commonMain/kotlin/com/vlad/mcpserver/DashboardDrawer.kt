package com.vlad.mcpserver

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DismissibleDrawerSheet
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun useInlineDashboardDrawer(widthDp: Float, heightDp: Float): Boolean =
    minOf(widthDp, heightDp) >= 600 && widthDp > heightDp

internal enum class DashboardPanel(val title: String, val opensRight: Boolean) {
    EVIDENCE("QA Evidence", true), ACTIVITY("Activity", false)
}

internal fun activeDashboardPanel(
    selected: DashboardPanel, current: DrawerValue, target: DrawerValue
): DashboardPanel? = selected.takeIf { current == DrawerValue.Open || target == DrawerValue.Open }

@Composable
private fun RetainedPanelContent(
    selected: DashboardPanel, evidence: @Composable () -> Unit, activity: @Composable () -> Unit
) {
    // Measure both owners, but only place the selected panel: hidden work stays alive without focus targets.
    Layout(content = { Box { evidence() }; Box { activity() } }) { measurables, constraints ->
        val panels = measurables.map { it.measure(constraints) }
        val visible = panels[if (selected == DashboardPanel.EVIDENCE) 0 else 1]
        layout(visible.width, visible.height) { visible.placeRelative(0, 0) }
    }
}

@Composable
internal fun DashboardDrawer(
    evidence: @Composable () -> Unit,
    activity: @Composable () -> Unit,
    content: @Composable (active: DashboardPanel?, toggle: (DashboardPanel) -> Unit) -> Unit
) {
    val state = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val transitions = remember { Mutex() }
    val currentEvidence by rememberUpdatedState(evidence)
    val currentActivity by rememberUpdatedState(activity)
    val currentContent by rememberUpdatedState(content)
    var selected by remember { mutableStateOf(DashboardPanel.EVIDENCE) }
    val direction = LocalLayoutDirection.current
    // Keep evidence state and in-flight work when moving between modal and inline slots.
    val panel = remember {
        movableContentOf {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(selected.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    scope.launch { transitions.withLock { state.close() } }
                }) { Text("Close") }
            }
            Column(
                Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RetainedPanelContent(selected, evidence = { currentEvidence() }, activity = { currentActivity() })
            }
        }
    }
    val workspace = remember {
        movableContentOf {
            currentContent(activeDashboardPanel(selected, state.currentValue, state.targetValue)) { requested ->
                scope.launch {
                    transitions.withLock {
                        if (selected == requested && state.isOpen) state.close() else {
                            state.close()
                            selected = requested
                            state.open()
                        }
                    }
                }
            }
        }
    }
    BoxWithConstraints {
        val inline = useInlineDashboardDrawer(maxWidth.value, maxHeight.value)
        val width = if (inline) (maxWidth * 0.4f).coerceIn(300.dp, 400.dp)
            else minOf(400.dp, maxWidth * 0.9f)
        val revealedWidth = with(LocalDensity.current) {
            val offset = state.currentOffset
            if (offset.isNaN()) 0.dp else (width.toPx() + offset)
                .coerceIn(0f, width.toPx()).toDp()
        }
        CompositionLocalProvider(LocalLayoutDirection provides
            if (selected.opensRight) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            if (inline) {
                DismissibleNavigationDrawer(
                    drawerState = state,
                    gesturesEnabled = false,
                    drawerContent = {
                        DismissibleDrawerSheet(drawerState = state, modifier = Modifier.width(width)) {
                            CompositionLocalProvider(LocalLayoutDirection provides direction) { panel() }
                        }
                    }
                ) {
                    // Compensate for Material's body translation without clipping the workspace.
                    Box(Modifier.fillMaxSize().padding(end = revealedWidth)) {
                        CompositionLocalProvider(LocalLayoutDirection provides direction) { workspace() }
                    }
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = state,
                    gesturesEnabled = state.isOpen,
                    drawerContent = {
                        ModalDrawerSheet(drawerState = state, modifier = Modifier.width(width)) {
                            CompositionLocalProvider(LocalLayoutDirection provides direction) { panel() }
                        }
                    }
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) { workspace() }
                }
            }
        }
    }
}
