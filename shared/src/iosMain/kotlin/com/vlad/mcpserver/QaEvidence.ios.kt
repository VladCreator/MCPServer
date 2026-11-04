package com.vlad.mcpserver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
actual fun QaEvidenceCard(modifier: Modifier, evidenceRevision: Int) {
    Column(modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 10.dp)) {
        Text("On-device evidence browsing is currently available on Android.")
    }
}
