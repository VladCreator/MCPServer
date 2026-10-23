package com.vlad.mcpserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for AppViewModel covering server state, client approval/rejection, token generation, SSL/TLS, and Theme toggling.
 */
class AppViewModelTest {

    @Test
    fun testServerToggleState() {
        val viewModel = AppViewModel()
        assertFalse(viewModel.isServerRunning)
        
        viewModel.toggleServer()
        assertTrue(viewModel.isServerRunning)
        
        viewModel.toggleServer()
        assertFalse(viewModel.isServerRunning)
    }

    @Test
    fun testClientApprovalFlow() {
        val viewModel = AppViewModel()
        
        // Initially empty
        assertEquals(0, viewModel.pendingClients.value.size)
        assertEquals(0, viewModel.connectedClients.value.size)
        
        // Manually simulate adding a pending client
        val testClient = AiClient(id = "test-ai-1", name = "Test AI Agent", status = "Pending Approval", connectedAt = 1700000000L)
        viewModel.pendingClients.value = listOf(testClient)
        
        assertEquals(1, viewModel.pendingClients.value.size)
        
        // Approve client
        viewModel.approveClient("test-ai-1")
        
        assertEquals(0, viewModel.pendingClients.value.size)
        assertEquals(1, viewModel.connectedClients.value.size)
        assertEquals("Manually Approved", viewModel.connectedClients.value.first().status)
    }

    @Test
    fun testClientRejectionFlow() {
        val viewModel = AppViewModel()
        
        val testClient = AiClient(id = "test-ai-2", name = "Malicious AI", status = "Pending Approval", connectedAt = 1700000000L)
        viewModel.pendingClients.value = listOf(testClient)
        
        assertEquals(1, viewModel.pendingClients.value.size)
        
        // Reject client
        viewModel.rejectClient("test-ai-2")
        
        assertEquals(0, viewModel.pendingClients.value.size)
        assertEquals(0, viewModel.connectedClients.value.size)
    }

    @Test
    fun testBearerTokenUpdate() {
        val viewModel = AppViewModel()
        val customToken = "custom_secure_ai_token_999"
        
        viewModel.updateBearerToken(customToken)
        assertEquals(customToken, viewModel.bearerToken)
    }

    @Test
    fun testSecureTokenGeneration() {
        val viewModel = AppViewModel()
        val generatedToken = viewModel.generateSecureToken()
        
        assertEquals(14, generatedToken.length)
        assertEquals(generatedToken, viewModel.bearerToken)
    }

    @Test
    fun testSslToggle() {
        val viewModel = AppViewModel()
        assertFalse(viewModel.sslEnabled)
        
        viewModel.updateSslEnabled(true)
        assertTrue(viewModel.sslEnabled)
        
        viewModel.updateSslEnabled(false)
        assertFalse(viewModel.sslEnabled)
    }

    @Test
    fun testDarkModeToggle() {
        val viewModel = AppViewModel()
        assertTrue(viewModel.isDarkMode)
        
        viewModel.updateDarkMode(false)
        assertFalse(viewModel.isDarkMode)
        
        viewModel.updateDarkMode(true)
        assertTrue(viewModel.isDarkMode)
    }
}
