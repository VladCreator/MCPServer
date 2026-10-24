package com.vlad.mcpserver.mcp

/**
 * Validates the `Authorization: Bearer <token>` header.
 *
 * The comparison is constant-time with respect to the token contents to avoid
 * leaking a prefix through timing. A blank configured token disables access
 * instead of authorizing everybody.
 */
object BearerAuth {

    fun isValid(header: String?, expectedToken: String): Boolean {
        val expected = expectedToken.trim()
        if (expected.isEmpty()) return false

        val raw = header?.trim().orEmpty()
        val prefix = "Bearer "
        if (raw.length <= prefix.length) return false
        if (!raw.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return false

        val provided = raw.substring(prefix.length).trim()
        if (provided.isEmpty()) return false
        return constantTimeEquals(provided, expected)
    }

    fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.encodeToByteArray()
        val rightBytes = right.encodeToByteArray()
        if (leftBytes.size != rightBytes.size) return false
        var difference = 0
        for (index in leftBytes.indices) {
            difference = difference or (leftBytes[index].toInt() xor rightBytes[index].toInt())
        }
        return difference == 0
    }

    fun sanitizeToken(value: String): String = value.trim()
}
