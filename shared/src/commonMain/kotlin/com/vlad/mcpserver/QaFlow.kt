package com.vlad.mcpserver

data class QaFlow(val id: Long, val clientId: String, val kind: String, val sourceId: String)

expect suspend fun loadQaFlows(): List<QaFlow>

fun flowLabel(id: Long?): String = id?.let { "Flow #$it" } ?: "Unlinked"
