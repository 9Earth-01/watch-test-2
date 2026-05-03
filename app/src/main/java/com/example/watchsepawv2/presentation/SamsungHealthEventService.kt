package com.example.watchsepawv2.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class SamsungHealthEventService : Service() {
    companion object {
        private const val TAG = "SamsungHealthEventService"
        private const val CHANNEL_ID = "samsung_health_event_channel"
        private const val NOTIF_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Samsung Health Event Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Samsung Health Event Service started")

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Samsung Health Event")
            .setContentText("Listening for Samsung Health fall events")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIF_ID, notification)

        // TODO: ลงทะเบียน Health Services passive listener ที่นี่
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Samsung Health Event Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for Samsung Health event listener"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
