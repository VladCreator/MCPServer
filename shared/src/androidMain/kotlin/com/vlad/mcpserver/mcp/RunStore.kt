package com.vlad.mcpserver.mcp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

internal class RunStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "qa-runs.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE runs (id TEXT PRIMARY KEY, owner TEXT NOT NULL, submission TEXT NOT NULL, digest TEXT NOT NULL, updated INTEGER NOT NULL, body TEXT NOT NULL, UNIQUE(owner, submission))")
        db.execSQL("CREATE TABLE artifacts (id TEXT PRIMARY KEY, run_id TEXT NOT NULL, mime TEXT NOT NULL, body BLOB NOT NULL, created INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unsupported QA run store upgrade.")

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Only this process-wide store opens the database; no step is resumed.
        rows(db).filter { it.string("status") in ACTIVE }.forEach { run ->
            write(db, RunInterruption.apply(run, "interrupted", "process_restart"))
        }
    }

    @Synchronized fun admit(
        key: String, submission: String, digest: String, steps: List<RouteStep>, privacySensitive: Boolean, packageName: String
    ): Pair<JsonObject, Boolean> {
        require(KEY.matches(key)) { "run_key must be 32-128 URL-safe characters generated with cryptographic randomness." }
        require(SUBMISSION.matches(submission)) { "submission_id must be 1-80 letters, digits, dot, underscore or hyphen." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val owner = hash(key)
            val existing = db.rawQuery("SELECT body FROM runs WHERE owner=? AND submission=?", arrayOf(owner, hash(submission))).use {
                if (it.moveToFirst()) Json.parseToJsonElement(it.getString(0)).jsonObject else null
            }
            if (existing != null) {
                require(existing.string("plan_digest") == digest) { "submission_id already belongs to a different immutable plan." }
                db.setTransactionSuccessful()
                return existing to false
            }
            prune(db)
            val all = rows(db)
            check(all.count { it.string("status") in ACTIVE } < 8) { "Eight runs are already pending or active; wait or cancel." }
            check(all.size < 64) { "Run retention limit reached; wait for retention expiry." }
            val id = UUID.randomUUID().toString()
            val body = buildJsonObject {
                put("run_id", id); put("status", "awaiting_approval")
                put("plan_digest", digest); put("created_at_ms", System.currentTimeMillis())
                put("disconnect_policy", "continue_until_terminal")
                put("content_redacted", true); put("pending_cleanup", false)
                put("privacy_sensitive", privacySensitive)
                put("target_package", packageName)
                put("steps", JsonArray(steps.map { buildJsonObject {
                    put("id", it.id); put("tool", it.tool); put("status", "pending")
                    put("arguments", "[omitted]")
                } }))
            }
            db.insertOrThrow("runs", null, ContentValues().apply {
                put("id", id); put("owner", owner); put("submission", hash(submission))
                put("digest", digest); put("updated", System.currentTimeMillis()); put("body", body.toString())
            })
            db.setTransactionSuccessful()
            return body to true
        } finally { db.endTransaction() }
    }

    @Synchronized fun authorized(id: String, key: String): JsonObject? =
        readableDatabase.rawQuery("SELECT body FROM runs WHERE id=? AND owner=?", arrayOf(id, hash(key))).use {
            if (it.moveToFirst()) Json.parseToJsonElement(it.getString(0)).jsonObject else null
        }

    @Synchronized fun update(id: String, change: (JsonObject) -> JsonObject): JsonObject {
        val db = writableDatabase
        val run = db.rawQuery("SELECT body FROM runs WHERE id=?", arrayOf(id)).use {
            check(it.moveToFirst()) { "Run was removed." }
            Json.parseToJsonElement(it.getString(0)).jsonObject
        }
        return change(run).also { write(db, it) }
    }

    @Synchronized fun artifact(runId: String, mime: String, bytes: ByteArray): String {
        require(bytes.size <= 2 * 1024 * 1024) { "Artifact exceeds the 2 MiB limit." }
        val db = writableDatabase
        val (total, count) = db.rawQuery("SELECT COALESCE(SUM(length(body)),0),COUNT(*) FROM artifacts", null).use {
            it.moveToFirst(); it.getLong(0) to it.getInt(1)
        }
        check(count < 256) { "Artifact retention count limit of 256 reached." }
        check(total + bytes.size <= 20 * 1024 * 1024) { "Artifact retention quota of 20 MiB reached." }
        val id = UUID.randomUUID().toString()
        db.insertOrThrow("artifacts", null, ContentValues().apply {
            put("id", id); put("run_id", runId); put("mime", mime)
            put("body", bytes); put("created", System.currentTimeMillis())
        })
        return id
    }

    @Synchronized fun readArtifact(runId: String, id: String): Pair<String, ByteArray>? =
        readableDatabase.rawQuery("SELECT mime,body FROM artifacts WHERE id=? AND run_id=?", arrayOf(id, runId)).use {
            if (it.moveToFirst()) it.getString(0) to it.getBlob(1) else null
        }

    private fun rows(db: SQLiteDatabase): List<JsonObject> =
        db.rawQuery("SELECT body FROM runs", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Json.parseToJsonElement(cursor.getString(0)).jsonObject) }
        }

    private fun write(db: SQLiteDatabase, run: Map<String, JsonElement>) {
        val body = JsonObject(run)
        check(db.update("runs", ContentValues().apply {
            put("body", body.toString()); put("updated", System.currentTimeMillis())
        }, "id=?", arrayOf(body.string("run_id"))) == 1) { "Unable to persist run transition." }
    }

    private fun prune(db: SQLiteDatabase) {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        db.rawQuery("SELECT id,body FROM runs WHERE updated<?", arrayOf(cutoff.toString())).use { cursor ->
            val ids = buildList {
                while (cursor.moveToNext()) if (Json.parseToJsonElement(cursor.getString(1)).jsonObject.string("status") !in ACTIVE)
                    add(cursor.getString(0))
            }
            ids.forEach {
                db.delete("artifacts", "run_id=?", arrayOf(it))
                db.delete("runs", "id=?", arrayOf(it))
            }
        }
    }

    companion object {
        private var instance: RunStore? = null
        @Synchronized fun get(context: Context): RunStore =
            instance ?: RunStore(context).also { store -> store.writableDatabase; instance = store }
        val ACTIVE = setOf("awaiting_approval", "queued", "running", "cancelling")
        private val KEY = Regex("[A-Za-z0-9_-]{32,128}")
        private val SUBMISSION = Regex("[A-Za-z0-9_.-]{1,80}")
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun planDigest(key: String, plan: String): String = javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA256"))
            doFinal(plan.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
