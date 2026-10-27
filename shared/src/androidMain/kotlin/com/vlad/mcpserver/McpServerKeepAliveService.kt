package com.vlad.mcpserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that gives the process foreground priority while the MCP
 * server is running. It does not own the server; the ViewModel owns the engine
 * and stops this service when the server stops.
 *
 * Its notification is a Live Update (promoted ongoing notification): on
 * Android 16+ it shows as a status bar chip with the latest activity.
 */
class McpServerKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        secure = intent?.getBooleanExtra(EXTRA_SECURE, false) ?: false
        running = true
        startForeground(NOTIFICATION_ID, buildNotification(this))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        lastActivity = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MCP server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the local MCP server running in the background."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private data class Activity(val toolName: String, val status: String, val timestamp: Long)

    companion object {
        const val EXTRA_PORT = "port"
        const val EXTRA_SECURE = "secure"
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 1001
        private const val CHIP_MAX_CHARS = 14

        // Same key as Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36.1); set
        // through extras so it also works on devices running API 36.0.
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        @Volatile private var running = false
        @Volatile private var port = 0
        @Volatile private var secure = false
        @Volatile private var lastActivity: Activity? = null

        /** Records the latest activity and refreshes the notification if the server is running. */
        fun updateActivity(context: Context, toolName: String, status: String, timestamp: Long) {
            lastActivity = Activity(toolName, status, timestamp)
            if (!running) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (!manager.areNotificationsEnabled()) return
            manager.notify(NOTIFICATION_ID, buildNotification(context))
        }

        private fun buildNotification(context: Context): Notification {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    context,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val endpoint = if (port > 0) {
                "${if (secure) "HTTPS" else "HTTP"} on port $port"
            } else {
                "Running on the local network"
            }
            val activity = lastActivity
            val failed = activity != null && activity.status !in setOf("SUCCESS", "INFO")
            val title = if (activity == null) {
                "MCP Server running"
            } else {
                "${activity.toolName}${if (failed) " · ${activity.status.lowercase()}" else ""}"
            }
            val text = if (activity == null) endpoint else "Last activity · $endpoint"
            val chip = activity?.toolName?.let {
                if (it.length > CHIP_MAX_CHARS) it.take(CHIP_MAX_CHARS - 1) + "…" else it
            } ?: "MCP live"

            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText("MCP Server")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(activity != null)
                .setWhen(activity?.timestamp ?: System.currentTimeMillis())
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShortCriticalText(chip)
                .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
                .build()
        }
    }
}
