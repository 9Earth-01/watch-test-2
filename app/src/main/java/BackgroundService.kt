package com.example.watchsepawv2.presentation

import android.Manifest
import android.R
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class BackgroundService : Service(), ConnectionObserver {

    // --------- Service infra ---------
    private val handler = Handler(Looper.getMainLooper())
    private val CHANNEL_ID = "GPS_Tracking_Channel"
    private val FALL_ALERT_CHANNEL_ID = "FALL_ALERT_CHANNEL"
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var connectionManager: ConnectionManager
    private lateinit var skinTemperatureListener: SkinTemperatureListener
    private lateinit var trackerDataSubject: TrackerDataSubject

    // flags สำหรับควบคุมการเริ่ม tracker
    private var isHealthConnected = false
    private var isSkinTempAvailable = false
    private var isSkinTempStarted = false

    // --------- GPS continuous updates (ย้ายมาไว้ที่นี่) ---------
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val LOCATION_INTERVAL_MS = 10_000L

    // --------- Sensors / fall detection ---------
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var isCustomFallListening = false
    private var isFallDetected = false

    private lateinit var heartRateListener: HeartRateListener

    private var GyroX = 0f
    private var GyroY = 0f
    private var GyroZ = 0f

    private var svmA = 0f
    private var svmG = 0f
    private var pitchDeg = 0f
    private var rollDeg  = 0f
    private var yawDeg   = 0f

    private val G = 9.80665f
    private val IMPACT_A_THR = 47.40f
    private val IMPACT_G_THR = 9.20f
    private val IMPACT_PAIRING_MS = 300L

    private val EULER_DELTA_THR_DEG = 71.19f
    private val POSTURE_WINDOW_MS = 1500L

    private val COOLDOWN_MS = 10_000L

    private enum class State { IDLE, IMPACT, POSTURE, COOLDOWN }
    private var state = State.IDLE
    private var tImpact = 0L
    private var tStateEntered = 0L

    private var pitchAtImpact = 0f
    private var rollAtImpact  = 0f
    private var yawAtImpact   = 0f
    private var aPeak = 0f
    private var gPeak = 0f
    private var dPitchMax = 0f
    private var dRollMax  = 0f
    private var dYawMax   = 0f

    private val TAG_RAW = "FALL_RAW"
    private val TAG_STATE = "FALL_STATE"
    private val TAG_EVT = "FALL_EVT"
    private val LOG_RAW_EVERY_MS = 250L
    private var lastRawLog = 0L

    private var isTrackingLocation = false

    companion object {
        const val ACTION_START_TRACKING = "ACTION_START_TRACKING" // ชื่อคำสั่งเปิด
        const val ACTION_STOP_TRACKING = "ACTION_STOP_TRACKING"   // ชื่อคำสั่งปิด
        const val ACTION_SET_FALL_MODE = "ACTION_SET_FALL_MODE"
        var isEmergencyMode = false
        var isServerAllowTrackingGps = false // อนุญาติให้มีการตรวจสอบว่าผู้ใช้จะเแิดหรอืปิดระบบติดตามแบบ realtime
    }

    override fun onCreate() {
        super.onCreate()

        // Foreground + wakelock
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "GPS Tracking"
            val descriptionText = "Tracking location and temperature in the background"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BackgroundService::WakeLock"
            )
            wakeLock.acquire()
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS & Temp Tracking")
            .setContentText("Tracking in background")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)

        // --- GPS: init + start continuous updates ---
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
//        startLocationUpdates()  // <— เริ่มที่นี่

        // Health connections
        connectionManager = ConnectionManager(this)
        skinTemperatureListener = SkinTemperatureListener(this)
        trackerDataSubject = TrackerDataSubject()
        skinTemperatureListener.setTrackerDataSubject(trackerDataSubject)

        connectionManager.connect(applicationContext)

        // ---- Sensors ----
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        applyFallModeFromPreferences()

        // Heart rate
        heartRateListener = HeartRateListener(this)
        heartRateListener.startListening()
    }

    private fun applyFallModeFromPreferences() {
        val fallMode = MyPreferenceData(this).getFallMode()
        if (fallMode == MyPreferenceData.FALL_MODE_CUSTOM) {
            startCustomFallDetection()
        } else {
            stopCustomFallDetection()
            Log.i("BackgroundService", "Samsung Health fall mode selected, custom fall sensor listeners disabled")
        }
    }

    private fun startCustomFallDetection() {
        if (isCustomFallListening) {
            Log.d("BackgroundService", "Custom fall detection already running")
            return
        }

        resetFallDetectionState()

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let { acc ->
            sensorManager.registerListener(
                fallSensorListener,
                acc,
                SensorManager.SENSOR_DELAY_GAME,
                0
            )
        } ?: Log.e("Sensor", "Accelerometer not available on this device")

        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyroSensor != null) {
            sensorManager.registerListener(
                gyroListener,
                gyroSensor,
                SensorManager.SENSOR_DELAY_GAME,
                0
            )
        } else {
            Log.e("Sensor", "Gyroscope not available on this device")
        }

        isCustomFallListening = true
        Log.i("BackgroundService", "Custom fall detection started")
    }

    private fun stopCustomFallDetection() {
        if (!::sensorManager.isInitialized || !isCustomFallListening) return

        stopCustomFallDetection()
        isCustomFallListening = false
        resetFallDetectionState()
        Log.i("BackgroundService", "Custom fall detection stopped")
    }

    private fun resetFallDetectionState() {
        isFallDetected = false
        state = State.IDLE
        tImpact = 0L
        tStateEntered = 0L
        aPeak = 0f
        gPeak = 0f
        dPitchMax = 0f
        dRollMax = 0f
        dYawMax = 0f
    }

    // =========== GPS continuous update helpers ===========
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("GPS", "no location permission")
            return
        }

