package com.example.mcc_phase3

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.mcc_phase3.utils.ThemeManager

class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize theme manager and apply current theme
        ThemeManager.initialize(this)
        ThemeManager.applyCurrentTheme()
        
        setContentView(R.layout.activity_main)
        
        // Find the mobile worker button
        val mobileWorkerButton = findViewById<Button>(R.id.mobileWorkerButton)
        
        // Set click listener to launch dashboard activity
        mobileWorkerButton?.setOnClickListener {
            val intent = Intent(this, DashboardActivity::class.java)
            startActivity(intent)
        }
    }
}