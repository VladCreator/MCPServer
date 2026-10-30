package com.vlad.mcpserver.mcp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vlad.mcpserver.QaFlow
import java.io.File

/** Local correlation metadata only. Client IDs never enter exported reports. */
internal class QaFlowStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "qa-flows.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE flows (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, source TEXT NOT NULL, client TEXT NOT NULL, UNIQUE(kind,source))")
        db.execSQL("CREATE TABLE evidence (path TEXT PRIMARY KEY, flow INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unsupported QA flow store upgrade.")

    @Synchronized fun ensure(kind: String, source: String, clientId: String): Long {
        val db = writableDatabase
        db.insertWithOnConflict("flows", null, ContentValues().apply {
            put("kind", kind); put("source", source); put("client", clientId)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return db.rawQuery("SELECT id FROM flows WHERE kind=? AND source=?", arrayOf(kind, source)).use {
            check(it.moveToFirst()) { "Unable to persist QA flow." }
            it.getLong(0)
        }
    }

    @Synchronized fun bind(path: String, flowId: Long?) {
        if (flowId == null) {
            writableDatabase.delete("evidence", "path=?", arrayOf(File(path).canonicalPath))
            return
        }
        check(writableDatabase.insertWithOnConflict("evidence", null, ContentValues().apply {
            put("path", File(path).canonicalPath); put("flow", flowId)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Unable to link QA evidence." }
    }

    @Synchronized fun fileLinks(): Map<String, Long> =
        readableDatabase.rawQuery("SELECT path,flow FROM evidence", null).use {
            buildMap { while (it.moveToNext()) put(it.getString(0), it.getLong(1)) }
        }

    @Synchronized fun list(): List<QaFlow> =
        readableDatabase.rawQuery("SELECT id,client,kind,source FROM flows ORDER BY id DESC", null).use {
            buildList { while (it.moveToNext()) add(QaFlow(it.getLong(0), it.getString(1), it.getString(2), it.getString(3))) }
        }

    companion object {
        private var instance: QaFlowStore? = null
        @Synchronized fun get(context: Context): QaFlowStore =
            instance ?: QaFlowStore(context).also { instance = it }
    }
}
