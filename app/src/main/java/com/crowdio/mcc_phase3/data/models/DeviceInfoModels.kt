package com.crowdio.mcc_phase3.data.models

import android.os.Build
import java.io.Serializable

/**
 * Comprehensive battery information with security considerations
 */
data class BatteryInfo(
    val level: Int, // Battery level percentage (0-100)
    val isCharging: Boolean, // Whether device is currently charging
    val chargeStatus: ChargeStatus, // Detailed charging status
    val powerSource: PowerSource, // Power source type
    val health: BatteryHealth, // Battery health status
    val temperature: Float, // Battery temperature in Celsius
    val voltage: Float, // Battery voltage in millivolts
    val technology: String, // Battery technology (e.g., "Li-ion")
    val capacity: Long, // Battery capacity in microampere-hours
    val currentNow: Long, // Current battery current in microamperes
    val chargeCounter: Long, // Battery charge counter
    val fullChargeCounter: Long, // Full charge counter
    val timestamp: Long = System.currentTimeMillis() // When this info was collected
) : Serializable

/**
 * Enum for battery charging status
 */
enum class ChargeStatus {
    UNKNOWN,
    CHARGING,
    DISCHARGING,
    FULL,
    NOT_CHARGING
}

/**
 * Enum for power source types
 */
enum class PowerSource {
    UNKNOWN,
    AC, // AC adapter
    USB, // USB cable
    WIRELESS, // Wireless charging
    BATTERY // Running on battery
}

/**
 * Enum for battery health status
 */
enum class BatteryHealth {
    UNKNOWN,
    GOOD,
    OVERHEAT,
    DEAD,
    OVER_VOLTAGE,
    UNSPECIFIED_FAILURE,
    COLD
}

/**
 * RAM information with security considerations
 */
data class RamInfo(
    val totalRam: Long, // Total RAM in bytes
    val availableRam: Long, // Available RAM in bytes
    val usedRam: Long, // Used RAM in bytes
    val freeRam: Long, // Free RAM in bytes
    val threshold: Long, // Low memory threshold in bytes
    val isLowMemory: Boolean, // Whether device is in low memory state
    val ramUsagePercentage: Float, // RAM usage as percentage
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * Storage information with security considerations
 */
data class StorageInfo(
    val internalStorage: StorageDetails,
    val externalStorage: StorageDetails?,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * Detailed storage information
 */
data class StorageDetails(
    val totalSpace: Long, // Total space in bytes
    val availableSpace: Long, // Available space in bytes
    val usedSpace: Long, // Used space in bytes
    val freeSpace: Long, // Free space in bytes
    val usagePercentage: Float, // Usage as percentage
    val isReadOnly: Boolean, // Whether storage is read-only
    val isRemovable: Boolean, // Whether storage is removable
    val path: String // Storage path
) : Serializable

/**
 * Device information with security considerations
 */
data class DeviceInfo(
    val manufacturer: String, // Device manufacturer
    val model: String, // Device model
    val brand: String, // Device brand
    val product: String, // Product name
    val device: String, // Device name
    val hardware: String, // Hardware platform
    val androidVersion: String, // Android version (e.g., "11")
    val sdkLevel: Int, // Android SDK level
    val buildNumber: String, // Build number
    val fingerprint: String, // Build fingerprint
    val serialNumber: String?, // Device serial number (null if not accessible)
    val uniqueId: String, // Unique device identifier (non-sensitive)
    val cpuInfo: CpuInfo, // CPU information
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * CPU information
 */
data class CpuInfo(
    val architecture: String, // CPU architecture
    val cores: Int, // Number of CPU cores
    val maxFrequency: Long, // Maximum CPU frequency in Hz
    val currentFrequency: Long, // Current CPU frequency in Hz
    val cpuUsage: Float // Current CPU usage percentage
) : Serializable

/**
 * Comprehensive device metrics combining all information
 */
data class DeviceMetrics(
    val batteryInfo: BatteryInfo,
    val ramInfo: RamInfo,
    val storageInfo: StorageInfo,
    val deviceInfo: DeviceInfo,
    val networkInfo: NetworkInfo,
    val timestamp: Long = System.currentTimeMillis(),
    val securityLevel: SecurityLevel = SecurityLevel.STANDARD
) : Serializable

/**
 * Network information
 */
data class NetworkInfo(
    val isConnected: Boolean,
    val connectionType: ConnectionType,
    val networkType: String,
    val signalStrength: Int?, // Signal strength in dBm (null if not available)
    val ipAddress: String?, // IP address (null if not available)
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * Network connection types
 */
enum class ConnectionType {
    NONE,
    WIFI,
    MOBILE,
    ETHERNET,
    BLUETOOTH,
    VPN,
    UNKNOWN
}

/**
 * Security levels for data collection
 */
enum class SecurityLevel {
    MINIMAL, // Only basic, non-sensitive information
    STANDARD, // Standard device metrics
    DETAILED, // Detailed metrics with some sensitive data
    FULL // Full metrics (use with caution)
}

/**
 * Security configuration for device information collection
 */
data class SecurityConfig(
    val collectSerialNumber: Boolean = false,
    val collectIpAddress: Boolean = false,
    val collectDetailedBatteryInfo: Boolean = true,
    val collectDetailedStorageInfo: Boolean = true,
    val collectCpuUsage: Boolean = true,
    val maskSensitiveData: Boolean = true,
    val encryptionRequired: Boolean = false
)
