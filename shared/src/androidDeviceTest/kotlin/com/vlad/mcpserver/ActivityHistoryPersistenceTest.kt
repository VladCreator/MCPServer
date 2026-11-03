package com.vlad.mcpserver

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityHistoryPersistenceTest {
    @Test
    fun keeps800ActivitiesAfterAutomaticPruningAndRestart() = runTest {
        val directory = Files.createTempDirectory("qa-activity-history-test").toFile()
        val path = directory.resolve("activity.db").absolutePath
        try {
            val database = AppDatabase(path)
            try {
                database.init()
                repeat(850) {
                    database.insertCommandHistory("client", "ui_find", "{}", "SUCCESS", null)
                }
                assertEquals(1000, database.getCommandHistories(1000).size)
                database.pruneHistory()
                assertEquals(1000, database.getCommandHistories(1000).size)
            } finally {
                database.close()
            }
            val reopened = AppDatabase(path)
            try {
                reopened.init()
                val history = reopened.getCommandHistories(1000)
                assertEquals(1000, history.size)
                assertEquals(850L, history.first().id)
                assertEquals(51L, history.last().id)
            } finally {
                reopened.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
