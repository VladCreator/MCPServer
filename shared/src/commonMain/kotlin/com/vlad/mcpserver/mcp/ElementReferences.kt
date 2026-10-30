package com.vlad.mcpserver.mcp

internal data class ObservationWindow(
    val generation: Long,
    val windowId: Int,
    val packageName: String,
    val rotation: Int,
    val width: Int,
    val height: Int
)

/** Bounded, short-lived observations; references are never transferable between clients. */
internal class ElementReferences<T>(
    private val now: () -> Long,
    private val newId: () -> String,
    private val lifetimeMs: Long = 30_000
) {
    private data class Entry<T>(
        val owner: String, val observation: String, val window: ObservationWindow,
        val expires: Long, val value: T
    )
    private val entries = linkedMapOf<String, Entry<T>>()

    fun record(owner: String, window: ObservationWindow, values: List<T>): Pair<String, List<String>> {
        entries.entries.removeAll { it.value.owner == owner || it.value.expires <= now() }
        val observation = newId()
        val ids = values.take(1000).map { value ->
            val id = newId()
            entries[id] = Entry(owner, observation, window, now() + lifetimeMs, value)
            id
        }
        while (entries.size > 4000) entries.remove(entries.keys.first())
        return observation to ids
    }

    fun resolve(owner: String, reference: String, window: ObservationWindow): T? {
        val entry = entries[reference] ?: return null
        return entry.value.takeIf {
            entry.owner == owner && entry.expires > now() && entry.window == window
        }
    }

    fun clear() = entries.clear()
}
