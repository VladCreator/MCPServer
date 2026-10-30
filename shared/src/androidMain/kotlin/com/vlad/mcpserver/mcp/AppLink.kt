package com.vlad.mcpserver.mcp

import java.net.URI

internal object AppLink {
    fun validate(raw: String) {
        require(raw.length in 1..2048 && raw.none { it.isWhitespace() || it.code < 32 }) {
            "URI must be 1-2048 characters without whitespace/control characters."
        }
        val uri = try { URI(raw) } catch (_: java.net.URISyntaxException) {
            throw IllegalArgumentException("Invalid app-link URI.")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme != null && Regex("[a-z][a-z0-9+.-]*").matches(scheme) &&
            scheme !in setOf("file", "content", "intent", "javascript", "data", "tel", "sms", "smsto",
                "mailto", "package", "market", "android-app") &&
            !uri.isOpaque && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Only hierarchical HTTP(S)/custom app links without credentials are supported."
        }
    }
}
