package com.vlad.tester.desktop

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvidenceReceiverTest {
    private fun archive(root: File, names: List<String>, flow: String = "12"): File {
        val archive = File.createTempFile("flow-", ".zip", root)
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"flow_id":$flow,"files":[${names.joinToString { """{"file":"$it","original_name":"example"}""" }}]}""".toByteArray())
            zip.closeEntry()
            names.forEach {
                zip.putNextEntry(ZipEntry(it))
                zip.write("evidence".toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }

    @Test
    fun importRequiresManifestAndRejectsTraversalAndExecutableFiles() {
        val root = kotlin.io.path.createTempDirectory("tester-inbox-").toFile()
        try {
            for (name in listOf("../escape.txt", "folder/file.txt", "run.sh", "app.apk")) {
                val zip = archive(root, listOf(name))
                try {
                    assertFailsWith<IllegalArgumentException> { importFlow(zip, root, "12") }
                    assertTrue(root.listFiles()!!.none { it.isDirectory })
                } finally { zip.delete() }
            }
            val zip = archive(root, listOf("e1.md", "e2.png"))
            val result = importFlow(zip, root, "12")
            assertEquals(2, result.count)
            assertEquals(setOf("manifest.json", "e1.md", "e2.png"), result.folder.listFiles()!!.map { it.name }.toSet())
            result.folder.listFiles()!!.forEach { it.delete() }
            result.folder.delete()
            zip.delete()
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }

    @Test
    fun flowMismatchAndMissingManifestNeverPublishPartialFiles() {
        val root = kotlin.io.path.createTempDirectory("tester-inbox-").toFile()
        try {
            val zip = archive(root, listOf("e1.md"), "13")
            assertFailsWith<IllegalArgumentException> { importFlow(zip, root, "12") }
            zip.delete()
            val noManifest = File(root, "bad.zip")
            ZipOutputStream(noManifest.outputStream()).use { zipOut ->
                zipOut.putNextEntry(ZipEntry("e1.txt")); zipOut.write("evidence".toByteArray()); zipOut.closeEntry()
            }
            assertFailsWith<IllegalArgumentException> { importFlow(noManifest, root, "12") }
            assertTrue(root.listFiles()!!.none { it.isDirectory })
            noManifest.delete()
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }

    @Test
    fun oversizedExpandedArchivesAndFileCountsNeverPublish() {
        val root = kotlin.io.path.createTempDirectory("tester-limits-").toFile()
        try {
            val manyFiles = archive(root, (1..201).map { "e$it.txt" })
            assertFailsWith<IllegalArgumentException> { importFlow(manyFiles, root, "12") }
            assertTrue(root.listFiles()!!.none { it.isDirectory })
            assertTrue(manyFiles.delete())

            val expanded = File(root, "expanded.zip")
            ZipOutputStream(expanded.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("e1.txt"))
                val chunk = ByteArray(1024 * 1024)
                repeat(101) { zip.write(chunk) }
                zip.closeEntry()
            }
            assertTrue(expanded.length() < MAX_EVIDENCE_BYTES)
            assertFailsWith<IllegalArgumentException> { importFlow(expanded, root, "12") }
            assertTrue(root.listFiles()!!.none { it.isDirectory })
            assertTrue(expanded.delete())
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }

    @Test
    fun realHttpsReceiverAuthenticatesStatusAndPersistsAnUploadedFlow() {
        val root = kotlin.io.path.createTempDirectory("tester-https-").toFile()
        try {
            EvidenceReceiver(root, 0).use { receiver ->
                val ssl = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(object : X509TrustManager {
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                            val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                                .joinToString("") { "%02x".format(it) }
                            check(actual == receiver.fingerprint) { "Wrong desktop certificate." }
                        }
                    }), null)
                }
                fun connect(path: String, key: String): HttpsURLConnection =
                    (URI("https://localhost:${receiver.port}$path").toURL().openConnection() as HttpsURLConnection).apply {
                        sslSocketFactory = ssl.socketFactory
                        hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                        connectTimeout = 5000; readTimeout = 5000
                        setRequestProperty("Authorization", "Bearer $key")
                    }
                val unauthorized = connect("/status", "wrong")
                try { assertEquals(401, unauthorized.responseCode) } finally { unauthorized.disconnect() }
                val status = connect("/status", receiver.token)
                try {
                    assertEquals(200, status.responseCode)
                    assertTrue(status.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.contains("active"))
                } finally { status.disconnect() }
                val zip = archive(root, listOf("e1.txt"))
                val upload = connect("/evidence", receiver.token)
                try {
                    upload.requestMethod = "POST"; upload.doOutput = true
                    upload.setFixedLengthStreamingMode(zip.length())
                    upload.setRequestProperty("Content-Type", "application/zip")
                    upload.setRequestProperty("X-Tester-Flow", "12")
                    zip.inputStream().use { input -> upload.outputStream.use { input.copyTo(it) } }
                    assertEquals(201, upload.responseCode)
                    assertEquals(1, receiver.received.single().count)
                    assertTrue(File(receiver.received.single().folder, "e1.txt").isFile)
                } finally { upload.disconnect(); zip.delete() }
                receiver.received.forEach { flow ->
                    flow.folder.listFiles()!!.forEach { it.delete() }; flow.folder.delete()
                }
            }
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }
}
