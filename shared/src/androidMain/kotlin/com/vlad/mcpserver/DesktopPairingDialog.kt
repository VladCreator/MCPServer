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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesktopPairingDialog(client: DesktopEvidenceClient, onDismiss: () -> Unit, onPaired: (String) -> Unit,
    sendDescription: String? = null, onForgot: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("https://localhost:8766") }
    var key by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var offer by remember { mutableStateOf<UsbPairingOffer?>(null) }
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                offer = withContext(Dispatchers.IO) { client.usbOffer() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "Cannot read USB pairing: ${failure.localizedMessage}"
            }
            delay(1000)
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Pair Tester Desktop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connected by USB? On Desktop, press Pair beside your device. Then confirm that both screens show the same number.")
                offer?.let {
                    Text(it.code, style = MaterialTheme.typography.displayLarge)
                    Text("Only confirm if this number matches Desktop. Pairing expires after two minutes.")
                    sendDescription?.let { description -> Text(description) }
                }
                TextButton(enabled = !busy, onClick = { manual = !manual }) {
                    Text(if (manual) "Hide Wi-Fi setup" else "Wi-Fi / advanced setup")
                }
                if (manual) {
                    OutlinedTextField(url, { url = it }, label = { Text("Desktop HTTPS URL") }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it.trim() }, label = { Text("Pairing key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(fingerprint, { fingerprint = it.trim().lowercase() }, label = { Text("SHA-256 fingerprint") },
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    sendDescription?.let { Text(it) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { client.forget(); client.clearUsbOffer() }
                            onForgot()
                            onDismiss()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = "Cannot forget Desktop: ${failure.localizedMessage}"
                        } finally { busy = false }
                    }
                }) { Text("Forget") }
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { client.clearUsbOffer() }
                            onDismiss()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) { error = "Cannot cancel pairing: ${failure.localizedMessage}" }
                    }
                }) { Text("Cancel") }
            }
        },
        confirmButton = { TextButton(enabled = !busy && (offer != null || manual), onClick = {
            val approved = offer
            busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        if (!manual) {
                            check(approved != null && client.usbOffer() == approved) {
                                "Pairing changed or expired. Compare the new number and confirm again."
                            }
                        }
                        val status = client.pair(if (manual) DesktopPairing(url.trim(), key, fingerprint)
                            else requireNotNull(approved).pairing)
                        client.clearUsbOffer()
                        status
                    }
                    onPaired(result)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = "Pairing failed: ${failure.localizedMessage}"
                } finally { busy = false }
            }
        }) { Text(if (busy) "Pairing..." else if (!manual && offer != null)
            if (sendDescription != null) "Matches - pair and send" else "Number matches - pair"
            else if (sendDescription != null) "Pair and send" else "Pair") } })
}
