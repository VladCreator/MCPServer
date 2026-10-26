package com.vlad.mcpserver

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AdsClick
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SwipeVertical
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icon shown next to each MCP tool and dashboard event so activities are
 * easy to recognize at a glance.
 */
fun toolIcon(name: String): ImageVector = when (name) {
    "get_device_info" -> Icons.Rounded.PhoneAndroid
    "read_file" -> Icons.Rounded.Description
    "create_notification" -> Icons.Rounded.NotificationsActive
    "take_photo" -> Icons.Rounded.PhotoCamera
    "touch_test" -> Icons.Rounded.TouchApp
    "launch_app" -> Icons.Rounded.RocketLaunch
    "get_screen_tree" -> Icons.Rounded.AccountTree
    "take_screenshot" -> Icons.Rounded.Screenshot
    "click_element" -> Icons.Rounded.AdsClick
    "input_text" -> Icons.Rounded.Keyboard
    "scroll" -> Icons.Rounded.SwipeVertical
    "global_action" -> Icons.Rounded.Navigation
    "AuthSuccess" -> Icons.Rounded.VerifiedUser
    "AuthPending" -> Icons.Rounded.HourglassTop
    "ManualReject" -> Icons.Rounded.Block
    "ToolApproval" -> Icons.Rounded.GppMaybe
    else -> Icons.Rounded.Build
}
