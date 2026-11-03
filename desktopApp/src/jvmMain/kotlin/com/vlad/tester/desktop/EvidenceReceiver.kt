package com.vlad.tester.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

internal const val EVIDENCE_PORT = 8766
internal const val MAX_EVIDENCE_BYTES = 100L * 1024 * 1024
internal val EVIDENCE_EXTENSIONS = setOf("md", "txt", "log", "json", "xml", "jpg", "jpeg", "png", "webp", "mp4", "webm", "pdf")

internal data class ReceivedFlow(val folder: File, val count: Int, val receivedAt: Long)

internal class EvidenceReceiver(private val inbox: File = File(
    System.getProperty("user.home"), "Library/Application Support/Tester Desktop/QA Evidence"
), port: Int = EVIDENCE_PORT) : AutoCloseable {
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val fingerprint: String
    private val server: HttpsServer
    val port: Int get() = server.address.port
    private val executor = Executors.newFixedThreadPool(2)
    private data class UsbKey(val value: String, val expires: Long)
    private val usbTokens = java.util.concurrent.ConcurrentHashMap<String, UsbKey>()

    fun usbKey(deviceId: String): String =
        ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            .also { usbTokens[deviceId] = UsbKey(it, System.currentTimeMillis() + 120_000) }

    fun revokeUsbKey(deviceId: String) { usbTokens.remove(deviceId) }
    fun usbPaired(deviceId: String): Boolean = usbTokens[deviceId]?.expires == Long.MAX_VALUE
    @Volatile var latestError: String? = null
        private set
    @Volatile var received: List<ReceivedFlow> = emptyList()
        private set

    init {
        System.setProperty("sun.net.httpserver.maxReqTime", "120")
        System.setProperty("sun.net.httpserver.maxRspTime", "120")
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=Tester Desktop")
        val now = System.currentTimeMillis()
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(subject, BigInteger(128, SecureRandom()), Date(now - 60_000),
                Date(now + 24 * 60 * 60_000), subject, keys.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        )
        fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        val password = token.toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("desktop", keys.private, password, arrayOf(certificate))
        }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val ssl = SSLContext.getInstance("TLS").apply { init(factory.keyManagers, null, SecureRandom()) }
        if (inbox.isDirectory) {
            received = checkNotNull(inbox.listFiles()).filter {
                it.isDirectory && it.name.matches(Regex("flow-[0-9]{1,19}-[a-f0-9]{8}"))
            }.map { ReceivedFlow(it, checkNotNull(it.listFiles()).count { file -> file.isFile && file.name != "manifest.json" },
                it.lastModified()) }.sortedByDescending { it.receivedAt }.take(100)
        }
        server = HttpsServer.create(InetSocketAddress("0.0.0.0", port), 8)
        server.httpsConfigurator = HttpsConfigurator(ssl)
        server.executor = executor
        server.createContext("/status") { exchange -> handle(exchange, false) }
        server.createContext("/evidence") { exchange -> handle(exchange, true) }
        server.start()
    }

    private fun handle(exchange: HttpExchange, upload: Boolean) {
        try {
            val supplied = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val usb = usbTokens.entries.firstOrNull {
                it.value.expires > System.currentTimeMillis() &&
                    MessageDigest.isEqual(supplied.toByteArray(), "Bearer ${it.value.value}".toByteArray())
            }
            if ((listOf(token) + listOfNotNull(usb?.value?.value)).none {
                    MessageDigest.isEqual(supplied.toByteArray(), "Bearer $it".toByteArray())
                }) {
                reply(exchange, 401, "Pair with Tester Desktop first.")
                return
            }
            if (exchange.requestURI.path != if (upload) "/evidence" else "/status") {
                reply(exchange, 404, "Unknown endpoint."); return
            }
            if (exchange.requestMethod != if (upload) "POST" else "GET") {
                reply(exchange, 405, "Unsupported method."); return
            }
            if (!upload) {
                usb?.let { usbTokens.replace(it.key, it.value, it.value.copy(expires = Long.MAX_VALUE)) }
                reply(exchange, 200, "Tester Desktop active - evidence receiver ready.")
                return
            }
            if (usb != null && usb.value.expires != Long.MAX_VALUE) {
                reply(exchange, 403, "Confirm pairing on Android first."); return
            }
            val flow = exchange.requestHeaders.getFirst("X-Tester-Flow").orEmpty()
            if (!flow.matches(Regex("[0-9]{1,19}"))) {
                reply(exchange, 400, "Select a linked QA flow."); return
            }
            val length = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
            if (length == null || length !in 1..MAX_EVIDENCE_BYTES) {
                reply(exchange, 413, "Archive exceeds 100 MiB or lacks its length."); return
            }
            if (exchange.requestHeaders.getFirst("Content-Type") != "application/zip") {
                reply(exchange, 415, "Expected ZIP evidence."); return
            }
            check(inbox.isDirectory || inbox.mkdirs()) { "Cannot create QA Evidence inbox." }
            val archive = File.createTempFile("upload-", ".zip", inbox)
            try {
                archive.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    val deadline = System.nanoTime() + 120_000_000_000L
                    exchange.requestBody.use { input ->
                        while (true) {
                            if (System.nanoTime() > deadline) throw IOException("Evidence upload timed out.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= length && total <= MAX_EVIDENCE_BYTES) { "Evidence archive exceeds limit." }
                            out.write(buffer, 0, count)
                        }
                    }
                    require(total == length) { "Incomplete evidence upload." }
                }
                val result = importFlow(archive, inbox, flow)
                synchronized(this) { received = listOf(result) + received.take(99) }
                latestError = null
                reply(exchange, 201, "Received Flow #$flow: ${result.count} files.")
            } finally {
                check(archive.delete()) { "Cannot remove temporary evidence upload." }
            }
        } catch (error: IOException) {
            latestError = "Evidence transfer failed: ${error.message}"
            reply(exchange, 500, "Evidence transfer failed. Check Desktop Activity.")
        } catch (error: IllegalArgumentException) {
            latestError = "Evidence rejected: ${error.message}"
            reply(exchange, 400, "Invalid or oversized evidence archive.")
        } catch (error: IllegalStateException) {
            latestError = "Evidence storage failed: ${error.message}"
            reply(exchange, 500, "Evidence storage failed. Check Desktop Activity.")
        } finally {
            exchange.close()
        }
    }

    private fun reply(exchange: HttpExchange, code: Int, message: String) {
        val bytes = message.toByteArray()
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

internal fun importFlow(archive: File, inbox: File, flow: String): ReceivedFlow {
    require(flow.matches(Regex("[0-9]{1,19}"))) { "Invalid flow ID." }
    require(archive.length() in 1..MAX_EVIDENCE_BYTES) { "Archive exceeds 100 MiB." }
    val folder = Files.createTempDirectory(inbox.toPath(), ".receiving-").toFile()
    val saved = mutableListOf<File>()
    var published = false
    try {
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name.matches(Regex("[A-Za-z0-9_-]{1,80}\\.[a-z0-9]{1,8}")) &&
                    entry.name.substringAfterLast('.') in EVIDENCE_EXTENSIONS) { "Invalid evidence filename." }
                require(saved.size < 201 && saved.none { it.name == entry.name }) { "Too many files or duplicate names." }
                val file = File(folder, entry.name)
                saved += file
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_EVIDENCE_BYTES) { "Expanded evidence exceeds 100 MiB." }
                        out.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
            }
        }
        require(saved.isNotEmpty()) { "Empty evidence archive." }
        val manifest = saved.singleOrNull { it.name == "manifest.json" }
            ?: throw IllegalArgumentException("Missing flow manifest.")
        require(manifest.length() <= 256 * 1024) { "Manifest exceeds limit." }
        val description = Json.parseToJsonElement(manifest.readText()) as? JsonObject
            ?: throw IllegalArgumentException("Invalid manifest.")
        require(description["flow_id"]?.jsonPrimitive?.content == flow) { "Manifest flow mismatch." }
        val files = description["files"] as? JsonArray ?: throw IllegalArgumentException("Missing file list.")
        val names = files.map {
            (it as? JsonObject)?.get("file")?.jsonPrimitive?.content ?: throw IllegalArgumentException("Invalid file list.")
        }
        require(names.size == names.distinct().size && names.toSet() == saved.filter { it != manifest }.map { it.name }.toSet()
            && names.isNotEmpty()) { "Manifest file list mismatch." }
        val destination = File(inbox, "flow-$flow-${UUID.randomUUID().toString().take(8)}")
        Files.move(folder.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        published = true
        return ReceivedFlow(destination, saved.size - 1, System.currentTimeMillis())
    } finally {
        if (!published) {
            saved.forEach { check(it.delete()) { "Cannot clean incomplete evidence file." } }
            check(folder.delete()) { "Cannot clean incomplete evidence folder." }
        }
    }
}
