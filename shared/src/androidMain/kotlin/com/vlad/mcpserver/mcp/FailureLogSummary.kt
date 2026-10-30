package com.vlad.mcpserver.mcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit

/** Counts bounded, package-filtered bytes; no raw log content is stored or returned. */
internal object FailureLogSummary {
    suspend fun collect(context: Context, pkg: String, buffer: String): JsonObject {
        if (pkg != context.packageName &&
            context.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            return buildJsonObject { put("status", "denied"); put("reason", "READ_LOGS_required") }
        }
        return try {
            withContext(Dispatchers.IO) {
                val pidProcess = ProcessBuilder("pidof", pkg).start()
                val pids = try {
                    if (!pidProcess.waitFor(500, TimeUnit.MILLISECONDS)) return@withContext buildJsonObject {
                        put("status", "timed_out")
                    }
                    pidProcess.inputStream.bufferedReader().readText().trim().split(Regex("\\s+"))
                        .filter { it.matches(Regex("[0-9]{1,10}")) }.take(8)
                } finally { pidProcess.destroyForcibly() }
                if (pids.isEmpty()) return@withContext buildJsonObject {
                    put("status", "unsupported")
                    put("reason", "no_live_pid; historical_pid_attribution_not_verified")
                }
                coroutineScope {
                    val process = ProcessBuilder("logcat", "-d", "-t", "100", "-b", buffer,
                        "--pid=${pids.joinToString(",")}", "-v", "threadtime").redirectErrorStream(false).start()
                    try {
                        val count = async(Dispatchers.IO) {
                            val bytes = ByteArray(4096)
                            var total = 0
                            var lines = 0
                            process.inputStream.use { input ->
                                while (total < 65536) {
                                    val read = input.read(bytes, 0, minOf(bytes.size, 65536 - total))
                                    if (read < 0) break
                                    lines += (0 until read).count { bytes[it] == 10.toByte() }
                                    total += read
                                }
                            }
                            total to lines
                        }
                        if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                            process.destroyForcibly()
                            count.await()
                            return@coroutineScope buildJsonObject { put("status", "timed_out") }
                        }
                        val (bytes, lines) = count.await()
                        buildJsonObject {
                            put("status", if (process.exitValue() == 0) "redacted" else "failed")
                            put("bytes_observed", bytes); put("lines_observed", lines)
                            put("truncated", bytes >= 65536)
                            put("message_policy", "all_log_content_omitted")
                            put("filter", "live_target_pids")
                        }
                    } finally { process.destroyForcibly() }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: java.io.IOException) {
            android.util.Log.w("TesterEvidence", "Unable to collect bounded log summary", error)
            buildJsonObject { put("status", "failed"); put("reason", "log_reader_unavailable") }
        }
    }
}
