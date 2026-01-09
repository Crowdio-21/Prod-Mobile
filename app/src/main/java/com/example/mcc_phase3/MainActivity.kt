package com.example.mcc_phase3

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.mcc_phase3.utils.ThemeManager

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() called")
        
        // Initialize theme manager and apply current theme
        ThemeManager.initialize(this)
        ThemeManager.applyCurrentTheme()
        
        setContentView(R.layout.activity_main)
        
        // Find the mobile worker button
        val mobileWorkerButton = findViewById<Button>(R.id.mobileWorkerButton)
        
        // Set click listener to launch dashboard activity
        mobileWorkerButton?.setOnClickListener {
            Log.d(TAG, "Mobile Worker button clicked")
            val intent = Intent(this, DashboardActivity::class.java)
            startActivity(intent)
        }
        
        // Find the device info button
        val deviceInfoButton = findViewById<Button>(R.id.deviceInfoButton)
        
        // Set click listener to launch device info activity
        deviceInfoButton?.setOnClickListener {
            Log.d(TAG, "Device Info button clicked")
            val intent = Intent(this, DeviceInfoActivity::class.java)
            startActivity(intent)
        }
    }
    
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart() called")
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() called")
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause() called")
    }
    
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop() called")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() called")
    }
    
    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "onRestart() called")
    }
    
    override fun onBackPressed() {
        Log.d(TAG, "onBackPressed() called")
        super.onBackPressed()
    }
}