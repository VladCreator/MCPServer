package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.*

internal object BuildProvenance {
    const val PREFIX = "com.vlad.tester.provenance."
    fun parse(schema: Int?, sha: String?, artifact: String?): JsonObject = buildJsonObject {
        val present = schema != null || sha != null || artifact != null
        val valid = schema == 1 && (sha == null || Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}").matches(sha)) &&
            (artifact == null || Regex("[A-Za-z0-9._-]{1,160}").matches(artifact)) && (sha != null || artifact != null)
        put("sha", if (valid && sha != null) JsonPrimitive(sha.lowercase()) else JsonNull)
        put("ci_artifact", if (valid && artifact != null) JsonPrimitive(artifact) else JsonNull)
        put("provenance_status", if (!present) "unknown" else if (valid) "app_reported_unverified" else "invalid_contract")
        put("independently_verified", false)
        put("provenance_reason", when {
            !present -> "Target exposes no manifest provenance contract; installation dates do not establish a fix."
            valid -> "Manifest metadata supplied by the target build; not independent CI or source verification."
            else -> "Invalid provenance metadata; require schema 1, hex SHA and safe opaque artifact ID."
        })
    }
}
