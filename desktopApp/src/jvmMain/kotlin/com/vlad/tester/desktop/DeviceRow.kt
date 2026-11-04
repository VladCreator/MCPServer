package com.vlad.tester.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DeviceRow(
    device: Device, selected: Boolean, selectionEnabled: Boolean, actionsEnabled: Boolean,
    hasBuild: Boolean, bridge: BridgeState, onSelect: (Boolean) -> Unit,
    onRun: () -> Unit, onBridge: () -> Unit, onInstall: () -> Unit, onBoot: () -> Unit
) {
    Row {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Checkbox(checked = selected, enabled = device.canInstall && selectionEnabled,
                modifier = Modifier.semantics { contentDescription = "Select ${device.name}" },
                onCheckedChange = onSelect)
            if (device.platform == Platform.ANDROID) {
                DeviceActionIcon(Icons.Rounded.PlayArrow, "Run Tester on ${device.name}",
                    enabled = device.canInstall && actionsEnabled, onClick = onRun)
                val active = bridge == BridgeState.ACTIVE
                val label = when (bridge) {
                    BridgeState.ACTIVE -> "Disconnect MCP bridge on ${device.name} (interrupts MCP; keeps Tester and evidence pairing)"
                    BridgeState.MISSING -> "Connect MCP bridge on ${device.name}"
                    else -> "MCP bridge on ${device.name}: ${bridge.label}"
                }
                DeviceActionIcon(if (active) Icons.Rounded.LinkOff else Icons.Rounded.Link, label,
                    enabled = device.canInstall && actionsEnabled &&
                        bridge in setOf(BridgeState.MISSING, BridgeState.ACTIVE), onClick = onBridge)
            }
            if (device.platform == Platform.IOS_SIMULATOR && device.state == "Shutdown") {
                DeviceActionIcon(Icons.Rounded.PowerSettingsNew, "Boot ${device.name}",
                    enabled = actionsEnabled, onClick = onBoot)
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.align(Alignment.CenterVertically),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlatformIcon(device.platform)
                    Text(device.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium)
                }
                DeviceBadge(device.kindLabel, Modifier.align(Alignment.CenterVertically))
                DeviceBadge(device.stateLabel, Modifier.align(Alignment.CenterVertically))
            }
            Text("OS: ${device.osVersion ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(device.id, style = MaterialTheme.typography.bodySmall) }
            if (selected && hasBuild) {
                DeviceActionIcon(Icons.Rounded.Download, "Install on ${device.name}",
                    enabled = actionsEnabled, onClick = onInstall)
            }
            if (device.state == "unauthorized") Text("Unlock the device and accept USB debugging.",
                color = MaterialTheme.colorScheme.error)
            if (device.platform == Platform.ANDROID) {
                Text("MCP bridge: ${bridge.label}", style = MaterialTheme.typography.bodySmall,
                    color = if (bridge == BridgeState.ACTIVE) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DeviceBadge(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}
