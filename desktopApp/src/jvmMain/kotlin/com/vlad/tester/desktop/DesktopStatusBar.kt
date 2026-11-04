package com.vlad.tester.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopStatusBar(adbPath: String?) {
    val adbStatus = "ADB: ${adbPath ?: "Not found - install platform-tools or set ANDROID_HOME"}"
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TooltipArea(modifier = Modifier.weight(1f), tooltip = {
            Surface(shadowElevation = 4.dp) { Text(adbStatus, Modifier.padding(8.dp)) }
        }) {
            SelectionContainer {
                Text(adbStatus, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (adbPath != null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error)
            }
        }
        Text("0.4.1", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
