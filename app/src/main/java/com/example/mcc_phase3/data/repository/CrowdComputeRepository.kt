package com.example.mcc_phase3.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.example.mcc_phase3.data.api.ApiClient
import com.example.mcc_phase3.data.api.ApiService
import com.example.mcc_phase3.data.models.*
import com.example.mcc_phase3.data.websocket.WebSocketManager
import com.example.mcc_phase3.data.ConfigManager
import com.example.mcc_phase3.data.device.DeviceInfoManager
import com.example.mcc_phase3.services.MobileWorkerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import retrofit2.HttpException
import com.example.mcc_phase3.data.websocket.WebSocketManager.WebSocketListener
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException
import java.io.IOException

class CrowdComputeRepository(private val context: android.content.Context) {
    
    companion object {
        private const val TAG = "CrowdComputeRepository"
        private const val CIRCUIT_BREAKER_THRESHOLD = 5  // Increased from 3 to 5
        private const val CIRCUIT_BREAKER_TIMEOUT_MS = 60000L // Increased from 30s to 60s
        private const val CIRCUIT_BREAKER_SUCCESS_THRESHOLD = 2 // Number of successes needed to close circuit
    }
    
    private val apiService: ApiService = ApiClient.getApiService(context)
    private val webSocketManager = WebSocketManager.getInstance() // ✅ Use singleton instance
    private val deviceInfoManager = DeviceInfoManager(context) // Device information manager
    private val configManager = ConfigManager.getInstance(context) // Configuration manager
    
