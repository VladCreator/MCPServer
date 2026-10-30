package com.vlad.mcpserver

import com.vlad.mcpserver.mcp.BuildProvenance
import kotlinx.serialization.json.*
import kotlin.test.*

class BuildProvenanceTest {
    @Test fun missingProvenanceStaysUnknown() {
        val result = BuildProvenance.parse(null, null, null)
        assertEquals("unknown", result["provenance_status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, result["sha"])
    }

    @Test fun validManifestClaimsAreNeverIndependentlyVerified() {
        val result = BuildProvenance.parse(1, "A".repeat(40), "build-42")
        assertEquals("app_reported_unverified", result["provenance_status"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), result["independently_verified"])
        assertEquals("a".repeat(40), result["sha"]!!.jsonPrimitive.content)
    }

    @Test fun malformedClaimsDoNotLeakArbitraryMetadata() {
        for (result in listOf(BuildProvenance.parse(2, "f".repeat(40), "build"),
                BuildProvenance.parse(1, "secret", null), BuildProvenance.parse(1, null, "https://user:secret@ci"),
                BuildProvenance.parse(1, null, null))) {
            assertEquals("invalid_contract", result["provenance_status"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, result["sha"])
            assertEquals(JsonNull, result["ci_artifact"])
            assertFalse(result.toString().contains("secret"))
        }
    }
}
