package com.foxwelai.driverforcanon.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class PrintFoxServerService : Service() {
    companion object {
        private const val CHANNEL_ID = "printfox_server_channel"
        private const val NOTIF_ID = 912301

        fun startServer(context: Context) {
            val intent = Intent(context, PrintFoxServerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                LocalHttpServer.get(context).start()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification()
        try {
            startForeground(NOTIF_ID, notification)
        } catch (_: Exception) {}
        LocalHttpServer.get(applicationContext).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LocalHttpServer.get(applicationContext).start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FatFox Driver Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps FatFox local print server active in background"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("FatFox Print Server Active")
            .setContentText("Listening on http://127.0.0.1:9123")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }
}
