package com.vlad.mcpserver;

import androidx.sqlite.SQLiteConnection;
import androidx.sqlite.SQLiteStatement;
import androidx.sqlite.driver.bundled.BundledSQLiteDriver;
import java.util.ArrayList;
import java.util.List;
import kotlin.Metadata;
import kotlin.Unit;
import kotlin.jdk7.AutoCloseableKt;
import kotlin.jvm.internal.Intrinsics;

/* JADX INFO: compiled from: Database.kt */
/* JADX INFO: loaded from: /private/tmp/overlay-classes2.dex */
@Metadata(d1 = {"\u00000\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u000b\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\b\u0007\u0018\u00002\u00020\u0001B\u000f\u0012\u0006\u0010\u0002\u001a\u00020\u0003¢\u0006\u0004\b\u0004\u0010\u0005J\b\u0010\u0006\u001a\u00020\u0007H\u0002J\u0006\u0010\b\u001a\u00020\tJ\u0016\u0010\n\u001a\u00020\t2\u0006\u0010\u000b\u001a\u00020\u00032\u0006\u0010\f\u001a\u00020\u0003J\u0010\u0010\r\u001a\u0004\u0018\u00010\u00032\u0006\u0010\u000b\u001a\u00020\u0003J0\u0010\u000e\u001a\u00020\t2\u0006\u0010\u000f\u001a\u00020\u00032\u0006\u0010\u0010\u001a\u00020\u00032\u0006\u0010\u0011\u001a\u00020\u00032\u0006\u0010\u0012\u001a\u00020\u00032\b\u0010\u0013\u001a\u0004\u0018\u00010\u0003J\f\u0010\u0014\u001a\b\u0012\u0004\u0012\u00020\u00160\u0015R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004¢\u0006\u0002\n\u0000Ê\u0001\f\b\u0018\u0012\b\b\u0019\u0012\u0004\b\u0003\u0010\u0002¨\u0006\u0017"}, d2 = {"Lcom/vlad/mcpserver/AppDatabase;", "", "dbPath", "", "<init>", "(Ljava/lang/String;)V", "openConnection", "Landroidx/sqlite/SQLiteConnection;", "initDatabase", "", "saveSetting", "key", "value", "getSetting", "insertCommandHistory", "aiClientId", "toolName", "arguments", "status", "result", "getCommandHistories", "", "Lcom/vlad/mcpserver/ToolExecutionLog;", "MCPServer:shared", "Landroidx/compose/runtime/internal/StabilityInferred;", "parameters"}, k = 1, mv = {2, 4, 0}, xi = 48)
public final class AppDatabase {
    public static final int $stable = 0;
    private final String dbPath;

    public AppDatabase(String dbPath) {
        Intrinsics.checkNotNullParameter(dbPath, "dbPath");
        this.dbPath = dbPath;
    }

    private final SQLiteConnection openConnection() {
        return new BundledSQLiteDriver().open(this.dbPath);
    }

    public final void initDatabase() {
        SQLiteConnection sQLiteConnection = (AutoCloseable) openConnection();
        try {
            SQLiteConnection connection = sQLiteConnection;
            SQLiteStatement sQLiteStatement = (AutoCloseable) connection.prepare("CREATE TABLE IF NOT EXISTS settings (\n    key TEXT PRIMARY KEY,\n    value TEXT NOT NULL\n)");
            try {
                SQLiteStatement statement = sQLiteStatement;
                statement.step();
                AutoCloseableKt.closeFinally(sQLiteStatement, (Throwable) null);
                SQLiteStatement sQLiteStatement2 = (AutoCloseable) connection.prepare("CREATE TABLE IF NOT EXISTS logs (\n    id INTEGER PRIMARY KEY AUTOINCREMENT,\n    timestamp INTEGER NOT NULL,\n    level TEXT NOT NULL,\n    message TEXT NOT NULL\n)");
                try {
                    SQLiteStatement statement2 = sQLiteStatement2;
                    statement2.step();
                    AutoCloseableKt.closeFinally(sQLiteStatement2, (Throwable) null);
                    SQLiteStatement sQLiteStatement3 = (AutoCloseable) connection.prepare("CREATE TABLE IF NOT EXISTS command_history (\n    id INTEGER PRIMARY KEY AUTOINCREMENT,\n    timestamp INTEGER NOT NULL,\n    ai_client_id TEXT NOT NULL,\n    tool_name TEXT NOT NULL,\n    arguments TEXT NOT NULL,\n    status TEXT NOT NULL,\n    result TEXT\n)");
                    try {
                        SQLiteStatement statement3 = sQLiteStatement3;
                        statement3.step();
                        AutoCloseableKt.closeFinally(sQLiteStatement3, (Throwable) null);
                        AutoCloseableKt.closeFinally(sQLiteConnection, (Throwable) null);
                    } catch (Throwable th) {
                        try {
                            throw th;
                        } catch (Throwable th2) {
                            AutoCloseableKt.closeFinally(sQLiteStatement3, th);
                            throw th2;
                        }
                    }
                } catch (Throwable th3) {
                    try {
                        throw th3;
                    } catch (Throwable th4) {
                        AutoCloseableKt.closeFinally(sQLiteStatement2, th3);
                        throw th4;
                    }
                }
            } catch (Throwable th5) {
                try {
                    throw th5;
                } catch (Throwable th6) {
                    AutoCloseableKt.closeFinally(sQLiteStatement, th5);
                    throw th6;
                }
            }
        } catch (Throwable th7) {
            try {
                throw th7;
            } catch (Throwable th8) {
                AutoCloseableKt.closeFinally(sQLiteConnection, th7);
                throw th8;
            }
        }
    }

