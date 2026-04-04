package com.crowdio.mcc_phase3.communication

import android.content.Context
import android.util.Log

/**
 * Example usage of DeviceInfoCollector
 * Demonstrates how to collect and use device specs and performance metrics
 */
class DeviceInfoExample(private val context: Context) {
    
    private val TAG = "DeviceInfoExample"
    
    /**
     * Example: Collect and log device specifications
     */
    fun logDeviceSpecs() {
        val collector = DeviceInfoCollector(context)
        val specs = collector.getDeviceSpecs()
        
        Log.i(TAG, "=== Device Specifications ===")
        Log.i(TAG, "Device Type: ${specs.deviceType}")
        Log.i(TAG, "OS: ${specs.osType} ${specs.osVersion}")
        Log.i(TAG, "CPU: ${specs.cpuModel}")
        Log.i(TAG, "CPU Cores: ${specs.cpuCores}")
        Log.i(TAG, "CPU Frequency: ${specs.cpuFrequencyMhz} MHz")
        Log.i(TAG, "RAM Total: ${specs.ramTotalMb} MB")
        Log.i(TAG, "RAM Available: ${specs.ramAvailableMb} MB")
        Log.i(TAG, "GPU: ${specs.gpuModel}")
        Log.i(TAG, "Battery: ${specs.batteryLevel}%")
        Log.i(TAG, "Charging: ${specs.isCharging}")
        Log.i(TAG, "Network: ${specs.networkType}")
        Log.i(TAG, "Kotlin Version: ${specs.kotlinVersion}")
    }
    
    /**
     * Example: Collect and log performance metrics
     */
    fun logPerformanceMetrics() {
        val collector = DeviceInfoCollector(context)
        val metrics = collector.getPerformanceMetrics()
        
        Log.i(TAG, "=== Performance Metrics ===")
        Log.i(TAG, "CPU Usage: ${metrics.cpuUsagePercent}%")
        Log.i(TAG, "RAM Available: ${metrics.ramAvailableMb} MB")
        Log.i(TAG, "Battery Level: ${metrics.batteryLevel}%")
        Log.i(TAG, "Charging: ${metrics.isCharging}")
        Log.i(TAG, "Network Speed: ${metrics.networkSpeedMbps} Mbps")
        Log.i(TAG, "Storage Available: ${metrics.storageAvailableGb} GB")
        Log.i(TAG, "Timestamp: ${metrics.timestamp}")
    }
    
    /**
     * Example: Create worker ready message with full device info
     */
    fun createWorkerReadyMessage(workerId: String): String {
        return MessageProtocol.createWorkerReadyMessage(workerId, context)
    }
    
    /**
     * Example: Create heartbeat with performance metrics
     */
    fun createHeartbeatWithMetrics(
        workerId: String, 
        taskId: String? = null,
        jobId: String? = null
    ): String {
        return MessageProtocol.createHeartbeatMessage(
            workerId = workerId,
            currentTaskId = taskId,
            jobId = jobId,
            context = context
        )
    }
    
    /**
     * Example: Create pong with performance metrics
     */
    fun createPongWithMetrics(workerId: String): String {
        return MessageProtocol.createPongMessage(workerId, context)
    }
    
    /**
     * Example: Monitor device health (returns true if device is healthy for work)
     */
    fun isDeviceHealthy(): Boolean {
        val collector = DeviceInfoCollector(context)
        val specs = collector.getDeviceSpecs()
        val metrics = collector.getPerformanceMetrics()
        
        // Check battery level (at least 20% or charging)
        val batteryOk = (specs.batteryLevel ?: 100f) > 20f || (specs.isCharging == true)
        
        // Check available RAM (at least 100 MB)
        val ramOk = metrics.ramAvailableMb > 100f
        
        // Check available storage (at least 0.5 GB)
        val storageOk = metrics.storageAvailableGb > 0.5f
        
        // Check network connectivity
        val networkOk = specs.networkType != null
        
        val isHealthy = batteryOk && ramOk && storageOk && networkOk
        
        if (!isHealthy) {
            Log.w(TAG, "Device health check failed:")
            Log.w(TAG, "  Battery: ${specs.batteryLevel}% (charging: ${specs.isCharging}) - ${if (batteryOk) "OK" else "LOW"}")
            Log.w(TAG, "  RAM: ${metrics.ramAvailableMb} MB - ${if (ramOk) "OK" else "LOW"}")
            Log.w(TAG, "  Storage: ${metrics.storageAvailableGb} GB - ${if (storageOk) "OK" else "LOW"}")
            Log.w(TAG, "  Network: ${specs.networkType} - ${if (networkOk) "OK" else "DISCONNECTED"}")
        }
        
        return isHealthy
    }
    
    /**
     * Example: Get device capability score (0-100)
     * Higher score means better device for computation tasks
     */
    fun getDeviceCapabilityScore(): Float {
        val collector = DeviceInfoCollector(context)
        val specs = collector.getDeviceSpecs()
        val metrics = collector.getPerformanceMetrics()
        
        var score = 0f
        
        // CPU cores (max 20 points)
        score += ((specs.cpuCores ?: 0).coerceAtMost(8) / 8f) * 20f
        
        // CPU frequency (max 15 points)
        val freqScore = ((specs.cpuFrequencyMhz ?: 0f).coerceAtMost(3000f) / 3000f) * 15f
        score += freqScore
        
        // RAM (max 25 points)
        val ramScore = ((specs.ramTotalMb ?: 0L).coerceAtMost(8192L) / 8192f) * 25f
        score += ramScore
        
        // Battery (max 15 points)
        val batteryScore = if (specs.isCharging == true) {
            15f // Full points if charging
        } else {
            ((specs.batteryLevel ?: 0f) / 100f) * 15f
        }
        score += batteryScore
        
        // Network (max 15 points)
        val networkScore = when (specs.networkType) {
            "WiFi" -> 15f
            "Ethernet" -> 15f
            "Mobile" -> 10f
            else -> 0f
        }
        score += networkScore
        
        // Available resources (max 10 points)
        val resourceScore = ((metrics.ramAvailableMb / (specs.ramTotalMb ?: 1L)) * 10f).coerceAtMost(10f)
        score += resourceScore
        
        Log.i(TAG, "Device Capability Score: $score/100")
        return score.coerceIn(0f, 100f)
    }
}
