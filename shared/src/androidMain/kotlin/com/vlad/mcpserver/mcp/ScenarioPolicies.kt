package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.*
import java.util.UUID

/** Process/session-scoped consent, pinned to the complete expanded catalog plan and privacy policy. */
internal class ScenarioPolicies(private val now: () -> Long) {
    private data class Policy(val client: String, val plan: String, val expires: Long, var remaining: Int)
    private val policies = mutableMapOf<String, Policy>()

    fun identity(route: YamlRoute, privacySensitive: Boolean) = RunStore.hash(
        RunPrivacy.canonical(route.plan()) + ":privacy=$privacySensitive")

    @Synchronized fun grant(client: String, plan: String, count: Int, ttlMs: Long): JsonObject {
        require(count in 1..30 && ttlMs in 1000..900000) { "Policy supports 1-30 runs and 1000-900000 ms." }
        policies.entries.removeAll { it.value.expires <= now() }
        check(policies.size < 32) { "Policy capacity reached; revoke or wait for expiry." }
        val id = UUID.randomUUID().toString()
        policies[id] = Policy(client, plan, now() + ttlMs, count)
        return status(client, id)
    }

    @Synchronized fun consume(client: String, id: String, plan: String): Boolean {
        val policy = policies[id] ?: return false
        if (policy.client != client || policy.plan != plan || policy.expires <= now() || policy.remaining <= 0) return false
        policy.remaining--
        return true
    }

    @Synchronized fun status(client: String, id: String): JsonObject {
        val policy = policies[id]?.takeIf { it.client == client } ?: error("Policy unavailable for this session.")
        return buildJsonObject {
            put("policy_id", id); put("remaining_runs", policy.remaining)
            put("expires_at_monotonic_ms", policy.expires)
            put("active", policy.expires > now() && policy.remaining > 0)
        }
    }

    @Synchronized fun revoke(client: String, id: String): JsonObject {
        status(client, id)
        policies.remove(id)
        return buildJsonObject { put("revoked", true) }
    }

    @Synchronized fun clear() = policies.clear()
}
