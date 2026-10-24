package com.vlad.mcpserver

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Extra colors that are not part of the Material 3 color scheme.
 */
data class McpExtraColors(
    val warning: Color,
    val onWarning: Color
)

val LocalMcpExtraColors = staticCompositionLocalOf {
    McpExtraColors(warning = Color(0xFFF59E0B), onWarning = Color(0xFF1F1300))
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8B93FF),
    onPrimary = Color(0xFF0A0D1A),
    primaryContainer = Color(0xFF2A2F63),
    onPrimaryContainer = Color(0xFFE3E5FF),
    secondary = Color(0xFF3DD9EB),
    onSecondary = Color(0xFF00202A),
    secondaryContainer = Color(0xFF0D3B46),
    onSecondaryContainer = Color(0xFFB8F4FF),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF00210F),
    tertiaryContainer = Color(0xFF103D25),
    onTertiaryContainer = Color(0xFFC6F6D5),
    background = Color(0xFF070A12),
    onBackground = Color(0xFFE8EDF7),
    surface = Color(0xFF0C111C),
    onSurface = Color(0xFFE8EDF7),
    surfaceVariant = Color(0xFF161E2C),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2B3648),
    outlineVariant = Color(0xFF1B2534),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF4C1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    inverseSurface = Color(0xFFE8EDF7),
    inverseOnSurface = Color(0xFF111827)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF083344),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF052E22),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEDF0F7),
    onSurfaceVariant = Color(0xFF5B6678),
    outline = Color(0xFFD3DAE6),
    outlineVariant = Color(0xFFE6EBF3),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    inverseSurface = Color(0xFF111827),
    inverseOnSurface = Color(0xFFF6F7FB)
)

private val McpTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    bodySmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium
    ),
    labelSmall = TextStyle(
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp
    )
)

private val McpShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun McpTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val extra = if (darkTheme) {
        McpExtraColors(warning = Color(0xFFFBBF24), onWarning = Color(0xFF1F1300))
    } else {
        McpExtraColors(warning = Color(0xFFB45309), onWarning = Color.White)
    }
    CompositionLocalProvider(LocalMcpExtraColors provides extra) {
        MaterialTheme(
            colorScheme = colors,
            typography = McpTypography,
            shapes = McpShapes,
            content = content
        )
    }
}
