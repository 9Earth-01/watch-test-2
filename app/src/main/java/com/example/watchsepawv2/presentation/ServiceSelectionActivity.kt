package com.example.watchsepawv2.presentation

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.example.watchsepawv2.R

class ServiceSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_service_selection)

        val btnAfePlus = findViewById<Button>(R.id.btnAfePlus)
        val btnHealthServices = findViewById<Button>(R.id.btnHealthServices)

        btnAfePlus.setOnClickListener {
            MyPreferenceData(this).setFallMode(MyPreferenceData.FALL_MODE_CUSTOM)
            startFallDetectionService()
            navigateToMain()
        }

        btnHealthServices.setOnClickListener {
            navigateToMain()
            MyPreferenceData(this).setFallMode(MyPreferenceData.FALL_MODE_SAMSUNG)
            startSamsungHealthEventService()
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, standbymain::class.java)
        startActivity(intent)
        finish()
    }

    private fun startFallDetectionService() {
        val serviceIntent = Intent(this, BackgroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun startSamsungHealthEventService() {
        val serviceIntent = Intent(this, SamsungHealthEventService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
