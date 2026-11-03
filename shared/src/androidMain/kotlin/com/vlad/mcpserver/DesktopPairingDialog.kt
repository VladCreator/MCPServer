package com.vlad.mcpserver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopPairingDialog(client: DesktopEvidenceClient, onDismiss: () -> Unit, onPaired: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("https://localhost:8766") }
    var key by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Pair Tester Desktop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enable receiving in Desktop, then copy its URL, private key and SHA-256 fingerprint. For USB, enable the USB route there first.")
                OutlinedTextField(url, { url = it }, label = { Text("Desktop HTTPS URL") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it.trim() }, label = { Text("Pairing key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fingerprint, { fingerprint = it.trim().lowercase() }, label = { Text("SHA-256 fingerprint") },
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { client.forget() }
                            onPaired("Desktop not paired")
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = "Cannot forget Desktop: ${failure.localizedMessage}"
                        } finally { busy = false }
                    }
                }) { Text("Forget") }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        client.pair(DesktopPairing(url.trim(), key, fingerprint))
                    }
                    onPaired(result)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = "Pairing failed: ${failure.localizedMessage}"
                } finally { busy = false }
            }
        }) { Text(if (busy) "Pairing..." else "Pair") } })
}
