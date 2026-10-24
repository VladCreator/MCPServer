package com.vlad.mcpserver.mcp

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

data class TlsMaterial(
    val keyStore: KeyStore,
    val fingerprint: String
)

/**
 * Creates and loads the self-signed certificate used for local HTTPS/WSS.
 *
 * The certificate is generated on first use and stored in the app's private
 * files directory. It is regenerated when it expires or when the device's
 * local IPv4 addresses change so clients can validate the SAN.
 */
object AndroidTls {
    const val ALIAS = "mcp"
    private const val PASSWORD_TEXT = "mcp-local-tls"

    /**
     * Some KeyStore implementations clear the password array after use, so a
     * fresh array must be supplied for every load/store operation.
     */
    fun password(): CharArray = PASSWORD_TEXT.toCharArray()

    private const val FILE_NAME = "mcp-tls.p12"
    private const val IPS_FILE_NAME = "mcp-tls.ips"
    private const val VALIDITY_MILLIS = 2L * 365L * 24L * 60L * 60L * 1000L

    fun loadOrCreate(context: Context, ipAddresses: List<String>): TlsMaterial {
        val file = File(context.filesDir, FILE_NAME)
        val ipsFile = File(context.filesDir, IPS_FILE_NAME)
        val requiredIps = (ipAddresses + "127.0.0.1").distinct().sorted()
        val previousIps = runCatching {
            ipsFile.readText().split(',').filter { it.isNotBlank() }.sorted()
        }.getOrNull()

        if (file.exists() && previousIps == requiredIps) {
            val loaded = runCatching { load(file) }.getOrNull()
            if (loaded != null && isCertificateValid(loaded.second)) {
                return TlsMaterial(loaded.first, fingerprint(loaded.second))
            }
        }

        generate(file, requiredIps)
        ipsFile.writeText(requiredIps.joinToString(","))
        val material = load(file)
        return TlsMaterial(material.first, fingerprint(material.second))
    }

    private fun isCertificateValid(certificate: X509Certificate): Boolean =
        runCatching { certificate.checkValidity() }.isSuccess

    private fun load(file: File): Pair<KeyStore, X509Certificate> {
        val keyStore = KeyStore.getInstance("PKCS12")
        file.inputStream().use { keyStore.load(it, password()) }
        val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate
            ?: error("TLS keystore does not contain alias $ALIAS")
        return keyStore to certificate
    }

    private fun generate(file: File, ips: List<String>): X509Certificate {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60L * 60L * 1000L)
        val notAfter = Date(now + VALIDITY_MILLIS)
        val subject = X500Name("CN=MCPServer Local, O=MCPServer, OU=Android, C=MX")

        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        val alternativeNames = buildList {
            add(GeneralName(GeneralName.dNSName, "localhost"))
            ips.distinct().forEach { add(GeneralName(GeneralName.iPAddress, it)) }
        }.toTypedArray()

        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(alternativeNames)
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(ALIAS, keyPair.private, password(), arrayOf(certificate))
        file.parentFile?.mkdirs()
        file.outputStream().use { keyStore.store(it, password()) }
        return certificate
    }

    private fun fingerprint(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { byte ->
            val value = byte.toInt() and 0xFF
            value.toString(16).padStart(2, '0').uppercase()
        }
    }
}
