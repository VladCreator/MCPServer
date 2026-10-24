package com.vlad.mcpserver

import androidx.compose.runtime.Composable

/**
 * Adjusts the system bar icon contrast to the app theme.
 */
@Composable
expect fun SystemBarAppearance(useDarkIcons: Boolean)
