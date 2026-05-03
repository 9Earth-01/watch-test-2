package com.example.watchsepawv2.presentation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.HealthEvent
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.guava.await

class SamsungHealthEventService : PassiveListenerService() {
    companion object {
        private const val TAG = "SamsungHealthEventService"
        private const val CHANNEL_ID = "samsung_health_event_channel"
        private const val FALL_ALERT_CHANNEL_ID = "FALL_ALERT_CHANNEL"
        private const val NOTIF_ID = 1002
        private const val FALL_NOTIF_ID = 1003
    }

    private lateinit var healthServicesClient: HealthServicesClient
    private lateinit var passiveMonitoringClient: PassiveMonitoringClient
    private var registered = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createFallAlertChannel()
        Log.i(TAG, "Samsung Health Event Service created")

        // Initialize Health Services
        healthServicesClient = HealthServices.getClient(this)
        passiveMonitoringClient = healthServicesClient.passiveMonitoringClient

        // Register for fall detection events
        registerForHealthEvents()
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

    private fun createFallAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vibratePattern = longArrayOf(0, 600, 250, 600, 250, 800)
            val channel = NotificationChannel(
                FALL_ALERT_CHANNEL_ID,
                "Fall Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a fall is detected"
                enableVibration(true)
                vibrationPattern = vibratePattern
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun registerForHealthEvents() {
        if (!hasActivityRecognitionPermission()) {
            Log.w(TAG, "No ACTIVITY_RECOGNITION permission, cannot register for health events")
            return
        }

        runBlocking {
            try {
                Log.i(TAG, "Registering listener for fall detection")
                val healthEventTypes = setOf(HealthEvent.Type.FALL_DETECTED)
                val passiveListenerConfig = PassiveListenerConfig.builder()
                    .setHealthEventTypes(healthEventTypes)
                    .build()

                passiveMonitoringClient.setPassiveListenerServiceAsync(
                    SamsungHealthEventService::class.java,
                    passiveListenerConfig
                ).await()
                registered = true
                Log.i(TAG, "Successfully registered for fall detection events")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register for health events: ${e.message}")
            }
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onHealthEventReceived(event: HealthEvent) {
        runBlocking {
            Log.i(TAG, "onHealthEventReceived received with type: ${event.type}")

            when (event.type) {
                HealthEvent.Type.FALL_DETECTED -> {
                    Log.i(TAG, "FALL DETECTED by Samsung Health Services!")
                    onSamsungFallDetected()
                }
                else -> {
                    Log.d(TAG, "Received unknown health event: ${event.type}")
                }
            }

            super.onHealthEventReceived(event)
        }
    }

    private fun onSamsungFallDetected() {
        Log.i(TAG, "Processing Samsung Health fall detection")
        showFallAlertFullScreen()
    }

    private fun showFallAlertFullScreen() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // แพตเทิร์นการสั่น: หน่วง 0ms → สั่น 600 → หยุด 250 → สั่น 600 → หยุด 250 → สั่น 800
        val vibratePattern = longArrayOf(0, 600, 250, 600, 250, 800)

        // ปลุกจอ (สั้นๆ) เผื่อจอดับ
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SamsungHealthEventService:FallWake"
                ).apply { acquire(3_000); release() }
            }
        } catch (_: Exception) {}

        // Full-screen intent → HelpActivity
        val fullScreenIntent = Intent(this, HelpActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action ปุ่ม
        val okPI = PendingIntent.getBroadcast(
            this, 10,
            Intent(this, FallActionReceiver::class.java).setAction(FallActionReceiver.ACTION_OK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notOkPI = PendingIntent.getBroadcast(
            this, 11,
            Intent(this, FallActionReceiver::class.java).setAction(FallActionReceiver.ACTION_NOT_OK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val okAction = NotificationCompat.Action.Builder(0, "โอเค", okPI).build()
        val notOkAction = NotificationCompat.Action.Builder(0, "ไม่โอเค", notOkPI).build()

        val builder = NotificationCompat.Builder(this, FALL_ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("พบการล้ม (Samsung)")
            .setContentText("แตะเพื่อยืนยันความปลอดภัย")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(okAction)
            .addAction(notOkAction)
            .setVibrate(vibratePattern)
            .extend(NotificationCompat.WearableExtender().addAction(okAction).addAction(notOkAction))

        nm.notify(FALL_NOTIF_ID, builder.build())

        // Fallback: ยิงสั่นผ่าน Vibrator โดยตรง
        try {
            val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(vibratePattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(vibratePattern, -1)
            }
        } catch (_: Exception) {}

        // สำรอง: พยายามเปิด Activity ตรงๆ อีกครั้ง
        try { startActivity(fullScreenIntent) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        runBlocking {
            try {
                passiveMonitoringClient.clearPassiveListenerServiceAsync().await()
                registered = false
                Log.i(TAG, "Unregistered from health events")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister: ${e.message}")
            }
        }
    }
}