    public final void saveSetting(String key, String value) {
        Intrinsics.checkNotNullParameter(key, "key");
        Intrinsics.checkNotNullParameter(value, "value");
        SQLiteConnection sQLiteConnection = (AutoCloseable) openConnection();
        try {
            SQLiteConnection connection = sQLiteConnection;
            SQLiteStatement sQLiteStatement = (AutoCloseable) connection.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)");
            try {
                SQLiteStatement statement = sQLiteStatement;
                statement.bindText(1, key);
                statement.bindText(2, value);
                statement.step();
                AutoCloseableKt.closeFinally(sQLiteStatement, (Throwable) null);
                AutoCloseableKt.closeFinally(sQLiteConnection, (Throwable) null);
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    AutoCloseableKt.closeFinally(sQLiteStatement, th);
                    throw th2;
                }
            }
        } catch (Throwable th3) {
            try {
                throw th3;
            } catch (Throwable th4) {
                AutoCloseableKt.closeFinally(sQLiteConnection, th3);
                throw th4;
            }
        }
    }

    public final String getSetting(String key) {
        Intrinsics.checkNotNullParameter(key, "key");
        SQLiteConnection sQLiteConnection = (AutoCloseable) openConnection();
        try {
            SQLiteConnection connection = sQLiteConnection;
            SQLiteStatement sQLiteStatement = (AutoCloseable) connection.prepare("SELECT value FROM settings WHERE key = ?");
            try {
                SQLiteStatement statement = sQLiteStatement;
                statement.bindText(1, key);
                if (statement.step()) {
                    String text = statement.getText(0);
                    AutoCloseableKt.closeFinally(sQLiteStatement, (Throwable) null);
                    AutoCloseableKt.closeFinally(sQLiteConnection, (Throwable) null);
                    return text;
                }
                Unit unit = Unit.INSTANCE;
                AutoCloseableKt.closeFinally(sQLiteStatement, (Throwable) null);
                Unit unit2 = Unit.INSTANCE;
                AutoCloseableKt.closeFinally(sQLiteConnection, (Throwable) null);
                return null;
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    AutoCloseableKt.closeFinally(sQLiteStatement, th);
                    throw th2;
                }
            }
        } catch (Throwable th3) {
            try {
                throw th3;
            } catch (Throwable th4) {
                AutoCloseableKt.closeFinally(sQLiteConnection, th3);
                throw th4;
            }
        }
    }

    public final void insertCommandHistory(String aiClientId, String toolName, String arguments, String status, String result) {
        Intrinsics.checkNotNullParameter(aiClientId, "aiClientId");
        Intrinsics.checkNotNullParameter(toolName, "toolName");
        Intrinsics.checkNotNullParameter(arguments, "arguments");
        Intrinsics.checkNotNullParameter(status, "status");
        SQLiteConnection sQLiteConnection = (AutoCloseable) openConnection();
        try {
            SQLiteConnection connection = sQLiteConnection;
            SQLiteStatement sQLiteStatement = (AutoCloseable) connection.prepare("INSERT INTO command_history (timestamp, ai_client_id, tool_name, arguments, status, result)\nVALUES (?, ?, ?, ?, ?, ?)");
            try {
                SQLiteStatement statement = sQLiteStatement;
                statement.bindLong(1, 1700000000L);
                statement.bindText(2, aiClientId);
                statement.bindText(3, toolName);
                statement.bindText(4, arguments);
                statement.bindText(5, status);
                statement.bindText(6, result == null ? "" : result);
                statement.step();
                AutoCloseableKt.closeFinally(sQLiteStatement, (Throwable) null);
                AutoCloseableKt.closeFinally(sQLiteConnection, (Throwable) null);
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    AutoCloseableKt.closeFinally(sQLiteStatement, th);
                    throw th2;
                }
            }
        } catch (Throwable th3) {
            try {
                throw th3;
            } catch (Throwable th4) {
                AutoCloseableKt.closeFinally(sQLiteConnection, th3);
                throw th4;
            }
        }
    }

    public final List<ToolExecutionLog> getCommandHistories() {
        List list = new ArrayList();
        SQLiteConnection sQLiteConnection = (AutoCloseable) openConnection();
        try {
            SQLiteConnection connection = sQLiteConnection;
            SQLiteStatement sQLiteStatement = (AutoCloseable) connection.prepare("SELECT id, timestamp, ai_client_id, tool_name, arguments, status, result FROM command_history ORDER BY id DESC LIMIT 50");
            try {
                SQLiteStatement statement = sQLiteStatement;
                while (statement.step()) {
                    list.add(new ToolExecutionLog(statement.getLong(0), statement.getLong(1), statement.getText(2), statement.getText(3), statement.getText(4), statement.getText(5), statement.getText(6)));
                }
                Unit unit = Unit.INSTANCE;
                AutoCloseableKt.closeFinally(sQLiteStatement, (Throwable) null);
                Unit unit2 = Unit.INSTANCE;
                AutoCloseableKt.closeFinally(sQLiteConnection, (Throwable) null);
                return list;
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    AutoCloseableKt.closeFinally(sQLiteStatement, th);
                    throw th2;
                }
            }
        } catch (Throwable th3) {
            try {
                throw th3;
            } catch (Throwable th4) {
                AutoCloseableKt.closeFinally(sQLiteConnection, th3);
                throw th4;
            }
        }
    }
}
