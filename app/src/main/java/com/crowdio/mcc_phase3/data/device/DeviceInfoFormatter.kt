package com.crowdio.mcc_phase3.data.device

import com.crowdio.mcc_phase3.data.models.*
import java.text.DecimalFormat
import kotlin.math.pow

/**
 * Utility class for formatting device information for display
 */
object DeviceInfoFormatter {
    
    private val decimalFormat = DecimalFormat("#.##")
    private val largeNumberFormat = DecimalFormat("#,###")
    
    /**
     * Format bytes to human readable format
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${decimalFormat.format(bytes / 1024.0)} KB"
            bytes < 1024.0.pow(3) -> "${decimalFormat.format(bytes / (1024 * 1024.0))} MB"
            bytes < 1024.0.pow(4) -> "${decimalFormat.format(bytes / (1024.0.pow(3)))} GB"
            else -> "${decimalFormat.format(bytes / (1024.0.pow(4)))} TB"
        }
    }
    
    /**
     * Format frequency to human readable format
     */
    fun formatFrequency(hz: Long): String {
        return when {
            hz < 1000 -> "$hz Hz"
            hz < 1000000 -> "${decimalFormat.format(hz / 1000.0)} KHz"
            else -> "${decimalFormat.format(hz / 1000000.0)} MHz"
        }
    }
    
    /**
     * Format battery information for display
     */
    fun formatBatteryInfo(batteryInfo: BatteryInfo): String {
        val level = batteryInfo.level
        val status = when (batteryInfo.chargeStatus) {
            ChargeStatus.CHARGING -> "Charging"
            ChargeStatus.DISCHARGING -> "Discharging"
            ChargeStatus.FULL -> "Full"
            ChargeStatus.NOT_CHARGING -> "Not Charging"
            ChargeStatus.UNKNOWN -> "Unknown"
        }
        
        val powerSource = when (batteryInfo.powerSource) {
            PowerSource.AC -> "AC Adapter"
            PowerSource.USB -> "USB"
            PowerSource.WIRELESS -> "Wireless"
            PowerSource.BATTERY -> "Battery"
            PowerSource.UNKNOWN -> "Unknown"
        }
        
        val health = when (batteryInfo.health) {
            BatteryHealth.GOOD -> "Good"
            BatteryHealth.OVERHEAT -> "Overheating"
            BatteryHealth.DEAD -> "Dead"
            BatteryHealth.OVER_VOLTAGE -> "Over Voltage"
            BatteryHealth.UNSPECIFIED_FAILURE -> "Failure"
            BatteryHealth.COLD -> "Cold"
            BatteryHealth.UNKNOWN -> "Unknown"
        }
        
        val temperature = if (batteryInfo.temperature > 0) "${batteryInfo.temperature}°C" else "Unknown"
        val voltage = if (batteryInfo.voltage > 0) "${batteryInfo.voltage}mV" else "Unknown"
        
        return buildString {
            appendLine("🔋 Battery Information")
            appendLine("Level: $level%")
            appendLine("Status: $status")
            appendLine("Power Source: $powerSource")
            appendLine("Health: $health")
            appendLine("Temperature: $temperature")
            appendLine("Voltage: $voltage")
            appendLine("Technology: ${batteryInfo.technology}")
            
            if (batteryInfo.capacity > 0) {
                appendLine("Capacity: ${formatBytes(batteryInfo.capacity)}")
            }
            if (batteryInfo.currentNow != 0L) {
                appendLine("Current: ${batteryInfo.currentNow}μA")
            }
        }
    }
    
