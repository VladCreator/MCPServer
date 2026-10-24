package com.vlad.mcpserver

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun SystemBarAppearance(useDarkIcons: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = useDarkIcons
                isAppearanceLightNavigationBars = useDarkIcons
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
