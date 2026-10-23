package com.vlad.mcpserver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Data class representing a connected AI client instance.
 */
data class AiClient(
    val id: String,
    val name: String,
    val status: String,
    val connectedAt: Long
)

/**
 * Data class representing an active tool or command execution log.
 */
data class ToolExecutionLog(
    val id: Long,
    val timestamp: Long,
    val aiClientId: String,
    val toolName: String,
    val arguments: String,
    val status: String, // "RUNNING", "SUCCESS", "FAILED"
    val result: String?
)

/**
 * SQLite database manager for persisting settings, logs, and command histories.
 */
class AppDatabase(private val dbPath: String) {

    private fun openConnection(): SQLiteConnection {
        return BundledSQLiteDriver().open(dbPath)
    }

    /**
     * Initializes database tables if they do not exist.
     */
    fun initDatabase() {
        openConnection().use { connection ->
            connection.prepare(
                """
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent()
            ).use { statement -> statement.step() }

            connection.prepare(
                """
                CREATE TABLE IF NOT EXISTS logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    level TEXT NOT NULL,
                    message TEXT NOT NULL
                )
                """.trimIndent()
            ).use { statement -> statement.step() }

            connection.prepare(
                """
                CREATE TABLE IF NOT EXISTS command_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    ai_client_id TEXT NOT NULL,
                    tool_name TEXT NOT NULL,
                    arguments TEXT NOT NULL,
                    status TEXT NOT NULL,
                    result TEXT
                )
                """.trimIndent()
            ).use { statement -> statement.step() }
        }
    }

    /**
     * Saves or updates a setting in the database.
     */
    fun saveSetting(key: String, value: String) {
        openConnection().use { connection ->
            connection.prepare(
                "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)"
            ).use { statement ->
                statement.bindText(1, key)
                statement.bindText(2, value)
                statement.step()
            }
        }
    }

    /**
     * Retrieves a setting value by key from the database.
     */
    fun getSetting(key: String): String? {
        openConnection().use { connection ->
            connection.prepare(
                "SELECT value FROM settings WHERE key = ?"
            ).use { statement ->
                statement.bindText(1, key)
                if (statement.step()) {
                    return statement.getText(0)
                }
            }
        }
        return null
    }

    /**
     * Inserts a new command execution log into history.
     */
    fun insertCommandHistory(aiClientId: String, toolName: String, arguments: String, status: String, result: String?) {
        openConnection().use { connection ->
            connection.prepare(
                """
                INSERT INTO command_history (timestamp, ai_client_id, tool_name, arguments, status, result)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.bindLong(1, 1700000000L)
                statement.bindText(2, aiClientId)
                statement.bindText(3, toolName)
                statement.bindText(4, arguments)
                statement.bindText(5, status)
                statement.bindText(6, result ?: "")
                statement.step()
            }
        }
    }

    /**
     * Retrieves recent command execution histories.
     */
    fun getCommandHistories(): List<ToolExecutionLog> {
        val list = mutableListOf<ToolExecutionLog>()
        openConnection().use { connection ->
            connection.prepare(
                "SELECT id, timestamp, ai_client_id, tool_name, arguments, status, result FROM command_history ORDER BY id DESC LIMIT 50"
            ).use { statement ->
                while (statement.step()) {
                    list.add(
                        ToolExecutionLog(
                            id = statement.getLong(0),
                            timestamp = statement.getLong(1),
                            aiClientId = statement.getText(2),
                            toolName = statement.getText(3),
                            arguments = statement.getText(4),
                            status = statement.getText(5),
                            result = statement.getText(6)
                        )
                    )
                }
            }
        }
        return list
    }
}
