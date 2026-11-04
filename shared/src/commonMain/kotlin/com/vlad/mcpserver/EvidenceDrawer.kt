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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal fun useInlineEvidenceDrawer(widthDp: Float, heightDp: Float): Boolean =
    minOf(widthDp, heightDp) >= 600 && widthDp > heightDp

@Composable
internal fun EvidenceDrawer(
    evidence: @Composable () -> Unit,
    content: @Composable (toggle: () -> Unit) -> Unit
) {
    val state = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentEvidence by rememberUpdatedState(evidence)
    val currentContent by rememberUpdatedState(content)
    val direction = LocalLayoutDirection.current
    // Keep evidence state and in-flight work when moving between modal and inline slots.
    val panel = remember {
        movableContentOf {
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("QA Evidence", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { scope.launch { state.close() } }) { Text("Close") }
                }
                currentEvidence()
            }
        }
    }
    val workspace = remember {
        movableContentOf {
            currentContent {
                scope.launch { if (state.isOpen) state.close() else state.open() }
            }
        }
    }
    BoxWithConstraints {
        val inline = useInlineEvidenceDrawer(maxWidth.value, maxHeight.value)
        val width = if (inline) (maxWidth * 0.4f).coerceIn(300.dp, 400.dp)
            else minOf(400.dp, maxWidth * 0.9f)
        val revealedWidth = with(LocalDensity.current) {
            val offset = state.currentOffset
            if (offset.isNaN()) 0.dp else (width.toPx() + offset)
                .coerceIn(0f, width.toPx()).toDp()
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
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
