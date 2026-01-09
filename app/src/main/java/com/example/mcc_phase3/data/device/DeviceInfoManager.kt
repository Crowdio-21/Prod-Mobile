package com.example.mcc_phase3.data.device

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.mcc_phase3.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.net.NetworkInterface
import java.util.*

/**
 * Comprehensive device information manager with security considerations
 */
class DeviceInfoManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceInfoManager"
        private val DEFAULT_SECURITY_LEVEL = SecurityLevel.STANDARD
    }
    
    private val activityManager: ActivityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val telephonyManager: TelephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val batteryManager: BatteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    
    private var securityConfig: SecurityConfig = SecurityConfig()
    private var lastBatteryInfo: BatteryInfo? = null
    private var lastRamInfo: RamInfo? = null
    private var lastStorageInfo: StorageInfo? = null
    private var lastDeviceInfo: DeviceInfo? = null
    private var lastNetworkInfo: NetworkInfo? = null
    
    /**
     * Set security configuration for data collection
     */
    fun setSecurityConfig(config: SecurityConfig) {
        this.securityConfig = config
        Log.d(TAG, "Security config updated: $config")
    }
    
    /**
     * Get comprehensive device metrics
     */
    suspend fun getDeviceMetrics(): DeviceMetrics = withContext(Dispatchers.IO) {
        Log.d(TAG, "📊 Collecting comprehensive device metrics")
        
        try {
            val batteryInfo = getBatteryInfo()
            val ramInfo = getRamInfo()
            val storageInfo = getStorageInfo()
            val deviceInfo = getDeviceInfo()
            val networkInfo = getNetworkInfo()
            
            val metrics = DeviceMetrics(
                batteryInfo = batteryInfo,
                ramInfo = ramInfo,
                storageInfo = storageInfo,
                deviceInfo = deviceInfo,
                networkInfo = networkInfo,
                securityLevel = DEFAULT_SECURITY_LEVEL
            )
            
            Log.d(TAG, "✅ Device metrics collected successfully")
            Log.v(TAG, "📊 Metrics summary: Battery=${batteryInfo.level}%, RAM=${ramInfo.ramUsagePercentage}%, Storage=${storageInfo.internalStorage.usagePercentage}%")
            
            metrics
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to collect device metrics", e)
            throw e
        }
    }
    
    /**
     * Get detailed battery information
     */
    suspend fun getBatteryInfo(): BatteryInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔋 Collecting battery information")
        
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryLevel = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else -1
            
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            
            val chargeStatus = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> ChargeStatus.CHARGING
                BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeStatus.DISCHARGING
                BatteryManager.BATTERY_STATUS_FULL -> ChargeStatus.FULL
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeStatus.NOT_CHARGING
                else -> ChargeStatus.UNKNOWN
            }
            
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val powerSource = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> PowerSource.AC
                BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.USB
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> PowerSource.WIRELESS
                else -> PowerSource.BATTERY
            }
            
            val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
            val batteryHealth = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
                BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.UNSPECIFIED_FAILURE
                BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
                else -> BatteryHealth.UNKNOWN
            }
            
            val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let { it / 10f } ?: -1f
            val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.toFloat() ?: -1f
            val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"
            
            // Advanced battery metrics (API 21+)
            val capacity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            } else -1L
            
            val currentNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            } else -1L
            
            val chargeCounter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            } else -1L
            
            val fullChargeCounter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            } else -1L
            
            val batteryInfo = BatteryInfo(
                level = batteryLevel,
                isCharging = isCharging,
                chargeStatus = chargeStatus,
                powerSource = powerSource,
                health = batteryHealth,
                temperature = temperature,
                voltage = voltage,
                technology = technology,
                capacity = capacity,
                currentNow = currentNow,
                chargeCounter = chargeCounter,
                fullChargeCounter = fullChargeCounter
            )
            
            lastBatteryInfo = batteryInfo
            Log.d(TAG, "✅ Battery info collected: ${batteryInfo.level}%, ${batteryInfo.chargeStatus}, ${batteryInfo.powerSource}")
            
            batteryInfo
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to collect battery information", e)
            throw e
        }
    }
    
    /**
     * Get RAM information
     */
    suspend fun getRamInfo(): RamInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "💾 Collecting RAM information")
        
        try {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            val totalRam = memoryInfo.totalMem
            val availableRam = memoryInfo.availMem
            val usedRam = totalRam - availableRam
            val freeRam = memoryInfo.threshold
            val threshold = memoryInfo.threshold
            val isLowMemory = memoryInfo.lowMemory
            val ramUsagePercentage = (usedRam.toFloat() / totalRam.toFloat()) * 100
            
            val ramInfo = RamInfo(
                totalRam = totalRam,
                availableRam = availableRam,
                usedRam = usedRam,
                freeRam = freeRam,
                threshold = threshold,
                isLowMemory = isLowMemory,
                ramUsagePercentage = ramUsagePercentage
            )
            
            lastRamInfo = ramInfo
            Log.d(TAG, "✅ RAM info collected: ${ramInfo.ramUsagePercentage}% used, ${ramInfo.availableRam / (1024 * 1024)}MB available")
            
            ramInfo
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to collect RAM information", e)
            throw e
        }
    }
    
    /**
     * Get storage information
     */
    suspend fun getStorageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "💿 Collecting storage information")
        
        try {
            // Internal storage
            val internalStorage = getStorageDetails(Environment.getDataDirectory())
            
            // External storage
            val externalStorage = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                getStorageDetails(Environment.getExternalStorageDirectory())
            } else null
            
            val storageInfo = StorageInfo(
                internalStorage = internalStorage,
                externalStorage = externalStorage
            )
            
            lastStorageInfo = storageInfo
            Log.d(TAG, "✅ Storage info collected: Internal=${internalStorage.usagePercentage}%, External=${externalStorage?.usagePercentage ?: "N/A"}%")
            
            storageInfo
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to collect storage information", e)
            throw e
        }
    }
    
    /**
     * Get detailed storage information for a specific path
     */
    private fun getStorageDetails(path: File): StorageDetails {
        val statFs = StatFs(path.path)
        val blockSize = statFs.blockSizeLong
        val totalBlocks = statFs.blockCountLong
        val availableBlocks = statFs.availableBlocksLong
        val usedBlocks = totalBlocks - availableBlocks
        
        val totalSpace = totalBlocks * blockSize
        val availableSpace = availableBlocks * blockSize
        val usedSpace = usedBlocks * blockSize
        val freeSpace = availableSpace
        val usagePercentage = (usedSpace.toFloat() / totalSpace.toFloat()) * 100
        
        return StorageDetails(
            totalSpace = totalSpace,
            availableSpace = availableSpace,
            usedSpace = usedSpace,
            freeSpace = freeSpace,
            usagePercentage = usagePercentage,
            isReadOnly = !path.canWrite(),
            isRemovable = try {
                Environment.isExternalStorageRemovable(path)
            } catch (e: IllegalArgumentException) {
                // Internal storage is not removable
                false
            },
            path = path.path
        )
    }
    
    /**
     * Get device information
     */
    suspend fun getDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "📱 Collecting device information")
        
        try {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            val brand = Build.BRAND
            val product = Build.PRODUCT
            val device = Build.DEVICE
            val hardware = Build.HARDWARE
            val androidVersion = Build.VERSION.RELEASE
            val sdkLevel = Build.VERSION.SDK_INT
            val buildNumber = Build.DISPLAY
            val fingerprint = Build.FINGERPRINT
            
            // Device identifier (using alternative approach instead of Build.getSerial())
            val deviceIdentifier = if (securityConfig.collectSerialNumber) {
                getDeviceIdentifier()
            } else null
            
            // Generate unique device ID (non-sensitive)
            val uniqueId = generateUniqueDeviceId()
            
            // CPU information
            val cpuInfo = getCpuInfo()
            
            val deviceInfo = DeviceInfo(
                manufacturer = manufacturer,
                model = model,
                brand = brand,
                product = product,
                device = device,
                hardware = hardware,
                androidVersion = androidVersion,
                sdkLevel = sdkLevel,
                buildNumber = buildNumber,
                fingerprint = fingerprint,
                serialNumber = deviceIdentifier,
                uniqueId = uniqueId,
                cpuInfo = cpuInfo
            )
            
            lastDeviceInfo = deviceInfo
            Log.d(TAG, "✅ Device info collected: $manufacturer $model, Android $androidVersion")
            
            deviceInfo
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to collect device information", e)
            throw e
        }
    }
    
    /**
     * Get CPU information
     */
    private fun getCpuInfo(): CpuInfo {
        val architecture = System.getProperty("os.arch") ?: "Unknown"
        val cores = Runtime.getRuntime().availableProcessors()
        
        // CPU frequency information
        var maxFrequency = 0L
        var currentFrequency = 0L
         
         try {
             val maxFreqFile = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
             if (maxFreqFile.exists() && maxFreqFile.canRead()) {
                 val reader = BufferedReader(FileReader(maxFreqFile))
                 maxFrequency = reader.readLine().toLong() * 1000 // Convert kHz to Hz
                 reader.close()
             }
             
             val currentFreqFile = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
             if (currentFreqFile.exists() && currentFreqFile.canRead()) {
                 val currentReader = BufferedReader(FileReader(currentFreqFile))
                 currentFrequency = currentReader.readLine().toLong() * 1000 // Convert kHz to Hz
                 currentReader.close()
             }
         } catch (e: SecurityException) {
             Log.w(TAG, "⚠️ SecurityException when reading CPU frequency: ${e.message}")
         } catch (e: IOException) {
             Log.w(TAG, "⚠️ Could not read CPU frequency information: ${e.message}")
         } catch (e: Exception) {
             Log.w(TAG, "⚠️ Exception when reading CPU frequency: ${e.message}")
         }
        
        // CPU usage calculation (simplified)
        val cpuUsage = if (securityConfig.collectCpuUsage) {
            calculateCpuUsage()
        } else 0f
        
        return CpuInfo(
            architecture = architecture,
            cores = cores,
            maxFrequency = maxFrequency,
            currentFrequency = currentFrequency,
            cpuUsage = cpuUsage
        )
    }
    
    /**
     * Calculate CPU usage percentage
     */
     private fun calculateCpuUsage(): Float {
         return try {
             val statFile = File("/proc/stat")
             if (statFile.exists() && statFile.canRead()) {
                 val reader = BufferedReader(FileReader(statFile))
                 val line = reader.readLine()
                 reader.close()
                 
                 val parts = line.split("\\s+".toRegex())
                 if (parts.size >= 5) {
                     val user = parts[1].toLong()
                     val nice = parts[2].toLong()
                     val system = parts[3].toLong()
                     val idle = parts[4].toLong()
                     
                     val total = user + nice + system + idle
                     val nonIdle = user + nice + system
                     
                     (nonIdle.toFloat() / total.toFloat()) * 100
                 } else 0f
             } else {
                 Log.w(TAG, "⚠️ Cannot read /proc/stat file")
                 0f
             }
         } catch (e: SecurityException) {
             Log.w(TAG, "⚠️ SecurityException when calculating CPU usage: ${e.message}")
             0f
         } catch (e: Exception) {
             Log.w(TAG, "⚠️ Could not calculate CPU usage: ${e.message}")
             0f
         }
     }
    
    /**
     * Get network information
     */
    suspend fun getNetworkInfo(): NetworkInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "🌐 Collecting network information")
        
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetworkInfo
            
            val isConnected = activeNetwork?.isConnected == true
            val connectionType = when (activeNetwork?.type) {
                android.net.ConnectivityManager.TYPE_WIFI -> ConnectionType.WIFI
                android.net.ConnectivityManager.TYPE_MOBILE -> ConnectionType.MOBILE
                android.net.ConnectivityManager.TYPE_ETHERNET -> ConnectionType.ETHERNET
                android.net.ConnectivityManager.TYPE_BLUETOOTH -> ConnectionType.BLUETOOTH
                android.net.ConnectivityManager.TYPE_VPN -> ConnectionType.VPN
                else -> ConnectionType.UNKNOWN
            }
            
            val networkType = activeNetwork?.typeName ?: "Unknown"
            
            // Signal strength (for mobile networks)
            val signalStrength = if (connectionType == ConnectionType.MOBILE) {
                getSignalStrength()
            } else null
            
            // IP address
            val ipAddress = if (securityConfig.collectIpAddress) {
                getLocalIpAddress()
            } else null
            
            val networkInfo = NetworkInfo(
                isConnected = isConnected,
                connectionType = connectionType,
                networkType = networkType,
                signalStrength = signalStrength,
                ipAddress = ipAddress
            )
            
            lastNetworkInfo = networkInfo
            Log.d(TAG, "✅ Network info collected: ${networkInfo.connectionType}, Connected: ${networkInfo.isConnected}")
            
            networkInfo
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to collect network information", e)
            throw e
        }
    }
    
    /**
     * Get local IP address
     */
    private fun getLocalIpAddress(): String? {
        return try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                val inetAddresses = networkInterface.inetAddresses
                
                while (inetAddresses.hasMoreElements()) {
                    val inetAddress = inetAddresses.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress.hostAddress.indexOf(':') < 0) {
                        return inetAddress.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not get IP address: ${e.message}")
            null
        }
    }
    
    /**
     * Generate unique device identifier (non-sensitive)
     */
    private fun generateUniqueDeviceId(): String {
        val deviceId = "${Build.MANUFACTURER}_${Build.MODEL}_${Build.VERSION.SDK_INT}_${Build.FINGERPRINT.hashCode()}"
        return deviceId.replace(" ", "_").replace("[^a-zA-Z0-9_-]".toRegex(), "")
    }
    
    /**
     * Get cached device metrics (if available)
     */
    fun getCachedDeviceMetrics(): DeviceMetrics? {
        return if (lastBatteryInfo != null && lastRamInfo != null && lastStorageInfo != null && 
                   lastDeviceInfo != null && lastNetworkInfo != null) {
            DeviceMetrics(
                batteryInfo = lastBatteryInfo!!,
                ramInfo = lastRamInfo!!,
                storageInfo = lastStorageInfo!!,
                deviceInfo = lastDeviceInfo!!,
                networkInfo = lastNetworkInfo!!,
                securityLevel = DEFAULT_SECURITY_LEVEL
            )
        } else null
    }
    
    /**
     * Get signal strength with proper permission handling
     */
    private fun getSignalStrength(): Int? {
        return try {
            // Check if we have both required permissions
            val hasPhoneStatePermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE)
            val hasLocationPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
            
            if (hasPhoneStatePermission == PackageManager.PERMISSION_GRANTED && hasLocationPermission == PackageManager.PERMISSION_GRANTED) {
                 val signalInfo = telephonyManager.allCellInfo?.firstOrNull()
                 when (signalInfo) {
                     is android.telephony.CellInfoGsm -> signalInfo.cellSignalStrength.level
                     is android.telephony.CellInfoCdma -> signalInfo.cellSignalStrength.level
                     is android.telephony.CellInfoLte -> signalInfo.cellSignalStrength.level
                     is android.telephony.CellInfoWcdma -> signalInfo.cellSignalStrength.level
                     else -> null
                 }
            } else {
                if (hasPhoneStatePermission != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "⚠️ READ_PHONE_STATE permission not granted for signal strength")
                }
                if (hasLocationPermission != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "⚠️ ACCESS_FINE_LOCATION permission not granted for signal strength")
                }
                null
            }
         } catch (e: SecurityException) {
             Log.w(TAG, "⚠️ SecurityException when accessing signal strength: ${e.message}")
             null
         } catch (e: Exception) {
             Log.w(TAG, "⚠️ Exception when accessing signal strength: ${e.message}")
             null
         }
     }
     
    /**
     * Get device identifier using alternative approach (no Build.getSerial())
     */
    private fun getDeviceIdentifier(): String? {
        return try {
            // Use a combination of device properties that don't require privileged permissions
            val deviceId = StringBuilder()
             
             // Add manufacturer and model
             deviceId.append(Build.MANUFACTURER).append("_")
             deviceId.append(Build.MODEL).append("_")
             
             // Add Android version and SDK level
             deviceId.append(Build.VERSION.RELEASE).append("_")
             deviceId.append(Build.VERSION.SDK_INT).append("_")
             
             // Add hardware info
             deviceId.append(Build.HARDWARE).append("_")
             
             // Add fingerprint hash (already available)
             deviceId.append(Build.FINGERPRINT.hashCode())
             
             val identifier = deviceId.toString()
             Log.d(TAG, "✅ Device identifier generated: ${identifier.take(20)}...")
             
             identifier
         } catch (e: Exception) {
             Log.w(TAG, "⚠️ Exception when generating device identifier: ${e.message}")
             null
         }
     }
     
    /**
     * Clear cached data
     */
    fun clearCache() {
        lastBatteryInfo = null
        lastRamInfo = null
        lastStorageInfo = null
        lastDeviceInfo = null
        lastNetworkInfo = null
        Log.d(TAG, "🗑️ Device info cache cleared")
    }
 }