    // Mobile worker service connection
    private var mobileWorkerService: MobileWorkerService? = null
    private var isServiceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MobileWorkerService.LocalBinder
            mobileWorkerService = binder?.getService()
            isServiceBound = true
            Log.d(TAG, "✅ Connected to MobileWorkerService")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            mobileWorkerService = null
            isServiceBound = false
            Log.d(TAG, "❌ Disconnected from MobileWorkerService")
        }
    }
    
    // Circuit breaker state
    private var failureCount = 0
    private var successCount = 0
    private var lastFailureTime = 0L
    private var isCircuitOpen = false

    init {
        Log.d(TAG, "=== CrowdComputeRepository Initialized ===")
        Log.d(TAG, "ApiService: ${apiService.javaClass.simpleName}")
        Log.d(TAG, "WebSocketManager: ${webSocketManager.javaClass.simpleName}")
        Log.d(TAG, "Base URL: ${ApiClient.getBaseUrl(context)}")
        
        // Try to bind to MobileWorkerService if it's running
        tryBindToWorkerService()
    }
    
    private fun tryBindToWorkerService() {
        try {
            val intent = Intent(context, MobileWorkerService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "Attempting to bind to MobileWorkerService")
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind to MobileWorkerService: ${e.message}")
        }
    }

    init {
        Log.d(TAG, "=== CrowdComputeRepository Initialized ===")
        Log.d(TAG, "ApiService: ${apiService.javaClass.simpleName}")
        Log.d(TAG, "WebSocketManager: ${webSocketManager.javaClass.simpleName}")
        Log.d(TAG, "Base URL: ${ApiClient.getBaseUrl(context)}")
    }

    suspend fun getStats(): Result<Stats> = withContext(Dispatchers.IO) {
        Log.d(TAG, "📊 getStats() called")
        ApiClient.logApiCall(context, "/api/stats")

        try {
            checkCircuitBreaker()
            
            val startTime = System.currentTimeMillis()
            val stats = apiService.getStats()
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ getStats() successful in ${duration}ms")
            Log.d(TAG, "📊 Stats data: totalJobs=${stats.totalJobs}, totalTasks=${stats.totalTasks}, totalWorkers=${stats.totalWorkers}")
            ApiClient.logApiSuccess("/api/stats", 200)
            
            resetCircuitBreakerOnSuccess()
            Result.success(stats)
        } catch (e: Exception) {
            handleApiError(e, "getStats")
        }
    }

    suspend fun getJobs(skip: Int = 0, limit: Int = 100): Result<List<Job>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "💼 getJobs() called with skip=$skip, limit=$limit")
        ApiClient.logApiCall(context, "/api/jobs?skip=$skip&limit=$limit")

        try {
            checkCircuitBreaker()
            
            val startTime = System.currentTimeMillis()
            val jobs = apiService.getJobs(skip, limit)
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ getJobs() successful in ${duration}ms")
            Log.d(TAG, "💼 Retrieved ${jobs.size} jobs")
            jobs.forEachIndexed { index, job ->
                Log.v(TAG, "💼 Job[$index]: id=${job.id}, status=${job.status}, progress=${job.completedTasks}/${job.totalTasks}")
            }
            ApiClient.logApiSuccess("/api/jobs", 200, jobs.size)
            
            resetCircuitBreakerOnSuccess()
            Result.success(jobs)
        } catch (e: Exception) {
            handleApiError(e, "getJobs")
        }
    }
    
    private fun checkNetworkConnectivity() {
        try {
            val configManager = ConfigManager.getInstance(context)
            val foremanUrl = configManager.getForemanHttpURL()
            if (foremanUrl == null) {
                Log.w(TAG, "⚠️ Cannot check connectivity: Foreman IP not configured")
                return
            }
            Log.d(TAG, "🌐 Checking connectivity to: $foremanUrl")
            
            // Try a simple ping test
            val runtime = Runtime.getRuntime()
            val process = runtime.exec("ping -c 1 ${configManager.getForemanIP()}")
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Log.d(TAG, "✅ Network connectivity OK")
            } else {
                Log.w(TAG, "⚠️ Network connectivity issue - ping failed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not check network connectivity: ${e.message}")
        }
    }
    
    private fun checkCircuitBreaker() {
        if (isCircuitOpen) {
            val timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime
            if (timeSinceLastFailure < CIRCUIT_BREAKER_TIMEOUT_MS) {
                Log.w(TAG, "🚫 Circuit breaker is OPEN, request blocked (${timeSinceLastFailure}ms since last failure)")
                val waitTime = (CIRCUIT_BREAKER_TIMEOUT_MS - timeSinceLastFailure) / 1000
                val foremanIp = configManager.getForemanIP()
                val configMessage = if (foremanIp.isEmpty()) {
                    "\n💡 TIP: Configure your Foreman IP in Settings first!"
                } else {
                    "\n💡 TIP: Verify Foreman at $foremanIp is running and accessible."
                }
                throw IOException("Circuit breaker is open - too many recent failures. Try again in ${waitTime}s.$configMessage")
            } else {
                Log.d(TAG, "🔄 Circuit breaker timeout reached, attempting to close")
                isCircuitOpen = false
                failureCount = 0
                successCount = 0
            }
        }
    }
    
    /**
     * Manually reset the circuit breaker
     * Call this when configuration changes (e.g., new IP address set)
     */
    fun manuallyResetCircuitBreaker() {
        Log.d(TAG, "🔄 Manually resetting circuit breaker")
        failureCount = 0
        successCount = 0
        lastFailureTime = 0L
        isCircuitOpen = false
    }
    
    private fun resetCircuitBreakerOnSuccess() {
        successCount++
        if (successCount >= CIRCUIT_BREAKER_SUCCESS_THRESHOLD) {
            Log.d(TAG, "✅ Circuit breaker reset after $successCount successful requests")
            failureCount = 0
            successCount = 0
            isCircuitOpen = false
        }
    }
    
    private fun handleApiError(e: Exception, operation: String): Result<Nothing> {
        Log.e(TAG, "❌ $operation() failed", e)
        ApiClient.logApiError(context, "/api/$operation", e)
        
        // Update circuit breaker state
        failureCount++
        successCount = 0 // Reset success count on failure
        lastFailureTime = System.currentTimeMillis()
        
        if (failureCount >= CIRCUIT_BREAKER_THRESHOLD) {
            isCircuitOpen = true
            Log.w(TAG, "🚫 Circuit breaker opened after $failureCount failures")
        }
        
        // Provide specific error messages based on exception type
        val errorMessage = when (e) {
            is SocketTimeoutException -> "Request timed out. Please check your network connection."
            is ConnectException -> "Cannot connect to server. Please check if the server is running."
            is UnknownHostException -> "Cannot resolve server address. Please check your network configuration."
            is IOException -> "Network communication failed. Please check your internet connection."
            is HttpException -> "Server error (${e.code()}). Please try again later."
            else -> "An unexpected error occurred: ${e.message}"
        }
        
        Log.e(TAG, "💡 Error details: $errorMessage")
        return Result.failure(e)
    }

    suspend fun getJob(jobId: String): Result<Job> = withContext(Dispatchers.IO) {
        Log.d(TAG, "💼 getJob() called for jobId=$jobId")
        ApiClient.logApiCall(context, "/api/jobs/$jobId")

        try {
            checkCircuitBreaker()
            
            val startTime = System.currentTimeMillis()
            val job = apiService.getJob(jobId)
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ getJob() successful in ${duration}ms")
            Log.d(TAG, "💼 Job details: id=${job.id}, status=${job.status}, progress=${job.completedTasks}/${job.totalTasks}")
            ApiClient.logApiSuccess("/api/jobs/$jobId", 200)
            
            resetCircuitBreakerOnSuccess()
            Result.success(job)
        } catch (e: Exception) {
            handleApiError(e, "getJob")
        }
    }

    suspend fun getWorkers(): Result<List<Worker>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "👷 getWorkers() called")
        ApiClient.logApiCall(context, "/api/workers")

        try {
            checkCircuitBreaker()
            
            val startTime = System.currentTimeMillis()
            val workers = apiService.getWorkers()
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ getWorkers() successful in ${duration}ms")
            Log.d(TAG, "👷 Retrieved ${workers.size} workers")

            val onlineCount = workers.count { it.status == "online" }
            val offlineCount = workers.size - onlineCount
            Log.d(TAG, "👷 Worker status: $onlineCount online, $offlineCount offline")

            workers.forEachIndexed { index, worker ->
                Log.v(TAG, "👷 Worker[$index]: id=${worker.id}, status=${worker.status}, task=${worker.currentTaskId}")
            }
            ApiClient.logApiSuccess("/api/workers", 200, workers.size)
            
            resetCircuitBreakerOnSuccess()
            Result.success(workers)
        } catch (e: Exception) {
            handleApiError(e, "getWorkers")
        }
    }

