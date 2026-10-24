package com.vlad.mcpserver

import com.vlad.mcpserver.mcp.BearerAuth
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BearerAuthTest {

    @Test
    fun acceptsValidBearerHeader() {
        assertTrue(BearerAuth.isValid("Bearer secret-token", "secret-token"))
    }

    @Test
    fun schemeIsCaseInsensitive() {
        assertTrue(BearerAuth.isValid("bearer secret-token", "secret-token"))
        assertTrue(BearerAuth.isValid("BEARER secret-token", "secret-token"))
    }

    @Test
    fun rejectsWrongToken() {
        assertFalse(BearerAuth.isValid("Bearer nope", "secret-token"))
    }

    @Test
    fun rejectsMissingHeader() {
        assertFalse(BearerAuth.isValid(null, "secret-token"))
        assertFalse(BearerAuth.isValid("", "secret-token"))
    }

    @Test
    fun rejectsBlankConfiguredToken() {
        assertFalse(BearerAuth.isValid("Bearer ", ""))
        assertFalse(BearerAuth.isValid("Bearer anything", "   "))
    }

    @Test
    fun rejectsWrongScheme() {
        assertFalse(BearerAuth.isValid("Basic secret-token", "secret-token"))
    }

    @Test
    fun constantTimeComparisonWorks() {
        assertTrue(BearerAuth.constantTimeEquals("abc", "abc"))
        assertFalse(BearerAuth.constantTimeEquals("abc", "abd"))
        assertFalse(BearerAuth.constantTimeEquals("abc", "ab"))
    }
}
