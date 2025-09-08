package com.example.mcc_phase3.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.mcc_phase3.R
import com.example.mcc_phase3.data.WorkerIdManager
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Service for managing the mobile worker
 * Integrates Python mobile worker with Android lifecycle
 */
class MobileWorkerService : Service() {
    
    companion object {
        private const val TAG = "MobileWorkerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mobile_worker_channel"
        private const val CHANNEL_NAME = "Mobile Worker Service"
    }
    
    private val binder = LocalBinder()
    private val isRunning = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var workerIdManager: WorkerIdManager
    
    // Python modules
    private var mobileWorkerModule: com.chaquo.python.PyObject? = null
    private var workerInstance: com.chaquo.python.PyObject? = null
    private var workerThread: Thread? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): MobileWorkerService = this@MobileWorkerService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        workerIdManager = WorkerIdManager.getInstance(this)
        createNotificationChannel()
        initializePython()
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        when (intent?.action) {
            "START_WORKER" -> {
                val foremanUrl = intent.getStringExtra("foreman_url") ?: "ws://192.168.8.101:9000"
                startWorker(foremanUrl)
            }
            "STOP_WORKER" -> stopWorker()
            "GET_STATUS" -> getWorkerStatus()
        }
        
        // Start as foreground service to prevent Android from killing it
        startForeground(NOTIFICATION_ID, createNotification("Mobile Worker Service", "Worker is running"))
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopWorker()
        serviceScope.cancel()
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
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
        val notification = createNotification(title, content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Initialize Python environment
     */
    private fun initializePython() {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            
            val py = Python.getInstance()
            mobileWorkerModule = py.getModule("mobile_worker")
            
            Log.d(TAG, "Python initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Python", e)
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
        
        serviceScope.launch {
            try {
                Log.d(TAG, "Starting mobile worker...")
                Log.d(TAG, "Foreman URL: $foremanUrl")
                
                // Get or generate persistent worker ID
                val workerId = workerIdManager.getOrGenerateWorkerId()
                Log.d(TAG, "Worker ID: $workerId")
                
                // Create worker instance
                Log.d(TAG, "Creating worker instance...")
                workerInstance = mobileWorkerModule?.callAttr(
                    "create_mobile_worker",
                    workerId,
                    foremanUrl
                )
                Log.d(TAG, "Worker instance created successfully")
                
                if (workerInstance != null) {
                    isRunning.set(true)
                    updateNotification("Mobile Worker Service", "Worker connected: $workerId")
                    
                    // Start worker in a separate thread to avoid blocking the service
                    workerThread = Thread {
                        try {
                            Log.d(TAG, "Starting worker in background thread")
                            Log.d(TAG, "Worker instance: $workerInstance")
                            
                            // Call start_sync method
                            val result = workerInstance?.callAttr("start_sync")
                            Log.d(TAG, "Worker start_sync result: $result")
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "Worker thread error", e)
                            Log.e(TAG, "Worker thread error details: ${e.message}")
                            e.printStackTrace()
                            isRunning.set(false)
                        }
                    }
                    workerThread?.start()
                    
                    Log.d(TAG, "Mobile worker started successfully in background thread")
                } else {
                    Log.e(TAG, "Failed to create worker instance")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start worker", e)
                isRunning.set(false)
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
            
            // Stop the worker gracefully
            workerInstance?.callAttr("stop")
            
            // Stop the worker thread
            workerThread?.interrupt()
            workerThread = null
            
            // Disconnect the worker
            workerInstance?.callAttr("disconnect")
            workerInstance = null
            isRunning.set(false)
            
            Log.d(TAG, "Mobile worker stopped successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop worker", e)
        }
    }
    
    /**
     * Get worker status
     */
    fun getWorkerStatus(): Map<String, Any>? {
        return try {
            workerInstance?.callAttr("get_status")?.toMap()
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
    
    /**
     * Test worker functionality
     */
    fun testWorker() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Testing mobile worker...")
                
                // Test the mobile worker module directly
                val testResult = mobileWorkerModule?.callAttr("test_mobile_worker")
                Log.d(TAG, "Worker test result: $testResult")
                
                // Convert Python boolean to Kotlin boolean
                val isSuccess = testResult?.toBoolean() ?: false
                if (isSuccess) {
                    Log.d(TAG, "✅ Worker test completed successfully")
                } else {
                    Log.e(TAG, "❌ Worker test failed")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Worker test failed", e)
                Log.e(TAG, "Worker test error details: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Test task execution functionality
     */
    fun testTaskExecution(): Map<String, Any>? {
        return try {
            Log.d(TAG, "Testing task execution...")
            
            val testResult = workerInstance?.callAttr("test_task_execution")?.toMap()
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
            workerInstance?.callAttr("get_detailed_device_info")?.toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mobile info", e)
            null
        }
    }
    
    /**
     * Get worker logs
     */
    fun getWorkerLogs(): String? {
        return try {
            // Read the log file if it exists
            val logFile = File(Environment.getExternalStorageDirectory(), "CrowdCompute/logs/worker_${workerInstance?.callAttr("config", "worker_id")?.toString()}.log")
            if (logFile.exists()) {
                logFile.readText()
            } else {
                "No log file found"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading logs: ${e.message}")
            "Error reading logs: ${e.message}"
        }
    }
    
    /**
     * Get worker status with detailed info
     */
    fun getDetailedWorkerStatus(): Map<String, Any>? {
        return try {
            val status = getWorkerStatus()
            val mobileInfo = getMobileInfo()
            val logs = getWorkerLogs()
            
            mapOf(
                "status" to (status ?: "No status available"),
                "mobile_info" to (mobileInfo ?: "No mobile info available"),
                "logs" to (logs ?: "No logs available"),
                "is_running" to isRunning.get(),
                "worker_thread_alive" to (workerThread?.isAlive ?: false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get detailed worker status", e)
            mapOf("error" to e.message.toString())
        }
    }
}
