package com.vlad.mcpserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopEvidenceClientTest {
    private val key = "a".repeat(48)
    private val fingerprint = "b".repeat(64)

    @Test
    fun pairingRequiresHttpsExplicitPortAndExactCredentials() {
        assertEquals("https://localhost:8766",
            validateDesktopPairing(DesktopPairing("https://localhost:8766/", key, fingerprint)).url)
        validateDesktopPairing(DesktopPairing("https://192.168.1.10:8766", key, fingerprint))
        for (url in listOf("http://localhost:8766", "https://localhost", "https://user@localhost:8766",
            "https://localhost:8766/path", "https://localhost:8766?token=bad", "https://localhost:8766/#fragment",
            "https://localhost:99999")) {
            assertFailsWith<IllegalArgumentException> { validateDesktopPairing(DesktopPairing(url, key, fingerprint)) }
        }
        assertFailsWith<IllegalArgumentException> {
            validateDesktopPairing(DesktopPairing("https://localhost:8766", "short", fingerprint))
        }
        assertFailsWith<IllegalArgumentException> {
            validateDesktopPairing(DesktopPairing("https://localhost:8766", key, "wrong-certificate"))
        }
    }
}
