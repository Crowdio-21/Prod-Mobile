package com.example.mcc_phase3

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.mcc_phase3.data.device.DeviceInfoFormatter
import com.example.mcc_phase3.data.models.SecurityConfig
import com.example.mcc_phase3.data.models.SecurityLevel
import com.example.mcc_phase3.data.repository.CrowdComputeRepository
import kotlinx.coroutines.launch

/**
 * Example activity demonstrating device information collection
 */
class DeviceInfoActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "DeviceInfoActivity"
    }
    
    private lateinit var repository: CrowdComputeRepository
    private lateinit var outputTextView: TextView
    private lateinit var refreshButton: Button
    private lateinit var securityButton: Button
    private lateinit var clearButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_info)
        
        // Initialize repository
        repository = CrowdComputeRepository(this)
        
        // Initialize views
        outputTextView = findViewById(R.id.outputTextView)
        refreshButton = findViewById(R.id.refreshButton)
        securityButton = findViewById(R.id.securityButton)
        clearButton = findViewById(R.id.clearButton)
        
        // Set up click listeners
        refreshButton.setOnClickListener { collectDeviceInfo() }
        securityButton.setOnClickListener { toggleSecurityConfig() }
        clearButton.setOnClickListener { clearOutput() }
        
        // Set initial security config
        setStandardSecurityConfig()
        
        // Collect initial device info
        collectDeviceInfo()
    }
    
    /**
     * Collect comprehensive device information
     */
    private fun collectDeviceInfo() {
        outputTextView.text = "🔄 Collecting device information..."
        
        lifecycleScope.launch {
            try {
                val result = repository.getDeviceMetrics()
                
                if (result.isSuccess) {
                    val metrics = result.getOrNull()!!
                    val formattedInfo = DeviceInfoFormatter.formatDeviceMetrics(metrics)
                    outputTextView.text = formattedInfo
                    
                    Log.d(TAG, "✅ Device info collected successfully")
                    Log.d(TAG, DeviceInfoFormatter.getBriefSummary(metrics))
                } else {
                    val error = result.exceptionOrNull()
                    outputTextView.text = "❌ Error collecting device information:\n${error?.message}"
                    Log.e(TAG, "❌ Failed to collect device info", error)
                }
            } catch (e: Exception) {
                outputTextView.text = "❌ Exception occurred:\n${e.message}"
                Log.e(TAG, "❌ Exception in collectDeviceInfo", e)
            }
        }
    }
    
    /**
     * Toggle between different security configurations
     */
    private fun toggleSecurityConfig() {
        val currentConfig = repository.getCachedDeviceMetrics()?.securityLevel
        
        val newConfig = when (currentConfig) {
            SecurityLevel.MINIMAL -> {
                setStandardSecurityConfig()
                "Standard"
            }
            SecurityLevel.STANDARD -> {
                setDetailedSecurityConfig()
                "Detailed"
            }
            SecurityLevel.DETAILED -> {
                setFullSecurityConfig()
                "Full"
            }
            SecurityLevel.FULL -> {
                setMinimalSecurityConfig()
                "Minimal"
            }
            else -> {
                setStandardSecurityConfig()
                "Standard"
            }
        }
        
        outputTextView.text = "🔒 Security level changed to: $newConfig\n\nRefreshing device information..."
        collectDeviceInfo()
    }
    
    /**
     * Set minimal security configuration
     */
    private fun setMinimalSecurityConfig() {
        val config = SecurityConfig(
            collectSerialNumber = false,
            collectIpAddress = false,
            collectDetailedBatteryInfo = false,
            collectDetailedStorageInfo = false,
            collectCpuUsage = false,
            maskSensitiveData = true,
            encryptionRequired = false
        )
        repository.setDeviceInfoSecurityConfig(config)
    }
    
    /**
     * Set standard security configuration
     */
    private fun setStandardSecurityConfig() {
        val config = SecurityConfig(
            collectSerialNumber = false,
            collectIpAddress = false,
            collectDetailedBatteryInfo = true,
            collectDetailedStorageInfo = true,
            collectCpuUsage = true,
            maskSensitiveData = true,
            encryptionRequired = false
        )
        repository.setDeviceInfoSecurityConfig(config)
    }
    
    /**
     * Set detailed security configuration
     */
    private fun setDetailedSecurityConfig() {
        val config = SecurityConfig(
            collectSerialNumber = false,
            collectIpAddress = true,
            collectDetailedBatteryInfo = true,
            collectDetailedStorageInfo = true,
            collectCpuUsage = true,
            maskSensitiveData = false,
            encryptionRequired = false
        )
        repository.setDeviceInfoSecurityConfig(config)
    }
    
    /**
     * Set full security configuration (use with caution)
     */
    private fun setFullSecurityConfig() {
        val config = SecurityConfig(
            collectSerialNumber = true,
            collectIpAddress = true,
            collectDetailedBatteryInfo = true,
            collectDetailedStorageInfo = true,
            collectCpuUsage = true,
            maskSensitiveData = false,
            encryptionRequired = true
        )
        repository.setDeviceInfoSecurityConfig(config)
    }
    
    /**
     * Clear output and cache
     */
    private fun clearOutput() {
        outputTextView.text = "🗑️ Output cleared"
        repository.clearDeviceInfoCache()
        Log.d(TAG, "🗑️ Device info cache cleared")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources if needed
        repository.clearDeviceInfoCache()
    }
}
