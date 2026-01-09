package com.example.mcc_phase3.data.device

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects device specifications for the WORKER_READY handshake message
 * Matches the database schema fields for worker device information
 */
class DeviceSpecsCollector(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceSpecsCollector"
    }
    
    private val deviceInfoManager = DeviceInfoManager(context)
    
    /**
     * Collect all device specifications for WORKER_READY message
     */
    suspend fun collectDeviceSpecs(): DeviceSpecs = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📊 Collecting device specifications for WORKER_READY message")
            
            // Collect device metrics
            val deviceMetrics = deviceInfoManager.getDeviceMetrics()
            
            // Extract relevant information
            val deviceInfo = deviceMetrics.deviceInfo
            val batteryInfo = deviceMetrics.batteryInfo
            val ramInfo = deviceMetrics.ramInfo
            val networkInfo = deviceMetrics.networkInfo
            val cpuInfo = deviceInfo.cpuInfo
            
            // Build OS version string
            val osVersion = "Android ${deviceInfo.androidVersion} (SDK ${deviceInfo.sdkLevel})"
            
            // CPU model - combination of architecture and hardware
            val cpuModel = "${cpuInfo.architecture} - ${deviceInfo.hardware}"
            
            // CPU frequency in MHz (convert from Hz)
            val cpuFrequencyMhz = if (cpuInfo.maxFrequency > 0) {
                (cpuInfo.maxFrequency / 1_000_000.0f)
            } else null
            
            // RAM in MB (convert from bytes)
            val ramTotalMb = (ramInfo.totalRam / (1024.0f * 1024.0f))
            val ramAvailableMb = (ramInfo.availableRam / (1024.0f * 1024.0f))
            
            // GPU model - use hardware info as proxy (Android doesn't expose GPU info easily)
            val gpuModel = "GPU: ${deviceInfo.hardware} (${deviceInfo.manufacturer})"
            
            // Battery information
            val batteryLevel = batteryInfo.level.toFloat()
            val isCharging = batteryInfo.isCharging
            
            // Network type
            val networkType = when (networkInfo.connectionType) {
                com.example.mcc_phase3.data.models.ConnectionType.WIFI -> "WiFi"
                com.example.mcc_phase3.data.models.ConnectionType.MOBILE -> "Cellular"
                com.example.mcc_phase3.data.models.ConnectionType.ETHERNET -> "Ethernet"
                com.example.mcc_phase3.data.models.ConnectionType.NONE -> "None"
                else -> "Unknown"
            }
            
            val specs = DeviceSpecs(
                deviceType = "Android",
                osType = "Android",
                osVersion = osVersion,
                cpuModel = cpuModel,
                cpuCores = cpuInfo.cores,
                cpuThreads = cpuInfo.cores, // Android doesn't distinguish, use cores
                cpuFrequencyMhz = cpuFrequencyMhz,
                ramTotalMb = ramTotalMb,
                ramAvailableMb = ramAvailableMb,
                gpuModel = gpuModel,
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                networkType = networkType
            )
            
            Log.d(TAG, "✅ Device specs collected successfully")
            Log.d(TAG, "📊 CPU: $cpuModel, Cores: ${cpuInfo.cores}, Freq: ${cpuFrequencyMhz}MHz")
            Log.d(TAG, "📊 RAM: ${ramTotalMb.toInt()}MB total, ${ramAvailableMb.toInt()}MB available")
            Log.d(TAG, "📊 Battery: ${batteryLevel.toInt()}%, Charging: $isCharging")
            Log.d(TAG, "📊 Network: $networkType")
            
            specs
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to collect device specs", e)
            // Return minimal specs on error
            DeviceSpecs(
                deviceType = "Android",
                osType = "Android",
                osVersion = "Android ${Build.VERSION.RELEASE}"
            )
        }
    }
}

/**
 * Data class containing device specifications matching database schema
 */
data class DeviceSpecs(
    val deviceType: String,
    val osType: String,
    val osVersion: String? = null,
    val cpuModel: String? = null,
    val cpuCores: Int? = null,
    val cpuThreads: Int? = null,
    val cpuFrequencyMhz: Float? = null,
    val ramTotalMb: Float? = null,
    val ramAvailableMb: Float? = null,
    val gpuModel: String? = null,
    val batteryLevel: Float? = null,
    val isCharging: Boolean? = null,
    val networkType: String? = null,
    val pythonVersion: String? = null
)
