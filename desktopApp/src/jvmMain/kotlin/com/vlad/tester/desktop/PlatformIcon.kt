package com.vlad.tester.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

@Composable
internal fun PlatformIcon(platform: Platform) {
    val appleColor = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(28.dp).semantics {
        contentDescription = if (platform == Platform.ANDROID) "Android" else "Apple iOS"
    }) {
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            if (platform == Platform.ANDROID) {
                val green = Color(0xFF4ADE80)
                drawLine(green, androidx.compose.ui.geometry.Offset(6f, 3f),
                    androidx.compose.ui.geometry.Offset(8f, 6f), 1.4f)
                drawLine(green, androidx.compose.ui.geometry.Offset(18f, 3f),
                    androidx.compose.ui.geometry.Offset(16f, 6f), 1.4f)
                drawPath(Path().apply {
                    moveTo(3f, 16f); cubicTo(3f, 1f, 21f, 1f, 21f, 16f); close()
                }, green)
                drawCircle(Color(0xFF0C111C), 1f, androidx.compose.ui.geometry.Offset(8f, 10f))
                drawCircle(Color(0xFF0C111C), 1f, androidx.compose.ui.geometry.Offset(16f, 10f))
            } else {
                drawPath(Path().apply {
                    moveTo(12f, 7f)
                    cubicTo(8f, 3f, 2f, 7f, 4f, 14f)
                    cubicTo(5f, 20f, 8f, 23f, 12f, 20f)
                    cubicTo(16f, 23f, 19f, 19f, 21f, 15f)
                    cubicTo(16f, 13f, 17f, 8f, 20f, 7f)
                    cubicTo(17f, 4f, 14f, 5f, 12f, 7f); close()
                    moveTo(12f, 5f)
                    cubicTo(12f, 1f, 15f, 1f, 17f, 0f)
                    cubicTo(17f, 3f, 15f, 5f, 12f, 5f); close()
                }, appleColor)
            }
        }
    }
}
