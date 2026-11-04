package com.vlad.tester.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.LocalTime

internal enum class ActivityKind(val label: String, val icon: ImageVector) {
    INSTALL("Installation", Icons.Rounded.Download),
    RUN("Tester launch", Icons.Rounded.PlayArrow),
    BRIDGE_CONNECT("Bridge connected", Icons.Rounded.Link),
    BRIDGE_DISCONNECT("Bridge disconnected", Icons.Rounded.LinkOff),
    BOOT("Simulator startup", Icons.Rounded.PowerSettingsNew),
    EVIDENCE("QA Evidence receiver", Icons.Rounded.Description),
    PAIRING("Pairing", Icons.Rounded.Key),
    ERROR("Error", Icons.Rounded.WarningAmber),
    INFO("Information", Icons.Rounded.Info)
}

internal data class DesktopActivity(
    val kind: ActivityKind, val message: String, val time: LocalTime = LocalTime.now().withNano(0)
) {
    val displayText: String get() = "$time  $message"
}

internal fun List<DesktopActivity>.record(entry: DesktopActivity): List<DesktopActivity> =
    listOf(entry) + take(99)
