package com.example.watchsepawv2.presentation

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import com.example.watchsepawv2.R

class ServiceSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_service_selection)

        val btnAfePlus = findViewById<Button>(R.id.button_afe_plus)
        val btnHealthServices = findViewById<Button>(R.id.button_health_services)

        btnAfePlus.setOnClickListener {
            openStandby("AFE_PLUS")
        }

        btnHealthServices.setOnClickListener {
            openStandby("HEALTH_SERVICES")
        }
    }

    private fun openStandby(serviceType: String) {
        val intent = Intent(this, standbymain::class.java).apply {
            putExtra("selected_service", serviceType)
        }
        startActivity(intent)
        finish()
    }
}
