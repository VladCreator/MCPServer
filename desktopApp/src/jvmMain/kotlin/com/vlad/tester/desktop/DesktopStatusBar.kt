package com.vlad.tester.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopStatusBar(
    adbPath: String?,
    version: String,
    receivingEnabled: Boolean,
    canEnableReceiving: Boolean,
    canPairEvidence: Boolean,
    canStopReceiving: Boolean,
    onEnableReceiving: () -> Unit,
    onPairEvidence: () -> Unit,
    onStopReceiving: () -> Unit
) {
    val adbStatus = "ADB: ${adbPath ?: "Not found - install platform-tools or set ANDROID_HOME"}"
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusIcon(
            icon = Icons.Rounded.ArrowCircleDown,
            label = if (receivingEnabled) "Receiver enabled" else "Enable receiving",
            enabled = canEnableReceiving,
            highlighted = receivingEnabled,
            onClick = onEnableReceiving
        )
        StatusIcon(
            icon = Icons.Rounded.Link,
            label = "Pair evidence receiver",
            enabled = canPairEvidence,
            highlighted = receivingEnabled,
            onClick = onPairEvidence
        )
        StatusIcon(
            icon = Icons.Rounded.StopCircle,
            label = "Stop receiving",
            enabled = canStopReceiving,
            highlighted = false,
            onClick = onStopReceiving
        )
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
        Text(version, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    TooltipArea(tooltip = {
        Surface(shadowElevation = 4.dp) { Text(label, Modifier.padding(8.dp)) }
    }) {
        IconButton(
            enabled = enabled,
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (highlighted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}
