package com.vlad.mcpserver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
actual fun QaEvidenceCard(modifier: Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text("QA Evidence", style = MaterialTheme.typography.titleSmall)
            Text("On-device evidence browsing is currently available on Android.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
