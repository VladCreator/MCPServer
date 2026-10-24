package com.vlad.mcpserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that gives the process foreground priority while the MCP
 * server is running. It does not own the server; the ViewModel owns the engine
 * and stops this service when the server stops.
 */
class McpServerKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        val secure = intent?.getBooleanExtra(EXTRA_SECURE, false) ?: false
        startForeground(NOTIFICATION_ID, buildNotification(port, secure))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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

    private fun buildNotification(port: Int, secure: Boolean): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
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

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MCP Server running")
            .setContentText(endpoint)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val EXTRA_PORT = "port"
        const val EXTRA_SECURE = "secure"
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 1001
    }
}
