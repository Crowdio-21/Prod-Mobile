package com.example.mcc_phase3.communication

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.mcc_phase3.data.device.DeviceInfoManager
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.*

/**
 * Device information collector matching the Python worker schema
 * Collects comprehensive device specs and performance metrics
 * Uses the existing DeviceInfoManager for safe device info collection
 */
class DeviceInfoCollector(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceInfoCollector"
    }
    
    private val deviceInfoManager = DeviceInfoManager(context)
    
    // ============================================
    // Device Specifications
    // ============================================
    
    /**
     * Collect complete device specifications matching Python worker format
     * Uses DeviceInfoManager for safe, comprehensive device info collection
     */
    fun getDeviceSpecs(): DeviceSpecs {
        Log.d(TAG, "Collecting device specifications using DeviceInfoManager...")
        
        return try {
            // Use runBlocking to call suspend functions synchronously
            // This is safe here as we're collecting device info
            runBlocking {
                val metrics = deviceInfoManager.getDeviceMetrics()
                
                DeviceSpecs(
                    deviceType = "Android",
                    osType = "Android",
                    osVersion = metrics.deviceInfo.androidVersion,
                    cpuModel = getCpuModelFromDeviceInfo(metrics.deviceInfo),
                    cpuCores = metrics.deviceInfo.cpuInfo.cores,
                    cpuThreads = metrics.deviceInfo.cpuInfo.cores, // Same as cores on Android
                    cpuFrequencyMhz = (metrics.deviceInfo.cpuInfo.maxFrequency / 1_000_000f).takeIf { it > 0 },
                    ramTotalMb = (metrics.ramInfo.totalRam / (1024 * 1024)),
                    ramAvailableMb = (metrics.ramInfo.availableRam / (1024 * 1024)),
                    gpuModel = null, // GPU info not critical, can be null
                    batteryLevel = metrics.batteryInfo.level.toFloat(),
                    isCharging = metrics.batteryInfo.isCharging,
                    networkType = getNetworkTypeName(metrics.networkInfo),
                    kotlinVersion = KotlinVersion.CURRENT.toString()
                )
            }.also {
                Log.d(TAG, "Device specs collected successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting device specs, using safe defaults", e)
            // Return safe default values
            DeviceSpecs(
                deviceType = "Android",
                osType = "Android",
                osVersion = Build.VERSION.RELEASE,
                cpuModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                cpuCores = Runtime.getRuntime().availableProcessors(),
                cpuThreads = Runtime.getRuntime().availableProcessors(),
                cpuFrequencyMhz = null,
                ramTotalMb = null,
                ramAvailableMb = null,
                gpuModel = null,
                batteryLevel = null,
                isCharging = null,
                networkType = null,
                kotlinVersion = KotlinVersion.CURRENT.toString()
            )
        }
    }
    
    /**
     * Get CPU model description from device info
     */
    private fun getCpuModelFromDeviceInfo(deviceInfo: com.example.mcc_phase3.data.models.DeviceInfo): String {
        return "${deviceInfo.manufacturer} ${deviceInfo.model} (${deviceInfo.cpuInfo.architecture})"
    }
    
    /**
     * Convert network info to simple type name
     */
    private fun getNetworkTypeName(networkInfo: com.example.mcc_phase3.data.models.NetworkInfo): String? {
        if (!networkInfo.isConnected) return null
        
        return when (networkInfo.connectionType) {
            com.example.mcc_phase3.data.models.ConnectionType.WIFI -> "WiFi"
            com.example.mcc_phase3.data.models.ConnectionType.MOBILE -> "Mobile"
            com.example.mcc_phase3.data.models.ConnectionType.ETHERNET -> "Ethernet"
            else -> "Unknown"
        }
    }
    
    // ============================================
    // Performance Metrics
    // ============================================
    
    /**
     * Collect current performance metrics
     * Uses DeviceInfoManager for safe, comprehensive metrics collection
     */
    fun getPerformanceMetrics(): PerformanceMetrics {
        Log.d(TAG, "Collecting performance metrics using DeviceInfoManager...")
        
        return try {
            runBlocking {
                val metrics = deviceInfoManager.getDeviceMetrics()
                
                PerformanceMetrics(
                    cpuUsagePercent = metrics.deviceInfo.cpuInfo.cpuUsage,
                    ramAvailableMb = (metrics.ramInfo.availableRam / (1024 * 1024f)),
                    batteryLevel = metrics.batteryInfo.level.toFloat(),
                    isCharging = metrics.batteryInfo.isCharging,
                    networkSpeedMbps = estimateNetworkSpeed(metrics.networkInfo),
                    storageAvailableGb = (metrics.storageInfo.internalStorage.availableSpace / (1024f * 1024f * 1024f)),
                    timestamp = getCurrentTimestamp()
                )
            }.also {
                Log.d(TAG, "Performance metrics collected successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting performance metrics, using safe defaults", e)
            // Return safe default values
            PerformanceMetrics(
                cpuUsagePercent = 0f,
                ramAvailableMb = 0f,
                batteryLevel = null,
                isCharging = null,
                networkSpeedMbps = 0f,
                storageAvailableGb = 0f,
                timestamp = getCurrentTimestamp()
            )
        }
    }
    
    /**
     * Estimate network speed based on connection type
     */
    private fun estimateNetworkSpeed(networkInfo: com.example.mcc_phase3.data.models.NetworkInfo): Float {
        if (!networkInfo.isConnected) return 0f
        
        return when (networkInfo.connectionType) {
            com.example.mcc_phase3.data.models.ConnectionType.WIFI -> 50f // Conservative WiFi estimate
            com.example.mcc_phase3.data.models.ConnectionType.ETHERNET -> 100f // Ethernet estimate
            com.example.mcc_phase3.data.models.ConnectionType.MOBILE -> 10f // Mobile data estimate
            else -> 0f
        }
    }
    
    /**
     * Get current timestamp in ISO format
     */
    private fun getCurrentTimestamp(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        return dateFormat.format(Date())
    }
}

// ============================================
// Data Models (matching Python schema)
// ============================================

data class DeviceSpecs(
    val deviceType: String = "Android",
    val osType: String,
    val osVersion: String,
    val cpuModel: String?,
    val cpuCores: Int?,
    val cpuThreads: Int?,
    val cpuFrequencyMhz: Float?,
    val ramTotalMb: Long?,
    val ramAvailableMb: Long?,
    val gpuModel: String?,
    val batteryLevel: Float?,
    val isCharging: Boolean?,
    val networkType: String?,
    val kotlinVersion: String = KotlinVersion.CURRENT.toString()
)

data class PerformanceMetrics(
    val cpuUsagePercent: Float,
    val ramAvailableMb: Float,
    val batteryLevel: Float?,
    val isCharging: Boolean?,
    val networkSpeedMbps: Float,
    val storageAvailableGb: Float,
    val timestamp: String
)

// ============================================
// Helper Extensions
// ============================================

/**
 * Convert DeviceSpecs to map for JSON serialization
 */
fun DeviceSpecs.toMap(): Map<String, Any?> = mapOf(
    "device_type" to deviceType,
    "os_type" to osType,
    "os_version" to osVersion,
    "cpu_model" to cpuModel,
    "cpu_cores" to cpuCores,
    "cpu_threads" to cpuThreads,
    "cpu_frequency_mhz" to cpuFrequencyMhz,
    "ram_total_mb" to ramTotalMb,
    "ram_available_mb" to ramAvailableMb,
    "gpu_model" to gpuModel,
    "battery_level" to batteryLevel,
    "is_charging" to isCharging,
    "network_type" to networkType,
    "kotlin_version" to kotlinVersion
)

/**
 * Convert PerformanceMetrics to map for JSON serialization
 */
fun PerformanceMetrics.toMap(): Map<String, Any?> = mapOf(
    "cpu_usage_percent" to cpuUsagePercent,
    "ram_available_mb" to ramAvailableMb,
    "battery_level" to batteryLevel,
    "is_charging" to isCharging,
    "network_speed_mbps" to networkSpeedMbps,
    "storage_available_gb" to storageAvailableGb,
    "timestamp" to timestamp
)