    /**
     * Format RAM information for display
     */
    fun formatRamInfo(ramInfo: RamInfo): String {
        val totalRam = formatBytes(ramInfo.totalRam)
        val availableRam = formatBytes(ramInfo.availableRam)
        val usedRam = formatBytes(ramInfo.usedRam)
        val freeRam = formatBytes(ramInfo.freeRam)
        val threshold = formatBytes(ramInfo.threshold)
        val usagePercentage = decimalFormat.format(ramInfo.ramUsagePercentage)
        val memoryStatus = if (ramInfo.isLowMemory) "⚠️ Low Memory" else "✅ Normal"
        
        return buildString {
            appendLine("💾 RAM Information")
            appendLine("Total RAM: $totalRam")
            appendLine("Available RAM: $availableRam")
            appendLine("Used RAM: $usedRam")
            appendLine("Free RAM: $freeRam")
            appendLine("Usage: $usagePercentage%")
            appendLine("Low Memory Threshold: $threshold")
            appendLine("Memory Status: $memoryStatus")
        }
    }
    
    /**
     * Format storage information for display
     */
    fun formatStorageInfo(storageInfo: StorageInfo): String {
        val internal = storageInfo.internalStorage
        val external = storageInfo.externalStorage
        
        return buildString {
            appendLine("💿 Storage Information")
            appendLine()
            appendLine("📱 Internal Storage:")
            appendLine("  Total: ${formatBytes(internal.totalSpace)}")
            appendLine("  Available: ${formatBytes(internal.availableSpace)}")
            appendLine("  Used: ${formatBytes(internal.usedSpace)}")
            appendLine("  Usage: ${decimalFormat.format(internal.usagePercentage)}%")
            appendLine("  Read-only: ${if (internal.isReadOnly) "Yes" else "No"}")
            appendLine("  Path: ${internal.path}")
            
            if (external != null) {
                appendLine()
                appendLine("💾 External Storage:")
                appendLine("  Total: ${formatBytes(external.totalSpace)}")
                appendLine("  Available: ${formatBytes(external.availableSpace)}")
                appendLine("  Used: ${formatBytes(external.usedSpace)}")
                appendLine("  Usage: ${decimalFormat.format(external.usagePercentage)}%")
                appendLine("  Removable: ${if (external.isRemovable) "Yes" else "No"}")
                appendLine("  Read-only: ${if (external.isReadOnly) "Yes" else "No"}")
                appendLine("  Path: ${external.path}")
            } else {
                appendLine()
                appendLine("💾 External Storage: Not Available")
            }
        }
    }
    
    /**
     * Format device information for display
     */
    fun formatDeviceInfo(deviceInfo: DeviceInfo): String {
        val cpuInfo = deviceInfo.cpuInfo
        val maxFreq = if (cpuInfo.maxFrequency > 0) formatFrequency(cpuInfo.maxFrequency) else "Unknown"
        val currentFreq = if (cpuInfo.currentFrequency > 0) formatFrequency(cpuInfo.currentFrequency) else "Unknown"
        val cpuUsage = decimalFormat.format(cpuInfo.cpuUsage)
        
        return buildString {
            appendLine("📱 Device Information")
            appendLine("Manufacturer: ${deviceInfo.manufacturer}")
            appendLine("Model: ${deviceInfo.model}")
            appendLine("Brand: ${deviceInfo.brand}")
            appendLine("Product: ${deviceInfo.product}")
            appendLine("Device: ${deviceInfo.device}")
            appendLine("Hardware: ${deviceInfo.hardware}")
            appendLine("Android Version: ${deviceInfo.androidVersion}")
            appendLine("SDK Level: ${deviceInfo.sdkLevel}")
            appendLine("Build Number: ${deviceInfo.buildNumber}")
            appendLine("Unique ID: ${deviceInfo.uniqueId}")
            
            if (deviceInfo.serialNumber != null) {
                appendLine("Serial Number: ${deviceInfo.serialNumber}")
            }
            
            appendLine()
            appendLine("🖥️ CPU Information:")
            appendLine("  Architecture: ${cpuInfo.architecture}")
            appendLine("  Cores: ${cpuInfo.cores}")
            appendLine("  Max Frequency: $maxFreq")
            appendLine("  Current Frequency: $currentFreq")
            appendLine("  Usage: $cpuUsage%")
        }
    }
    
