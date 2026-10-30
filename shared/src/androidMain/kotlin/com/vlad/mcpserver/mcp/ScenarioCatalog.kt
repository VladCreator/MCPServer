package com.vlad.mcpserver.mcp

import kotlinx.serialization.json.*

/** Only repository-reviewed, APK-bundled definitions are discoverable; no client writes. */
internal class ScenarioCatalog(read: (String) -> String) {
    private data class Entry(val source: String, val route: YamlRoute, val digest: String)
    private val entries = listOf("named_project", "unicode_form", "tap_delay_back").map { file ->
        val source = read("tester-routes/$file.yaml")
        val route = YamlRouteParser.parse(source)
        Entry(source, route, RunStore.hash(source))
    }

    fun list(): JsonObject = buildJsonObject {
        put("schema_version", 1)
        put("routes", JsonArray(entries.map { descriptor(it) }))
    }

    fun get(arguments: JsonObject): JsonObject {
        val entry = select(arguments)
        return JsonObject(descriptor(entry) + ("yaml" to JsonPrimitive(entry.source)))
    }

    fun resolve(arguments: JsonObject): YamlRoute {
        val entry = select(arguments)
        require(arguments.string("digest") == entry.digest) { "Exact catalog digest is required; retrieve get_route before execution." }
        val parameters = arguments["parameters"] ?: JsonObject(emptyMap())
        require(parameters is JsonObject) { "parameters must be an object." }
        return YamlRouteParser.parse(entry.source, parameters).copy(catalogDigest = entry.digest)
    }

    private fun select(arguments: JsonObject): Entry {
        val id = arguments.string("route_id")
        val version = arguments.string("version")
        return entries.singleOrNull { it.route.id == id && it.route.version == version }
            ?: throw IllegalArgumentException("Catalog route unavailable; exact route_id and version required.")
    }

    private fun descriptor(entry: Entry) = buildJsonObject {
        put("route_id", entry.route.id); put("version", entry.route.version); put("digest", entry.digest)
        put("schema_version", entry.route.schemaVersion); put("app_id", entry.route.packageName)
        put("source", "apk_bundled_reviewed"); put("client_editable", false)
    }
}
