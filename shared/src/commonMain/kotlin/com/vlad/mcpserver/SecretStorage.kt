package com.vlad.mcpserver

/**
 * Marker for secrets encrypted at rest. Plaintext values written by older
 * versions are migrated on load.
 */
object SecretCodec {
    const val PREFIX = "enc:v1:"

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
}

/**
 * Encrypts a secret with the platform keystore. Returns null when the platform
 * cannot encrypt; callers may fall back to plaintext persistence.
 */
expect fun encryptSecret(plainText: String): String?

/**
 * Decrypts a value produced by [encryptSecret]. Returns null when the value is
 * not encrypted or cannot be decrypted (for example after a keystore reset).
 */
expect fun decryptSecret(cipherText: String): String?
