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

        val btnAfePlus = findViewById<Button>(R.id.btnAfePlus)
        val btnHealthServices = findViewById<Button>(R.id.btnHealthServices)

        btnAfePlus.setOnClickListener {
            navigateToMain()
        }

        btnHealthServices.setOnClickListener {
            navigateToMain()
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, standbymain::class.java)
        startActivity(intent)
        finish()
    }
}
