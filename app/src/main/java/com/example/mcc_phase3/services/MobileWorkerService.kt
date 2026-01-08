package com.example.mcc_phase3.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mcc_phase3.R
import com.example.mcc_phase3.communication.WorkerWebSocketClient
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.execution.PythonExecutor
import com.example.mcc_phase3.execution.TaskProcessor
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Service for managing the mobile worker
 * Uses separated architecture:
 * - WorkerWebSocketClient: Handles WebSocket communication
 * - TaskProcessor: Handles task routing and processing
 * - PythonExecutor: Handles only Python code execution via Chaquopy
 */
class MobileWorkerService : Service() {

    companion object {
        private const val TAG = "MobileWorkerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mobile_worker_channel"
        private const val CHANNEL_NAME = "Mobile Worker Service"
        private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }

    private val binder = LocalBinder()
    private val isRunning = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var workerIdManager: WorkerIdManager

    // New architecture components
    private lateinit var webSocketClient: WorkerWebSocketClient
    private lateinit var taskProcessor: TaskProcessor
    private lateinit var pythonExecutor: PythonExecutor

    inner class LocalBinder : Binder() {
        fun getService(): MobileWorkerService = this@MobileWorkerService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== MobileWorkerService Created ===")
        workerIdManager = WorkerIdManager.getInstance(this)
        
        // Create notification channel first
        createNotificationChannel()
        
        // Start as foreground service immediately to prevent being killed
        if (hasNotificationPermission()) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Mobile Worker Service", "Service initializing...")
            )
            Log.d(TAG, "Started as foreground service on onCreate")
        }
        
        // Initialize components
        initializeComponents()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with action: ${intent?.action}")

        when (intent?.action) {
            "START_WORKER" -> {
                val foremanUrl = intent.getStringExtra("foreman_url") ?: "ws://192.168.8.101:9000"
                startWorker(foremanUrl)
            }

            "STOP_WORKER" -> stopWorker()
            "GET_STATUS" -> getWorkerStatus()
            else -> {
                // Service restarted by system or no action specified
                Log.d(TAG, "Service restarted - Worker was running: ${isRunning.get()}")
            }
        }

        // Start as foreground service to prevent Android from killing it
        if (hasNotificationPermission()) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Mobile Worker Service", "Worker is running")
            )
        } else {
            Log.w(TAG, "Cannot start foreground service: POST_NOTIFICATIONS permission not granted")
        }

        // START_STICKY ensures the service is restarted if killed by the system
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopWorker()
        cleanupComponents()
        serviceScope.cancel()
    }

    /**
     * Called when the app is removed from recent tasks
     * Keep the service running if worker is active
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed - Worker running: ${isRunning.get()}")
        
        // Keep service running if worker is active
        if (isRunning.get()) {
            Log.d(TAG, "Restarting service to keep worker alive")
            val restartServiceIntent = Intent(applicationContext, MobileWorkerService::class.java)
            applicationContext.startService(restartServiceIntent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mobile Worker Service Channel"
                setShowBadge(false)
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, com.example.mcc_phase3.MobileWorkerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_worker)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        if (hasNotificationPermission()) {
            val notification = createNotification(title, content)
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } else {
            Log.w(TAG, "Cannot update notification: POST_NOTIFICATIONS permission not granted")
        }
    }

    /**
     * Check if the app has notification permission
     */
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                POST_NOTIFICATIONS_PERMISSION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // For Android 12 and below, notification permission is granted by default
            true
        }
    }

    /**
     * Initialize all components
     */
    private fun initializeComponents() {
        try {
            Log.d(TAG, "Initializing components...")
            
            // Initialize Python executor
            pythonExecutor = PythonExecutor(this)
            
            // Initialize task processor
            taskProcessor = TaskProcessor(this)
            
            // Initialize WebSocket client
            webSocketClient = WorkerWebSocketClient(this)
            
            Log.d(TAG, "✅ All components initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize components", e)
        }
    }

    /**
     * Start the mobile worker
     */
    fun startWorker(foremanUrl: String = "ws://192.168.8.101:9000") {
        if (isRunning.get()) {
            Log.w(TAG, "Worker is already running")
            return
        }

        // Set running status immediately when worker starts
        isRunning.set(true)
        Log.d(TAG, "Starting mobile worker...")
        Log.d(TAG, "Foreman URL: $foremanUrl")

        serviceScope.launch {
            try {
                // Get or generate persistent worker ID
                val workerId = workerIdManager.getOrGenerateWorkerId()
                Log.d(TAG, "Worker ID: $workerId")

                // Connect to WebSocket
                val connected = webSocketClient.connect(foremanUrl)
                
                if (connected) {
                    updateNotification("Mobile Worker Service", "Worker connected: $workerId")
                    Log.d(TAG, "✅ Mobile worker started successfully")
                } else {
                    Log.e(TAG, "❌ Failed to connect to WebSocket")
                    updateNotification("Mobile Worker Service", "Worker running (connecting...)")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start worker", e)
                updateNotification("Mobile Worker Service", "Worker error: ${e.message}")
            }
        }
    }
    /**
     * Stop the mobile worker
     */
    fun stopWorker() {
        if (!isRunning.get()) {
            Log.w(TAG, "Worker is not running")
            return
        }

        try {
            Log.d(TAG, "Stopping mobile worker...")

            // Disconnect WebSocket
            webSocketClient.disconnect()
            
            isRunning.set(false)
            updateNotification("Mobile Worker Service", "Worker stopped")

            Log.d(TAG, "✅ Mobile worker stopped successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to stop worker", e)
        }
    }

    /**
     * Get worker status
     */
    fun getWorkerStatus(): Map<String, Any>? {
        return try {
            val connectionStatus = webSocketClient.getConnectionStatus()
            val taskStatus = taskProcessor.getCurrentTaskStatus()
            val pythonInfo = pythonExecutor.getEnvironmentInfo()
            
            mapOf<String, Any>(
                "is_running" to isRunning.get(),
                "connection" to connectionStatus,
                "task_processor" to taskStatus,
                "python_executor" to pythonInfo,
                "worker_id" to (workerIdManager.getCurrentWorkerId() ?: "unknown")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get worker status", e)
            null
        }
    }

    /**
     * Check if worker is running
     */
    fun isWorkerRunning(): Boolean {
        return isRunning.get()
    }

//    /**
//     * Test worker functionality
//     */
//    fun testWorker() {
//        serviceScope.launch {
//            try {
//                Log.d(TAG, "Testing mobile worker...")
//
//                // Test the mobile worker module directly
//                val testResult = mobileWorkerModule?.callAttr("test_mobile_worker")
//                Log.d(TAG, "Worker test result: $testResult")
//
//                // Convert Python boolean to Kotlin boolean
//                val isSuccess = testResult?.toBoolean() ?: false
//                if (isSuccess) {
//                    Log.d(TAG, "✅ Worker test completed successfully")
//                } else {
//                    Log.e(TAG, "❌ Worker test failed")
//                }
//
//            } catch (e: Exception) {
//                Log.e(TAG, "Worker test failed", e)
//                Log.e(TAG, "Worker test error details: ${e.message}")
//                e.printStackTrace()
//            }
//        }
//    }

    /**
     * Test task execution functionality
     */
    suspend fun testTaskExecution(): Map<String, Any>? {
        return try {
            Log.d(TAG, "Testing task execution...")

            val testResult = taskProcessor.testTaskProcessing()
            Log.d(TAG, "Task execution test result: $testResult")

            testResult
        } catch (e: Exception) {
            Log.e(TAG, "Task execution test failed", e)
            mapOf("error" to e.message.toString())
        }
    }

    /**
     * Get current worker ID
     */
    fun getCurrentWorkerId(): String? {
        return try {
            val workerId = workerIdManager.getCurrentWorkerId()
            Log.d(TAG, "Current worker ID: $workerId")
            workerId
        } catch (e: Exception) {
            Log.e(TAG, "Error getting worker ID", e)
            null
        }
    }

    /**
     * Get worker ID information
     */
    fun getWorkerIdInfo(): Map<String, Any?> {
        return try {
            val info = workerIdManager.getWorkerIdInfo()
            mapOf(
                "workerId" to info.workerId,
                "deviceId" to info.deviceId,
                "generatedAt" to info.generatedAt,
                "hasWorkerId" to info.hasWorkerId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting worker ID info", e)
            mapOf("error" to e.message)
        }
    }

    /**
     * Get worker statistics
     */
    fun getWorkerStats(): Map<String, Any>? {
        return try {
            val status = getWorkerStatus()
            status?.get("stats") as? Map<String, Any>
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get worker stats", e)
            null
        }
    }

    /**
     * Get mobile device info
     */
    fun getMobileInfo(): Map<String, Any>? {
        return try {
            val pythonInfo = pythonExecutor.getEnvironmentInfo()
            val connectionStatus = webSocketClient.getConnectionStatus()
            
            mapOf<String, Any>(
                "platform" to "android",
                "python_executor" to pythonInfo,
                "connection" to connectionStatus,
                "worker_id" to (workerIdManager.getCurrentWorkerId() ?: "unknown"),
                "service_running" to isRunning.get()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mobile info", e)
            null
        }
    }
    
    /**
     * Send immediate heartbeat for testing
     */
    fun sendImmediateHeartbeat() {
        try {
            webSocketClient.sendImmediateHeartbeat()
            Log.d(TAG, "Immediate heartbeat sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send immediate heartbeat", e)
        }
    }
    
    /**
     * Test complete task flow
     */
    suspend fun testCompleteTaskFlow(): Map<String, Any>? {
        return try {
            Log.d(TAG, "Testing complete task flow...")
            
            val webSocketTest = webSocketClient.testWebSocket()
            val taskProcessorTest = taskProcessor.testTaskProcessing()
            
            val isSuccess = webSocketTest["status"] == "success" && 
                           taskProcessorTest["status"] == "success"
            
            mapOf<String, Any>(
                "status" to if (isSuccess) "success" else "error",
                "message" to if (isSuccess) "Complete task flow test passed" else "Complete task flow test failed",
                "websocket_test" to webSocketTest,
                "task_processor_test" to taskProcessorTest
            )
        } catch (e: Exception) {
            Log.e(TAG, "Complete task flow test failed", e)
            mapOf<String, Any>(
                "status" to "error",
                "message" to "Complete task flow test failed: ${e.message ?: "Unknown error"}",
                "error" to e.toString()
            )
        }
    }
    
    /**
     * Cleanup all components
     */
    private fun cleanupComponents() {
        try {
            Log.d(TAG, "Cleaning up components...")
            webSocketClient.cleanup()
            taskProcessor.cleanup()
            pythonExecutor.cleanup()
            Log.d(TAG, "✅ All components cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
