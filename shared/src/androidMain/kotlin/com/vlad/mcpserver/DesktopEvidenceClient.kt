package com.vlad.mcpserver

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

internal data class DesktopPairing(val url: String, val key: String, val fingerprint: String)
internal data class UsbPairingOffer(val pairing: DesktopPairing, val code: String)
internal const val DESKTOP_MAX_BYTES = 100L * 1024 * 1024

internal fun validateDesktopPairing(pair: DesktopPairing): DesktopPairing {
    val uri = URI(pair.url)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.rawQuery == null && uri.fragment == null && uri.path in listOf("", "/") &&
        uri.port in 1..65535) { "Use https://DESKTOP_IP:8766 or https://localhost:8766 for USB." }
    require(pair.key.matches(Regex("[a-f0-9]{48}")) && pair.fingerprint.matches(Regex("[a-f0-9]{64}"))) {
        "Copy the pairing key and SHA-256 fingerprint exactly from Desktop."
    }
    return pair.copy(url = pair.url.trimEnd('/'))
}

internal class DesktopEvidenceClient(context: Context) {
    private val prefs = context.getSharedPreferences("desktop-pairing", Context.MODE_PRIVATE)
    private val usbPrefs = context.getSharedPreferences("desktop-usb-offer", Context.MODE_PRIVATE)
    private val cache = context.cacheDir

    fun usbOffer(): UsbPairingOffer? {
        val expires = usbPrefs.getLong("expires", 0)
        if (expires == 0L) return null
        val remaining = expires - android.os.SystemClock.elapsedRealtime()
        if (remaining !in 1..120_000) {
            clearUsbOffer()
            return null
        }
        val key = decryptSecret(usbPrefs.getString("key", null).orEmpty())
            ?: error("Cannot decrypt USB pairing offer. Press Pair on Desktop again.")
        return UsbPairingOffer(validateDesktopPairing(DesktopPairing("https://localhost:8766", key,
            usbPrefs.getString("fingerprint", null).orEmpty())), usbPrefs.getString("code", null).orEmpty())
    }

    fun clearUsbOffer() {
        check(usbPrefs.edit().clear().commit()) { "Cannot clear USB pairing offer." }
    }

    fun pairing(): DesktopPairing? {
        val url = prefs.getString("url", null) ?: return null
        val cipher = prefs.getString("key", null) ?: error("Desktop pairing key is missing. Pair again.")
        val key = decryptSecret(cipher) ?: error("Cannot decrypt Desktop pairing key. Pair again.")
        return validateDesktopPairing(DesktopPairing(url, key, prefs.getString("fingerprint", null)
            ?: error("Desktop certificate fingerprint is missing.")))
    }

    fun pair(pairing: DesktopPairing): String {
        val pair = validateDesktopPairing(pairing)
        val response = status(pair)
        val cipher = encryptSecret(pair.key) ?: error("Cannot securely store Desktop pairing key.")
        check(prefs.edit().putString("url", pair.url).putString("key", cipher)
            .putString("fingerprint", pair.fingerprint).commit()) { "Cannot save Desktop pairing." }
        return response
    }

    fun forget() { check(prefs.edit().clear().commit()) { "Cannot clear Desktop pairing." } }

    fun status(pair: DesktopPairing = pairing() ?: error("Pair with Desktop first.")): String =
        request(pair, "/status", null, null)

