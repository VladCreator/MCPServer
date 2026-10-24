package com.vlad.mcpserver

/**
 * iOS has no server implementation yet. Values are kept as-is; the shared UI
 * only needs the token to round-trip.
 */
actual fun encryptSecret(plainText: String): String? = plainText

actual fun decryptSecret(cipherText: String): String? = null