    /**
     * Format network information for display
     */
    fun formatNetworkInfo(networkInfo: NetworkInfo): String {
        val connectionType = when (networkInfo.connectionType) {
            ConnectionType.WIFI -> "WiFi"
            ConnectionType.MOBILE -> "Mobile Data"
            ConnectionType.ETHERNET -> "Ethernet"
            ConnectionType.BLUETOOTH -> "Bluetooth"
            ConnectionType.VPN -> "VPN"
            ConnectionType.NONE -> "None"
            ConnectionType.UNKNOWN -> "Unknown"
        }
        
        val signalStrength = networkInfo.signalStrength?.let { strength ->
            when (strength) {
                0 -> "None"
                1 -> "Poor"
                2 -> "Fair"
                3 -> "Good"
                4 -> "Excellent"
                else -> "Unknown"
            }
        } ?: "N/A"
        
        return buildString {
            appendLine("🌐 Network Information")
            appendLine("Connected: ${if (networkInfo.isConnected) "Yes" else "No"}")
            appendLine("Connection Type: $connectionType")
            appendLine("Network Type: ${networkInfo.networkType}")
            appendLine("Signal Strength: $signalStrength")
            
            if (networkInfo.ipAddress != null) {
                appendLine("IP Address: ${networkInfo.ipAddress}")
            }
        }
    }
    
    /**
     * Format comprehensive device metrics for display
     */
    fun formatDeviceMetrics(metrics: DeviceMetrics): String {
        return buildString {
            appendLine("📊 Device Metrics Summary")
            appendLine("=".repeat(50))
            appendLine()
            appendLine(formatBatteryInfo(metrics.batteryInfo))
            appendLine()
            appendLine(formatRamInfo(metrics.ramInfo))
            appendLine()
            appendLine(formatStorageInfo(metrics.storageInfo))
            appendLine()
            appendLine(formatDeviceInfo(metrics.deviceInfo))
            appendLine()
            appendLine(formatNetworkInfo(metrics.networkInfo))
            appendLine()
            appendLine("Security Level: ${metrics.securityLevel}")
            appendLine("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(metrics.timestamp))}")
        }
    }
    
    /**
     * Get a brief summary of device metrics
     */
    fun getBriefSummary(metrics: DeviceMetrics): String {
        return "Device: ${metrics.deviceInfo.manufacturer} ${metrics.deviceInfo.model} | " +
               "Battery: ${metrics.batteryInfo.level}% (${metrics.batteryInfo.chargeStatus}) | " +
               "RAM: ${decimalFormat.format(metrics.ramInfo.ramUsagePercentage)}% | " +
               "Storage: ${decimalFormat.format(metrics.storageInfo.internalStorage.usagePercentage)}% | " +
               "Network: ${metrics.networkInfo.connectionType}"
    }
    
    /**
     * Get battery status summary
     */
    fun getBatterySummary(batteryInfo: BatteryInfo): String {
        val status = when (batteryInfo.chargeStatus) {
            ChargeStatus.CHARGING -> "🔌"
            ChargeStatus.DISCHARGING -> "🔋"
            ChargeStatus.FULL -> "✅"
            ChargeStatus.NOT_CHARGING -> "⏸️"
            ChargeStatus.UNKNOWN -> "❓"
        }
        
        return "$status ${batteryInfo.level}% (${batteryInfo.powerSource})"
    }
    
    /**
     * Get storage summary
     */
    fun getStorageSummary(storageInfo: StorageInfo): String {
        val internal = storageInfo.internalStorage
        val external = storageInfo.externalStorage
        
        return "Internal: ${decimalFormat.format(internal.usagePercentage)}% " +
               "(${formatBytes(internal.availableSpace)} free)" +
               (external?.let { " | External: ${decimalFormat.format(it.usagePercentage)}%" } ?: "")
    }
    
    /**
     * Get RAM summary
     */
    fun getRamSummary(ramInfo: RamInfo): String {
        val status = if (ramInfo.isLowMemory) "⚠️" else "✅"
        return "$status ${decimalFormat.format(ramInfo.ramUsagePercentage)}% " +
               "(${formatBytes(ramInfo.availableRam)} available)"
    }
}
