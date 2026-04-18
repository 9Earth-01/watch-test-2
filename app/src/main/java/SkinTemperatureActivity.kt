package com.example.watchsepawv2.presentation

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.example.watchsepawv2.R

class SkinTemperatureActivity : Activity() {

    private lateinit var txtTemperature: TextView
    private lateinit var preferenceData: MyPreferenceData
    private val handler = Handler(Looper.getMainLooper())
    private val refreshIntervalMillis: Long = 10_000 // 10 วินาที

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skin_temperature)

        txtTemperature = findViewById(R.id.txtTemperatureValue)
        preferenceData = MyPreferenceData(this)

        // แสดงค่าล่าสุดทันที
        updateTemperatureDisplay()

        // อัปเดตค่าจาก SharedPreferences เป็นระยะ (อ่านค่าอย่างเดียว)
        handler.postDelayed(refreshRunnable, refreshIntervalMillis)
    }

    private fun updateTemperatureDisplay() {
        val temp = preferenceData.getTemperature()
        val tempFormatted = temp.toFloatOrNull()?.let { "%.1f".format(it) } ?: "-"
        txtTemperature.text = "อุณหภูมิ: $tempFormatted °C"
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateTemperatureDisplay()
            handler.postDelayed(this, refreshIntervalMillis)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