    fun sendFlow(repository: QaEvidenceRepository, flowId: Long, expectedIds: Set<String>): String {
        val pair = pairing() ?: error("Pair with Desktop first.")
        val entries = repository.list().filter { it.flowId == flowId }
        require(entries.isNotEmpty() && entries.size <= 200 && entries.map { it.id }.toSet() == expectedIds) {
            "Flow evidence changed. Refresh and confirm Send again."
        }
        val archive = File.createTempFile("flow-$flowId-", ".zip", cache)
        try {
            var total = 0L
            val transfers = planTransfers(entries)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                val manifest = buildJsonObject {
                    put("flow_id", flowId)
                    put("files", buildJsonArray {
                        transfers.forEach { transfer ->
                            val item = transfer.item
                            add(buildJsonObject {
                                put("file", transfer.name)
                                put("display_name", item.name)
                                item.sourceName?.let { put("source_name", it) }
                                put("evidence_id", item.id)
                                put("created_at", item.created)
                                put("origin", item.origin)
                                put("mime", item.mime)
                            })
                        }
                    })
                }.toString().toByteArray()
                require(manifest.size <= 256 * 1024) { "Flow manifest exceeds limit." }
                total += manifest.size
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest)
                zip.closeEntry()
                transfers.forEach { transfer ->
                    val item = transfer.item
                    val staged = repository.stage(item)
                    try {
                        zip.putNextEntry(ZipEntry(transfer.name))
                        staged.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= DESKTOP_MAX_BYTES) { "Flow evidence exceeds the 100 MiB transfer limit." }
                                zip.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                    } finally {
                        check(staged.delete()) { "Cannot remove staged flow evidence." }
                    }
                }
            }
            require(archive.length() in 1..DESKTOP_MAX_BYTES) { "Compressed evidence exceeds 100 MiB." }
            return request(pair, "/evidence", archive, flowId)
        } finally {
            check(archive.delete()) { "Cannot remove temporary flow archive." }
        }
    }

    private data class PlannedTransfer(val item: QaEvidence, val name: String)

    private fun planTransfers(entries: List<QaEvidence>): List<PlannedTransfer> {
        val used = linkedSetOf<String>()
        return entries.mapIndexed { index, item ->
            val extension = item.name.substringAfterLast('.').lowercase().ifBlank { "bin" }
            val preferred = sanitizeTransferName(item.name)
            val baseFallback = "e${index + 1}"
            val preferredOrFallback = preferred ?: "$baseFallback.$extension"
            var selected = preferredOrFallback
            var suffix = 1
            while (selected in used) {
                selected = "$baseFallback-$suffix.$extension"
                suffix++
            }
            used += selected
            PlannedTransfer(item, selected)
        }
    }

    private fun sanitizeTransferName(name: String): String? {
        if (!name.contains('.')) return null
        val base = name.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .take(70)
            .ifBlank { return null }
        val extension = name.substringAfterLast('.').lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .take(8)
            .ifBlank { return null }
        val candidate = "$base.$extension"
        return candidate.takeIf {
            it.matches(Regex("[A-Za-z0-9_-]{1,80}\\.[a-z0-9]{1,8}"))
        }
    }

    private fun request(pairing: DesktopPairing, path: String, archive: File?, flowId: Long?): String {
        val pair = validateDesktopPairing(pairing)
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("Client certificates are unsupported.")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("Desktop certificate missing.")
                chain[0].checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(actual.toByteArray(), pair.fingerprint.toByteArray())) {
                    throw CertificateException("Desktop certificate changed. Pair again using its displayed fingerprint.")
                }
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val connection = URI(pair.url + path).toURL().openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = ssl.socketFactory
            // The explicit certificate pin identifies this self-signed desktop across USB and LAN addresses.
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = if (archive == null) 5_000 else 120_000
            connection.setRequestProperty("Authorization", "Bearer ${pair.key}")
            if (archive != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(archive.length())
                connection.setRequestProperty("Content-Type", "application/zip")
                connection.setRequestProperty("X-Tester-Flow", flowId.toString())
                archive.inputStream().use { input -> connection.outputStream.use { input.copyTo(it) } }
            }
            val status = connection.responseCode
            if (status != if (archive == null) 200 else 201) {
                throw IOException("Desktop returned HTTP $status. Check pairing, receiver status and transfer limits. No automatic retry.")
            }
            return connection.inputStream.use { input ->
                val bytes = input.readNBytes(4097)
                require(bytes.size <= 4096) { "Desktop response exceeds limit." }
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }
}
