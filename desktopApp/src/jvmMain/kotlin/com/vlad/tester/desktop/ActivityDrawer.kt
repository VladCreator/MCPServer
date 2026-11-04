package com.vlad.tester.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DismissibleDrawerSheet
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ActivityDrawer(
    state: DrawerState, activity: List<String>, onClose: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable () -> Unit
) {
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier) {
        val panelWidth = (maxWidth * 0.32f).coerceIn(260.dp, 360.dp)
        val revealedWidth = with(LocalDensity.current) {
            val offset = state.currentOffset
            if (offset.isNaN()) 0.dp else (panelWidth.toPx() + offset)
                .coerceIn(0f, panelWidth.toPx()).toDp()
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            DismissibleNavigationDrawer(
                drawerState = state,
                gesturesEnabled = false,
                drawerContent = {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        DismissibleDrawerSheet(Modifier.width(panelWidth).fillMaxHeight()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row {
                                    Text("Activity", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                    TextButton(onClick = onClose) { Text("Close") }
                                }
                                Text("This session only. Installations are never automatically retried.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (activity.isEmpty()) item { Text("No activity yet.") }
                                    items(activity) { entry ->
                                        SelectionContainer {
                                            Text(entry, style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp, lineHeight = 16.sp))
                                        }
                                    }
                                }
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
