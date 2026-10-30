package com.vlad.mcpserver

import com.vlad.mcpserver.mcp.QaFlowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun loadQaFlows(): List<QaFlow> = withContext(Dispatchers.IO) {
    QaFlowStore.get(appContext).list()
}
