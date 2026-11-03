package com.vlad.mcpserver.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayHeartbeatTest {
    @Test
    fun readsOnlyExplicitPingMetadataAndExpiresVerification() {
        val body = """{"id":3,"method":"ping","params":{"_meta":{"tester_gateway":{"agent_initialized":true,"adb_verified":true}}}}"""
        val connection = gatewayHeartbeat(body, "session", 1000)!!
        assertEquals("session", connection.clientId)
        assertTrue(connection.agentInitialized)
        assertTrue(connection.adbVerified)
        assertTrue(connection.isRecent(46_000))
        assertFalse(connection.isRecent(46_001))
        assertFalse(connection.copy(disconnected = true).isRecent(1000))
        assertNull(gatewayHeartbeat(body.replace("\"ping\"", "\"tools/call\""), "session", 0))
        assertNull(gatewayHeartbeat(body.replace("\"id\":3,", ""), "session", 0))
        assertNull(gatewayHeartbeat("""{"id":3,"method":"ping"}""", "session", 0))
        assertNull(gatewayHeartbeat("invalid", "session", 0))
    }
}
