package com.vlad.mcpserver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Data class representing a connected AI client instance.
 */
data class AiClient(
    val id: String,
    val name: String,
    val status: String,
    val connectedAt: Long,
    val remoteAddress: String? = null
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
    val status: String, // "SUCCESS", "FAILED", "WARNING"
    val result: String?
)

/**
 * SQLite database manager for settings, logs and command history.
 *
 * A single connection is opened and every operation is serialized with a
 * Mutex. SQLite writes come from network/event threads, so sharing one
 * connection avoids the "database is locked" errors of the previous
 * connection-per-call design.
 */
class AppDatabase(dbPath: String) {

    private val mutex = Mutex()
    private val connection: SQLiteConnection = BundledSQLiteDriver().open(dbPath)
    private var insertedSincePrune = 0

    suspend fun init() = mutex.withLock {
        connection.prepare("PRAGMA journal_mode=WAL").use { it.step() }
        connection.prepare("PRAGMA busy_timeout=5000").use { it.step() }
        connection.prepare("PRAGMA synchronous=NORMAL").use { it.step() }

        connection.prepare(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        ).use { it.step() }

        connection.prepare(
            """
            CREATE TABLE IF NOT EXISTS logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                level TEXT NOT NULL,
                message TEXT NOT NULL
            )
            """.trimIndent()
        ).use { it.step() }

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
        ).use { it.step() }

        connection.prepare(
            "CREATE INDEX IF NOT EXISTS idx_command_history_timestamp ON command_history(timestamp)"
        ).use { it.step() }

        pruneHistoryLocked()
    }

    suspend fun saveSetting(key: String, value: String) = mutex.withLock {
        connection.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)").use { statement ->
            statement.bindText(1, key)
            statement.bindText(2, value)
            statement.step()
        }
    }

    suspend fun getSetting(key: String): String? = mutex.withLock {
        connection.prepare("SELECT value FROM settings WHERE key = ?").use { statement ->
            statement.bindText(1, key)
            if (statement.step()) statement.getText(0) else null
        }
    }

    /**
     * Inserts a command log and returns its database id.
     */
    suspend fun insertCommandHistory(
        aiClientId: String,
        toolName: String,
        arguments: String,
        status: String,
        result: String?
    ): Long = mutex.withLock {
        val timestamp = nowMillis()
        connection.prepare(
            """
            INSERT INTO command_history (timestamp, ai_client_id, tool_name, arguments, status, result)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.bindLong(1, timestamp)
            statement.bindText(2, aiClientId)
            statement.bindText(3, toolName)
            statement.bindText(4, arguments)
            statement.bindText(5, status)
            if (result == null) statement.bindNull(6) else statement.bindText(6, result)
            statement.step()
        }

        val id = connection.prepare("SELECT last_insert_rowid()").use { statement ->
            if (statement.step()) statement.getLong(0) else 0L
        }

        insertedSincePrune++
        if (insertedSincePrune >= 25) {
            pruneHistoryLocked()
            insertedSincePrune = 0
        }
        id
    }

    suspend fun getCommandHistories(limit: Int = 100): List<ToolExecutionLog> = mutex.withLock {
        val list = mutableListOf<ToolExecutionLog>()
        connection.prepare(
            "SELECT id, timestamp, ai_client_id, tool_name, arguments, status, result " +
                "FROM command_history ORDER BY id DESC LIMIT ?"
        ).use { statement ->
            statement.bindLong(1, limit.toLong())
            while (statement.step()) {
                list.add(
                    ToolExecutionLog(
                        id = statement.getLong(0),
                        timestamp = statement.getLong(1),
                        aiClientId = statement.getText(2),
                        toolName = statement.getText(3),
                        arguments = statement.getText(4),
                        status = statement.getText(5),
                        result = if (statement.isNull(6)) null else statement.getText(6)
                    )
                )
            }
        }
        list
    }

    suspend fun pruneHistory(keep: Int = 500) = mutex.withLock {
        connection.prepare(
            "DELETE FROM command_history WHERE id NOT IN " +
                "(SELECT id FROM command_history ORDER BY id DESC LIMIT ?)"
        ).use { statement ->
            statement.bindLong(1, keep.toLong())
            statement.step()
        }
    }

    private fun pruneHistoryLocked(keep: Int = 500) {
        connection.prepare(
            "DELETE FROM command_history WHERE id NOT IN " +
                "(SELECT id FROM command_history ORDER BY id DESC LIMIT ?)"
        ).use { statement ->
            statement.bindLong(1, keep.toLong())
            statement.step()
        }
    }

    /**
     * Closes the underlying connection. The ViewModel calls this from
     * onCleared after cancelling its coroutine scope.
     */
    fun close() {
        runCatching { connection.close() }
    }
}
