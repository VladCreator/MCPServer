package com.vlad.tester.desktop

import androidx.compose.animation.animateContentSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.awt.Desktop
import java.net.NetworkInterface
import java.net.Inet4Address
import java.net.SocketException

@Composable
internal fun EvidenceInbox(
    manager: DeviceManager,
    devices: List<Device>,
    busy: Boolean,
    report: (ActivityKind, String) -> Unit,
    statusController: EvidenceStatusController? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var receiver by remember { mutableStateOf<EvidenceReceiver?>(null) }
    var flows by remember { mutableStateOf(emptyList<ReceivedFlow>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var receiverError by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var usbDevice by remember { mutableStateOf<Device?>(null) }
    var usbCode by remember { mutableStateOf<String?>(null) }
    var usbExpired by remember { mutableStateOf(false) }
    var usbPaired by remember { mutableStateOf(false) }
    var activeDevices by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(usbCode) {
        usbExpired = false
        usbPaired = false
        if (usbCode != null) {
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                if (receiver?.usbPaired(usbDevice?.id.orEmpty()) == true) {
                    usbPaired = true
                    break
                }
                delay(500)
            }
            usbExpired = !usbPaired
        }
    }
    DisposableEffect(Unit) { onDispose { receiver?.close() } }
    LaunchedEffect(receiver, devices) {
        activeDevices = emptySet()
        while (receiver != null) {
            flows = receiver?.received.orEmpty()
            receiverError = receiver?.latestError
            activeDevices = devices.filter { receiver?.usbActive(it.id) == true }.map { it.id }.toSet()
            delay(1000)
        }
    }
    fun enableReceiver(openPairingAfterStart: Boolean) {
        starting = true
        scope.launch {
            var created: EvidenceReceiver? = null
            try {
                receiver = withContext(Dispatchers.IO) { EvidenceReceiver().also { created = it } }
                error = null
                if (openPairingAfterStart) pairing = true
                report(ActivityKind.EVIDENCE, "HTTPS evidence receiver enabled on port $EVIDENCE_PORT.")
            } catch (cancelled: CancellationException) {
                created?.close()
                throw cancelled
            } catch (failure: Exception) {
                error = "Cannot enable evidence receiver: ${failure.message}"
                report(ActivityKind.ERROR, error!!)
            } finally { starting = false }
        }
    }
    fun stopReceiver() {
        receiver?.close(); receiver = null
        report(ActivityKind.EVIDENCE, "Evidence receiver stopped. Devices must pair again after restart.")
    }
    SideEffect {
        statusController?.receivingEnabled = receiver != null
        statusController?.canEnable = receiver == null && !starting
        statusController?.canPair = receiver != null && !starting
        statusController?.canStop = receiver != null && !starting
        statusController?.onEnable = { enableReceiver(openPairingAfterStart = false) }
        statusController?.onPair = { if (receiver == null) enableReceiver(openPairingAfterStart = true) else pairing = true }
        statusController?.onStop = ::stopReceiver
    }
    Column(modifier.fillMaxSize().padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (statusController == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (receiver == null) TextButton(enabled = !starting, onClick = {
                        enableReceiver(openPairingAfterStart = true)
                    }) { Text("Enable receiving") }
                    else {
                        TextButton(enabled = !starting, onClick = { pairing = true }) { Text("Wi-Fi / advanced") }
                        TextButton(enabled = !starting, onClick = ::stopReceiver) { Text("Stop") }
                    }
                }
            }
            if (receiver != null) {
                ActiveStatusBadge("Active - ready to receive")
                Text("Encrypted USB / Wi-Fi transfers. Device pairing is separate.",
                    style = MaterialTheme.typography.bodySmall)
            } else Text("Disabled. Enable to receive flow evidence from Android.",
                style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            receiverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            devices.filter { it.platform == Platform.ANDROID && it.canInstall }.forEach { device ->
                Row {
                    Text(device.name, Modifier.weight(1f))
                    if (device.id in activeDevices) ActiveStatusBadge("Connected")
                    else TextButton(enabled = !busy && !starting, onClick = {
                        starting = true
                        scope.launch {
                            var created: EvidenceReceiver? = null
                            try {
                                val current = receiver ?: withContext(Dispatchers.IO) {
                                    EvidenceReceiver().also { created = it }
                                }.also { receiver = it }
                                val code = String.format(java.util.Locale.ROOT, "%02d", java.security.SecureRandom().nextInt(100))
                                val key = current.usbKey(device.id)
                                try {
                                    withContext(Dispatchers.IO) {
                                        manager.pairEvidenceUsb(device, key, current.fingerprint, code)
                                    }
                                } catch (failure: Exception) {
                                    current.revokeUsbKey(device.id)
                                    throw failure
                                }
                                usbDevice = device
                                usbCode = code
                                error = null
                                report(ActivityKind.PAIRING, "USB pairing offered to ${device.name}. Confirm the matching number on Android.")
                            } catch (cancelled: CancellationException) {
                                if (receiver == null) created?.close()
                                throw cancelled
                            } catch (failure: Exception) {
                                error = "USB pairing failed: ${failure.message}"
                                report(ActivityKind.ERROR, error!!)
                            } finally { starting = false }
                        }
                    }) { Text("Pair") }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).animateContentSize()) {
                items(flows, key = { it.folder.path }) { flow ->
                    TextButton(onClick = {
                        try { Desktop.getDesktop().open(flow.folder) }
                        catch (failure: Exception) { error = "Cannot open received folder: ${failure.message}" }
                    }) { Text("${flow.folder.name} - ${flow.count} files (open folder)") }
                }
            }
    }
    if (usbCode != null) AlertDialog(onDismissRequest = { usbCode = null },
        title = { Text("Pair ${usbDevice?.name}") },
        text = {
            Column {
                Text(usbCode!!, style = MaterialTheme.typography.displayLarge)
                Text(if (usbPaired) "Android confirmed pairing. The requested flow can now transfer."
                    else if (usbExpired) "Pairing expired. Press Pair again."
                    else "On Android, open QA Evidence > Send. Confirm only if the same number appears. Expires in two minutes.")
            }
        }, confirmButton = { TextButton(onClick = { usbCode = null }) { Text("Done") } })
    if (pairing && receiver != null) {
        val current = receiver!!
        var addresses by remember { mutableStateOf(emptyList<String>()) }
        var addressError by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            try {
                addresses = withContext(Dispatchers.IO) {
                    NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                        .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }
                        .map { it.hostAddress }.distinct()
                }
            } catch (failure: SocketException) {
                addressError = "Cannot detect Wi-Fi addresses: ${failure.message}. USB pairing is still available."
            }
        }
        AlertDialog(onDismissRequest = { pairing = false }, title = { Text("Pair Tester with Desktop") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("On Android: QA Evidence > Desktop. Enter one URL, the pairing key and SHA-256 fingerprint below. Keep the key private.")
                    addressError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    SelectionContainer {
                        Text("Wi-Fi (same trusted network):\n${addresses.joinToString("\n") { "https://$it:$EVIDENCE_PORT" }}\n" +
                            "USB: https://localhost:$EVIDENCE_PORT\n\nKey: ${current.token}\n\nSHA-256: ${current.fingerprint}")
                    }
                    devices.filter { it.platform == Platform.ANDROID && it.canInstall }.forEach { device ->
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { manager.enableEvidenceUsb(device) }
                                    error = null
                                    report(ActivityKind.PAIRING, "USB evidence route enabled for ${device.name}.")
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = "USB evidence route failed: ${failure.message}"; report(ActivityKind.ERROR, error!!)
                                }
                            }
                        }) { Text("Enable USB for ${device.name}") }
                    }
                    Text("Stopping or restarting the receiver changes its key/certificate. Pair again. Wi-Fi may require allowing incoming connections in macOS Firewall.")
                }
            }, confirmButton = { TextButton(onClick = { pairing = false }) { Text("Done") } })
    }
}
