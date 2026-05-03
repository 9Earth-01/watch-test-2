package com.example.watchsepawv2.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.watchsepawv2.R

class ServiceSelectionActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startSamsungHealthEventService()
        } else {
            // Permission denied, fall back to custom mode
            MyPreferenceData(this).setFallMode(MyPreferenceData.FALL_MODE_CUSTOM)
            startFallDetectionService()
            navigateToMain()
        }
    }

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
            MyPreferenceData(this).setFallMode(MyPreferenceData.FALL_MODE_SAMSUNG)
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED) {
                startSamsungHealthEventService()
                navigateToMain()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
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