//    suspend fun getActivity(): Result<List<Activity>> = withContext(Dispatchers.IO) {
//        Log.d(TAG, "📈 getActivity() called")
//        ApiClient.logApiCall(context, "/api/activity")
//
//        try {
//            checkCircuitBreaker()
//
//            val startTime = System.currentTimeMillis()
//            val activity = apiService.getActivity()
//            val duration = System.currentTimeMillis() - startTime
//
//            Log.d(TAG, "✅ getActivity() successful in ${duration}ms")
//            Log.d(TAG, "📈 Retrieved ${activity.size} activities")
//            activity.forEachIndexed { index, act ->
//                Log.v(TAG, "📈 Activity[$index]: type=${act.type}, action=${act.action}, details=${act.details}")
//            }
//            ApiClient.logApiSuccess("/api/activity", 200, activity.size)
//
//            resetCircuitBreaker()
//            Result.success(activity)
//        } catch (e: Exception) {
//            handleApiError(e, "getActivity")
//        }
//    }

    suspend fun getWebsocketStats(): Result<WebsocketStats> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔌 getWebsocketStats() called")
        ApiClient.logApiCall(context, "/api/websocket-stats")

        try {
            checkCircuitBreaker()
            
            val startTime = System.currentTimeMillis()
            val stats = apiService.getWebsocketStats()
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ getWebsocketStats() successful in ${duration}ms")
            Log.d(TAG, "🔌 WebSocket stats: connected=${stats.connectedWorkers}, available=${stats.availableWorkers}")
            ApiClient.logApiSuccess("/api/websocket-stats", 200)
            
            resetCircuitBreakerOnSuccess()
            Result.success(stats)
        } catch (e: Exception) {
            handleApiError(e, "getWebsocketStats")
        }
    }

    fun connectWebSocket(url: String) {
        Log.d(TAG, "🔌 connectWebSocket() called with URL: $url")
        webSocketManager.connect(url)
    }

    fun disconnectWebSocket() {
        Log.d(TAG, "🔌 disconnectWebSocket() called")
        webSocketManager.disconnect()
    }

    fun isWebSocketConnected(): Boolean {
        // Check if mobile worker service is connected (higher priority)
        val workerConnected = mobileWorkerService?.isWorkerConnected() ?: false
        if (workerConnected) {
            Log.v(TAG, "🔌 isWebSocketConnected() - Mobile worker is connected: true")
            return true
        }
        
        // Fall back to WebSocketManager (dashboard monitoring connection)
        val dashboardConnected = webSocketManager.isConnected()
        Log.v(TAG, "🔌 isWebSocketConnected() - Dashboard connection: $dashboardConnected, Worker connection: $workerConnected")
        return dashboardConnected
    }

    fun addWebSocketListener(listener: WebSocketListener) {
        Log.d(TAG, "👂 addWebSocketListener() called: ${listener.javaClass.simpleName}")
        webSocketManager.addListener(listener)
    }

    fun removeWebSocketListener(listener: WebSocketListener) {
        Log.d(TAG, "👂 removeWebSocketListener() called: ${listener.javaClass.simpleName}")
        webSocketManager.removeListener(listener)
    }

    fun sendWebSocketMessage(message: String) {
        Log.d(TAG, "📤 sendWebSocketMessage() called (${message.length} chars)")
        webSocketManager.sendMessage(message)
    }

    fun getConnectionSummary(): String {
        val wsConnected = webSocketManager.isConnected()
        val circuitStatus = if (isCircuitOpen) "OPEN" else "CLOSED"
        val summary = "Repository - API: ${ApiClient.getBaseUrl(context)}, WebSocket: ${if (wsConnected) "Connected" else "Disconnected"}, Circuit: $circuitStatus"
        Log.d(TAG, "📋 getConnectionSummary(): $summary")
        return summary
    }
    
    /**
     * Get circuit breaker status for debugging
     */
    fun getCircuitBreakerStatus(): String {
        return "Circuit Breaker: ${if (isCircuitOpen) "OPEN" else "CLOSED"}, Failures: $failureCount, Successes: $successCount, Last Failure: ${if (lastFailureTime > 0) "${System.currentTimeMillis() - lastFailureTime}ms ago" else "Never"}"
    }
    
    /**
     * Manual reset of circuit breaker for testing/debugging
     */
    fun resetCircuitBreakerManually() {
        Log.d(TAG, "🔧 Manual circuit breaker reset")
        failureCount = 0
        successCount = 0
        isCircuitOpen = false
        lastFailureTime = 0L
    }
    
    // ==================== DEVICE INFORMATION METHODS ====================
    
    /**
     * Get comprehensive device metrics
     */
    suspend fun getDeviceMetrics(): Result<DeviceMetrics> = withContext(Dispatchers.IO) {
        Log.d(TAG, "📊 getDeviceMetrics() called")
        
        try {
            val metrics = deviceInfoManager.getDeviceMetrics()
            Log.d(TAG, "✅ Device metrics collected successfully")
            Log.d(TAG, "📊 Device: ${metrics.deviceInfo.manufacturer} ${metrics.deviceInfo.model}")
            Log.d(TAG, "🔋 Battery: ${metrics.batteryInfo.level}%, ${metrics.batteryInfo.chargeStatus}")
            Log.d(TAG, "💾 RAM: ${metrics.ramInfo.ramUsagePercentage}% used")
            Log.d(TAG, "💿 Storage: ${metrics.storageInfo.internalStorage.usagePercentage}% used")
            
            Result.success(metrics)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get device metrics", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get battery information
     */
    suspend fun getBatteryInfo(): Result<BatteryInfo> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔋 getBatteryInfo() called")
        
        try {
            val batteryInfo = deviceInfoManager.getBatteryInfo()
            Log.d(TAG, "✅ Battery info collected: ${batteryInfo.level}%, ${batteryInfo.chargeStatus}")
            Result.success(batteryInfo)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get battery info", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get RAM information
     */
    suspend fun getRamInfo(): Result<RamInfo> = withContext(Dispatchers.IO) {
        Log.d(TAG, "💾 getRamInfo() called")
        
        try {
            val ramInfo = deviceInfoManager.getRamInfo()
            Log.d(TAG, "✅ RAM info collected: ${ramInfo.ramUsagePercentage}% used")
            Result.success(ramInfo)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get RAM info", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get storage information
     */
    suspend fun getStorageInfo(): Result<StorageInfo> = withContext(Dispatchers.IO) {
        Log.d(TAG, "💿 getStorageInfo() called")
        
        try {
            val storageInfo = deviceInfoManager.getStorageInfo()
            Log.d(TAG, "✅ Storage info collected: Internal=${storageInfo.internalStorage.usagePercentage}%")
            Result.success(storageInfo)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get storage info", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get device information
     */
    suspend fun getDeviceInfo(): Result<DeviceInfo> = withContext(Dispatchers.IO) {
        Log.d(TAG, "📱 getDeviceInfo() called")
        
        try {
            val deviceInfo = deviceInfoManager.getDeviceInfo()
            Log.d(TAG, "✅ Device info collected: ${deviceInfo.manufacturer} ${deviceInfo.model}")
            Result.success(deviceInfo)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get device info", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get network information
     */
    suspend fun getNetworkInfo(): Result<NetworkInfo> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🌐 getNetworkInfo() called")
        
        try {
            val networkInfo = deviceInfoManager.getNetworkInfo()
            Log.d(TAG, "✅ Network info collected: ${networkInfo.connectionType}")
            Result.success(networkInfo)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get network info", e)
            Result.failure(e)
        }
    }
    
    /**
     * Set security configuration for device information collection
     */
    fun setDeviceInfoSecurityConfig(config: SecurityConfig) {
        Log.d(TAG, "🔒 Setting device info security config: $config")
        deviceInfoManager.setSecurityConfig(config)
    }
    
    /**
     * Get cached device metrics (if available)
     */
    fun getCachedDeviceMetrics(): DeviceMetrics? {
        return deviceInfoManager.getCachedDeviceMetrics()
    }
    
    /**
     * Clear device information cache
     */
    fun clearDeviceInfoCache() {
        Log.d(TAG, "🗑️ Clearing device info cache")
        deviceInfoManager.clearCache()
    }
    
    /**
     * Get device information summary for logging
     */
    fun getDeviceInfoSummary(): String {
        val cachedMetrics = getCachedDeviceMetrics()
        return if (cachedMetrics != null) {
            "Device: ${cachedMetrics.deviceInfo.manufacturer} ${cachedMetrics.deviceInfo.model}, " +
            "Battery: ${cachedMetrics.batteryInfo.level}%, " +
            "RAM: ${cachedMetrics.ramInfo.ramUsagePercentage}%, " +
            "Storage: ${cachedMetrics.storageInfo.internalStorage.usagePercentage}%, " +
            "Network: ${cachedMetrics.networkInfo.connectionType}"
        } else {
            "Device metrics not available"
        }
    }
}