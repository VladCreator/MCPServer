package com.vlad.tester.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun ActiveStatusBadge(text: String) {
    val dark = isSystemInDarkTheme()
    Surface(
        color = if (dark) Color(0xFF143525) else Color(0xFFDCFCE7),
        contentColor = if (dark) Color(0xFF86EFAC) else Color(0xFF166534),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall)
    }
}