//        if (isTrackingLocation) {
//            Log.d("GPS", "Tracking is already running. Skipping start.")
//            return
//        }

        val req = LocationRequest.create()
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
            .setInterval(LOCATION_INTERVAL_MS)
            .setFastestInterval(5_000)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                // อัปเดตตำแหน่งให้ตัวแปรกลาง
                standbymain.curLat = loc.latitude
                standbymain.curLong = loc.longitude

                // คำนวณระยะ (ใช้ค่าใน preferences ของผู้ใช้)
                val pref = MyPreferenceData(this@BackgroundService)
                standbymain.safeZoneLat = pref.getLat().toDouble()
                standbymain.safeZoneLong = pref.getLong().toDouble()

                if (standbymain.curLat != 0.0 && standbymain.curLong != 0.0) {
                    standbymain.distance = (
                            CalculateDistance().getDistanceFromLatLonInKm(
                                standbymain.curLat, standbymain.curLong,
                                standbymain.safeZoneLat, standbymain.safeZoneLong
                            ) * 1000
                            ).roundToInt()
                    standbymain.distanceKM = "%,d เมตร".format(standbymain.distance)
                }

                Log.d("GPS", "Update lat=${loc.latitude}, lon=${loc.longitude}")
            }
        }

        fusedLocationClient.requestLocationUpdates(
            req,
            locationCallback,
            Looper.getMainLooper()
        )
        Log.d("GPS", "✅ startLocationUpdates()")
    }

    private fun stopLocationUpdates() {
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.d("GPS", "🛑 stopLocationUpdates()")
        }
    }
    // =========== END GPS helpers ===========

    /** พยายามเริ่ม Skin Temperature เฉพาะเมื่อเชื่อมต่อแล้วและ sensor available เท่านั้น */
    private fun tryStartSkinTemperature() {
        if (isSkinTempStarted) return
        if (!isHealthConnected || !isSkinTempAvailable) {
            Log.w("SkinTemp", "skip start: connected=$isHealthConnected, available=$isSkinTempAvailable")
            return
        }
        try {
            connectionManager.initSkinTemperature(skinTemperatureListener)
            skinTemperatureListener.startTracker()
            isSkinTempStarted = true
            Log.i("SkinTemp", "Tracker started")
        } catch (e: Exception) {
            Log.e("SkinTemp", "start failed: ${e.message}")
        }
    }

    // ---------- Accelerometer ----------
    private val fallSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                val ax = it.values[0]
                val ay = it.values[1]
                val az = it.values[2]

                svmA = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

                val denomPitch = sqrt((ay * ay + az * az).toDouble()).toFloat()
                val denomRoll  = sqrt((ax * ax + az * az).toDouble()).toFloat()
                val denomYaw   = sqrt((ax * ax + ay * ay).toDouble()).toFloat()

                val pRatio = if (denomPitch == 0f) 0f else ax / denomPitch
                val rRatio = if (denomRoll  == 0f) 0f else ay / denomRoll
                val yRatio = if (denomYaw   == 0f) 0f else az / denomYaw

                pitchDeg = Math.toDegrees(atan(pRatio.toDouble())).toFloat()
                rollDeg  = Math.toDegrees(atan(rRatio.toDouble())).toFloat()
                yawDeg   = Math.toDegrees(atan(yRatio.toDouble())).toFloat()

                val preferenceData = MyPreferenceData(this@BackgroundService)
                preferenceData.setXAxis(ax)
                preferenceData.setYAxis(ay)
                preferenceData.setZAxis(az)
                preferenceData.setGyroX(GyroX)
                preferenceData.setGyroY(GyroY)
                preferenceData.setGyroZ(GyroZ)

                val now = System.currentTimeMillis()
                handleLogic(now)
                logRaw(now)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ---------- Gyroscope ----------
    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                GyroX = it.values[0]
                GyroY = it.values[1]
                GyroZ = it.values[2]
                val gyroMagnitude = sqrt((GyroX * GyroX + GyroY * GyroY + GyroZ * GyroZ).toDouble()).toFloat()
                svmG = gyroMagnitude

                val now = System.currentTimeMillis()
                handleLogic(now)
                logRaw(now)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ---------- Core logic: IDLE → IMPACT → POSTURE → COOLDOWN ----------
    private fun handleLogic(now: Long) {
        when (state) {
            State.IDLE -> {
                if (svmA > IMPACT_A_THR && svmG > IMPACT_G_THR && !isFallDetected) {
                    tImpact = now
                    pitchAtImpact = pitchDeg
                    rollAtImpact  = rollDeg
                    yawAtImpact   = yawDeg
                    aPeak = svmA; gPeak = svmG
                    dPitchMax = 0f; dRollMax = 0f; dYawMax = 0f
                    transition(State.IMPACT)
                    Log.d(TAG_EVT, "IMPACT start: A=${"%.2f".format(svmA)} m/s^2, G=${"%.2f".format(svmG)} rad/s, " +
                            "pitch=${"%.1f".format(pitchDeg)}, roll=${"%.1f".format(rollDeg)}, yaw=${"%.1f".format(yawDeg)}")
                }
            }
            State.IMPACT -> {
                aPeak = max(aPeak, svmA)
                gPeak = max(gPeak, svmG)
                if (now - tImpact > IMPACT_PAIRING_MS) {
                    transition(State.POSTURE)
                }
            }
            State.POSTURE -> {
                val dPitch = abs(pitchDeg - pitchAtImpact)
                val dRoll  = abs(rollDeg  - rollAtImpact)
                val dYaw   = abs(yawDeg   - yawAtImpact)
                dPitchMax = max(dPitchMax, dPitch)
                dRollMax  = max(dRollMax,  dRoll)
                dYawMax   = max(dYawMax,   dYaw)

                val deltaEuler = max(dPitch, max(dRoll, dYaw))
                if (deltaEuler >= EULER_DELTA_THR_DEG) {
                    onConfirmedFall()
                    transition(State.COOLDOWN)
                } else if (now - tStateEntered > POSTURE_WINDOW_MS) {
                    transition(State.IDLE)
                }
            }
            State.COOLDOWN -> { /* no-op */ }
        }
    }

    private fun onConfirmedFall() {
        val deltaMax = max(dPitchMax, max(dRollMax, dYawMax))
        Log.i(
            TAG_EVT,
            "CONFIRMED: A_peak=${"%.2f".format(aPeak)} m/s^2, " +
                    "G_peak=${"%.2f".format(gPeak)} rad/s, " +
                    "ΔPitch=${"%.1f".format(dPitchMax)}, ΔRoll=${"%.1f".format(dRollMax)}, ΔYaw=${"%.1f".format(dYawMax)}, ΔMax=${"%.1f".format(deltaMax)}"
        )
        showFallAlertFullScreen()
        isFallDetected = true
    }

    private fun transition(newState: State) {
        state = newState
        tStateEntered = System.currentTimeMillis()
        Log.d(TAG_STATE, "state=$state")
        if (newState == State.COOLDOWN) {
            handler.postDelayed({
                isFallDetected = false
                transition(State.IDLE)
            }, COOLDOWN_MS)
        }
    }

    private fun logRaw(now: Long) {
        if (now - lastRawLog >= LOG_RAW_EVERY_MS) {
            lastRawLog = now
            Log.d(
                TAG_RAW,
                "t=$now state=$state A=${"%.2f".format(svmA)} m/s^2 G=${"%.2f".format(svmG)} rad/s " +
                        "pitch=${"%.1f".format(pitchDeg)} roll=${"%.1f".format(rollDeg)} yaw=${"%.1f".format(yawDeg)}"
            )
        }
    }

    // ---------- Full-Screen Notification ----------
    private fun showFallAlertFullScreen() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // แพตเทิร์นการสั่น: หน่วง 0ms → สั่น 600 → หยุด 250 → สั่น 600 → หยุด 250 → สั่น 800
        val vibratePattern = longArrayOf(0, 600, 250, 600, 250, 800)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                FALL_ALERT_CHANNEL_ID,
                "Fall Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a fall is detected"
                enableVibration(true)                 // ✅ เปิดสั่นบนช่อง
                vibrationPattern = vibratePattern     // ✅ ตั้งแพตเทิร์นการสั่น
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }

        // ปลุกจอ (สั้นๆ) เผื่อจอดับ
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "BackgroundService:FallWake"
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
            .setSmallIcon(R.drawable.ic_dialog_alert)
            .setContentTitle("พบการล้ม")
            .setContentText("แตะเพื่อยืนยันความปลอดภัย")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(okAction)
            .addAction(notOkAction)
            // ⚠️ สำหรับ Android < O เท่านั้นที่ setVibrate มีผล (O+ จะอิงตามช่อง)
            .setVibrate(vibratePattern)
            .extend(NotificationCompat.WearableExtender().addAction(okAction).addAction(notOkAction))

        nm.notify(FallActionReceiver.NOTIF_ID, builder.build())

        // Fallback: ยิงสั่นผ่าน Vibrator โดยตรง (กันบางรุ่นปิดเสียงแจ้งเตือน)
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


    // ------------------- API parts -------------------
    private fun sendFallToServer(preferenceData: MyPreferenceData, fallStatus: Int) {
        Log.d("FALL_API", "ส่งข้อมูลการล้มไป backend (status: $fallStatus)")

        // 👇 ขอพิกัด 1 ครั้งก่อนส่งไป server

            val lat = standbymain.curLat
            val long = standbymain.curLong
            val client = OkHttpClient()
            val url = "https://afe-project-production.up.railway.app/api/sentFall"
            val jsonBody = """
            {
                "users_id": "${preferenceData.getUserId()}",
                "takecare_id": "${preferenceData.getTakecareId()}",
                "x_axis": "${preferenceData.getXAxis()}",
                "y_axis": "${preferenceData.getYAxis()}",
                "z_axis": "${preferenceData.getZAxis()}",
                "fall_status": "$fallStatus",
                "latitude": "$lat",
                "longitude": "$long"
            }
        """.trimIndent().toRequestBody()
            val request = Request.Builder()
                .url(url)
                .put(jsonBody)
                .addHeader("Content-Type", "application/json")
                .build()
            Thread {
                try {
                    client.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            Log.d("FALL_API", "❌ Error: ${e.message}")
                        }
                        override fun onResponse(call: Call, response: Response) {
                            Log.d("FALL_API", "✅ Sent: ${response.code} Successfully")
                        }
                    })
                } catch (e: IOException) {
                    Log.d("FALL_API", "❌ IOException: ${e.message}")
                }
            }.start()
        }


    private fun sendHeartRateToServer(preferenceData: MyPreferenceData) {
        val client = OkHttpClient()
        val url = "https://afe-project-production.up.railway.app/api/sentHeartRate"
        val body = """
            {
                "uId": "${preferenceData.getUserId()}",
                "takecare_id": "${preferenceData.getTakecareId()}",
                "bpm": "${preferenceData.getHeartRate()}",
                "status": "${preferenceData.getHeartRateStatus()}"
            }
        """.trimIndent().toRequestBody()

        val request = Request.Builder()
            .url(url)
            .put(body)
            .addHeader("Content-Type", "application/json")
            .build()

        Thread {
            try {
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.d("HR_API", "❌ Error: ${e.message}")
                    }
                    override fun onResponse(call: Call, response: Response) {
                        Log.d("HR_API", "✅ Sent: ${response.code}")
                    }
                })
            } catch (e: IOException) {
                Log.d("HR_API", "❌ IOException: ${e.message}")
            }
        }.start()
    }

    private var isLoopRunning = false
    private val refreshIntervalMillis: Long = 10000
    private lateinit var preferenceData : MyPreferenceData
    private val refreshRunnable = object : Runnable {
        override fun run() {
            // เริ่ม tracker ก็ต่อเมื่อเชื่อมต่อแล้ว และยังไม่ได้เริ่ม
            if (isHealthConnected && isSkinTempAvailable && !isSkinTempStarted) {
                tryStartSkinTemperature()
            }

            // GPS วิ่งต่อเนื่องอยู่แล้ว
            requestOkHttpClient(preferenceData)
            sendTemperatureToServer(preferenceData)
            sendHeartRateToServer(preferenceData)

            val temp = preferenceData.getTemperature()
            val status = preferenceData.getTemperatureStatus()
            Log.d("TEMP_PREF", "Stored Temp = $temp °C, Status = $status")

            handler.postDelayed(this, refreshIntervalMillis)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_FALL_MODE -> {
                applyFallModeFromPreferences()
            }
            ACTION_START_TRACKING -> {
                Log.d("GPS_CONTROL", "🚨 ได้รับคำสั่งฉุกเฉิน: บังคับเปิด GPS!")
                isEmergencyMode = true  // เข้าโหมดฉุกเฉิน (ห้ามปิด)
                startLocationUpdates()  // สั่งเปิด GPS ทันที
            }
            ACTION_STOP_TRACKING -> {
                Log.d("GPS_CONTROL", "ได้รับคำสั่งปิดโหมดฉุกเฉิน")
                isEmergencyMode = false // ยกเลิกโหมดฉุกเฉิน
                stopLocationUpdates()   // สั่งปิด GPS (กลับสู่สถานะปกติ)

                // รีเซ็ตค่าพิกัดเป็น 0 (ถ้าต้องการ)
                standbymain.curLat = 0.0
                standbymain.curLong = 0.0
            }
        }
        if (!::preferenceData.isInitialized) {
            preferenceData = MyPreferenceData(this)
        }

        // เช็คว่า Loop ทำงานอยู่หรือยัง? ถ้ายัง ค่อยเริ่ม
        if (!isLoopRunning) {
            handler.post(refreshRunnable)
            isLoopRunning = true
            Log.d("BackgroundService", "🚀 Started Main Loop")
        } else {
            Log.d("BackgroundService", "⚠️ Loop is already running, ignoring start command")
        }

        // เริ่มรอบแรก
//        handler.postDelayed(refreshRunnable, refreshIntervalMillis)
        return START_STICKY
    }

    override fun onConnectionResult(stringResourceId: Int) {
        val msg = when (stringResourceId) {
            1 -> "เชื่อมต่อกับ Health Tracking Service สำเร็จ"
            2 -> "ไม่พบแพลตฟอร์มสุขภาพที่รองรับ"
            else -> "ไม่ทราบสถานะ"
        }
        Log.d("HealthStatus", msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

        isHealthConnected = (stringResourceId == 1)
        if (isHealthConnected && isSkinTempAvailable) {
            tryStartSkinTemperature()
        }
    }

    override fun onSkinTemperatureAvailability(isAvailable: Boolean) {
        Log.d("SkinTempAvailable", "$isAvailable")
        isSkinTempAvailable = isAvailable
        if (isHealthConnected && isSkinTempAvailable && !isSkinTempStarted) {
            tryStartSkinTemperature()
            Log.d("SkinTemp", "Started continuous skin temperature tracking in Service")
        }
    }

    private fun sendTemperatureToServer(preferenceData: MyPreferenceData) {
        val client = OkHttpClient()
        val url = "https://afe-project-production.up.railway.app/api/sentTemperature"

        val body = """
            {
                "uId": "${preferenceData.getUserId()}",
                "takecare_id": "${preferenceData.getTakecareId()}",
                "temperature_value": "${preferenceData.getTemperature()}",
                "status": "${preferenceData.getTemperatureStatus()}"
            }
        """.trimIndent().toRequestBody()

        val request = Request.Builder()
            .url(url)
            .put(body)
            .addHeader("Content-Type", "application/json")
            .build()

        Thread {
            try {
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.d("TEMP_API", " Error: ${e.message}")
                    }
                    override fun onResponse(call: Call, response: Response) {
                        Log.d("TEMP_API", " SentBackground: ${response.code}")
                    }
                })
            } catch (e: IOException) {
                Log.d("TEMP_API", "❌ IOException: ${e.message}")
            }
        }.start()
    }


    // ==== CHANGED: ส่งตำแหน่งด้วย JSON + PUT ให้เหมือน sendTemperatureToServer ====
    private fun requestOkHttpClient(preferenceData: MyPreferenceData) {
        val client = OkHttpClient()
        val url = "https://afe-project-production.up.railway.app/api/sentlocation"

        val jsonString = """
            {
                "uId": "${preferenceData.getUserId()}",
                "takecare_id": "${preferenceData.getTakecareId()}",
                "distance": "${standbymain.distance}",
                "latitude": "${standbymain.curLat}",
                "longitude": "${standbymain.curLong}",
                "battery": "${standbymain.batLevel}",
                "status": "${standbymain.status}"
            }
        """.trimIndent()
        val body = jsonString.toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(url)
            .put(body)
//            .addHeader("Content-Type", "application/json")
            .build()

        Thread {
            try {
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        standbymain.response = 403
                        Log.d("LOC_API", "❌ Error: ${e.message}")
                    }
                    override fun onResponse(call: Call, response: Response) {
                        standbymain.response = response.code
                        // อ่าน body ของ response โดยแปลงเป็น string
                        val responseBodyStr = response.body?.string()
                        Log.d("LOC_API", "Response body = $responseBodyStr")
                        Log.d("LOC_API", "EmergencyMode = ")


                        if (response.isSuccessful && responseBodyStr != null) {
                            try {
                                // แปลง String เป็น JSON Object
                                val json = JSONObject(responseBodyStr)

                                // เช็คว่า Server ส่งคำสั่ง command_tracking มาไหม?
                                if (json.has("command_tracking")) {
                                    val command = json.getBoolean("command_tracking")

                                    // ถ้าค่าไม่เหมือนเดิม ให้สั่งทำงาน
                                    if (command != isServerAllowTrackingGps) {
                                        isServerAllowTrackingGps = command
                                        updateTrackingState(command)
                                    }
                                }

                                if (json.has("request_location")) {

                                    val startView = json.getBoolean("request_location")
                                    Log.d("DEBUG_GPS", "startView: $startView")

                                    if (startView && !isEmergencyMode){
                                        Log.d("GPS_CONTROL", "✅ เปิดตำแหน่ง")
                                        val intent = Intent(this@BackgroundService, BackgroundService::class.java)
                                        intent.action = ACTION_START_TRACKING
                                        startService(intent)
                                    }
                                }

                                if (json.has("stop_emergency")){
                                    val stopNow = json.getBoolean("stop_emergency")

                                    if (stopNow && isEmergencyMode){
                                        Log.d("GPS_CONTROL", "✅ ภารกิจเสร็จสิ้น! ส่ง Intent สั่งปิดตัวเอง")
                                        val intent = Intent(this@BackgroundService, BackgroundService::class.java)
                                        intent.action = ACTION_STOP_TRACKING
                                        startService(intent)
                                    }
                                }

                                if (json.has("request_extended_help_location")) {
                                    val extendedHelp = json.getBoolean("request_extended_help_location")

                                    if (extendedHelp && !isEmergencyMode){
                                        Log.d("GPS_CONTROL", "✅ ส่ง Intent สั่งเปิด Extended Help Location")
                                        val intent = Intent(this@BackgroundService, BackgroundService::class.java)
                                        intent.action = ACTION_START_TRACKING
                                        startService(intent)
                                    }
                                }

                            } catch (e: Exception) {
                                Log.e("LOC_API", "Json Parse Error: ${e.message}")
                            }
                        }
                        Log.d("LOC_API", "✅ Sent: ${isEmergencyMode}")
                        response.close()
                    }
                })
            } catch (e: IOException) {
                standbymain.response = 500
                Log.d("LOC_API", "❌ IOException: ${e.message}")
            }
        }.start()
    }

    // 3. ฟังก์ชันสำหรับ เปิด/ปิด GPS Hardware (เพื่อประหยัดแบต)
    private fun updateTrackingState(enable: Boolean) {
        Handler(Looper.getMainLooper()).post {
            standbymain.isTrackingOn = enable

//            if (!enable && isEmergencyMode) {
//                Log.d("GPS_CONTROL", "Server สั่งปิด แต่ติด Emergency Mode -> เปิดต่อ!")
//                startLocationUpdates()
//                return@post
//            }

            if (enable) {
                Log.d("GPS_CONTROL", "Server สั่ง: ✅ เปิด GPS")
                startLocationUpdates() // เรียกฟังก์ชันเดิมของคุณที่มีอยู่แล้ว
            } else {
                Log.d("GPS_CONTROL", "Server สั่ง: 🛑 ปิด GPS")
                stopLocationUpdates()  // เรียกฟังก์ชันเดิมของคุณที่มีอยู่แล้ว
                standbymain.curLat = 0.0
                standbymain.curLong = 0.0
                standbymain.distance = 0
            }
        }
    }

    // ยังเก็บ method เดิมไว้เผื่อใช้ในอนาคต แต่ไม่ได้ถูกเรียกแล้ว
    fun getCurrentLocation() {
        val gpsTracker = GpsTracker(this)
        gpsTracker.getLocation { location ->
            if (location != null) {
                standbymain.curLat = location.latitude
                standbymain.curLong = location.longitude

                val preferenceData = MyPreferenceData(this)
                standbymain.safeZoneLat = preferenceData.getLat().toDouble()
                standbymain.safeZoneLong = preferenceData.getLong().toDouble()

                if (standbymain.curLat != 0.0 && standbymain.curLong != 0.0) {
                    standbymain.distance = (CalculateDistance().getDistanceFromLatLonInKm(
                        standbymain.curLat, standbymain.curLong,
                        standbymain.safeZoneLat, standbymain.safeZoneLong
                    ) * 1000).roundToInt()
                    standbymain.distanceKM = "%d เมตร".format(standbymain.distance)
                }
            } else {
                Toast.makeText(this, "!!! แจ้งเตือน !!! ไม่สามารถรับพิกัดจาก GPS ได้", Toast.LENGTH_SHORT).show()
                Log.d("GPS", "Unable to get GPS location")
            }
        }
    } // requestOkHttpClient

    override fun onBind(intent: Intent?): IBinder? = null

    // กันบริการโดนระบบกวาด: ตั้ง Alarm ให้ลุกขึ้นมาใหม่
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, BackgroundService::class.java)
        val pi = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1000,
            pi
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopLocationUpdates() // <— หยุด GPS เมื่อ service ถูกทำลาย
        if (::skinTemperatureListener.isInitialized) {
            skinTemperatureListener.stopTracker()
        }
        connectionManager.disconnect()
        sensorManager.unregisterListener(fallSensorListener)
        sensorManager.unregisterListener(gyroListener)
        heartRateListener.stopListening()
        try { wakeLock.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
