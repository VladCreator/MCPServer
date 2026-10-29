package com.vlad.mcpserver

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AdsClick
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SwipeVertical
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.GraphicEq
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
    "swipe" -> Icons.Rounded.Swipe
    "get_app_version" -> Icons.Rounded.Info
    "get_logs" -> Icons.Rounded.BugReport
    "list_apps" -> Icons.Rounded.Apps
    "health_check" -> Icons.Rounded.HealthAndSafety
    "get_screen_tree" -> Icons.Rounded.AccountTree
    "take_screenshot" -> Icons.Rounded.Screenshot
    "click_element" -> Icons.Rounded.AdsClick
    "input_text" -> Icons.Rounded.Keyboard
    "scroll" -> Icons.Rounded.SwipeVertical
    "global_action" -> Icons.Rounded.Navigation
    "ui_find" -> Icons.Rounded.Search
    "ui_wait" -> Icons.Rounded.Timer
    "ui_assert" -> Icons.Rounded.Rule
    "ui_diff" -> Icons.Rounded.Compare
    "ui_gesture" -> Icons.Rounded.Gesture
    "qa_session" -> Icons.Rounded.Checklist
    "run_route" -> Icons.Rounded.Checklist
    "device_state" -> Icons.Rounded.Tune
    "get_device_state" -> Icons.Rounded.Settings
    "audio_instruction" -> Icons.Rounded.RecordVoiceOver
    "audio_state" -> Icons.Rounded.GraphicEq
    "AuthSuccess" -> Icons.Rounded.VerifiedUser
    "AuthPending" -> Icons.Rounded.HourglassTop
    "ManualReject" -> Icons.Rounded.Block
    "ToolApproval" -> Icons.Rounded.GppMaybe
    else -> Icons.Rounded.Build
}
