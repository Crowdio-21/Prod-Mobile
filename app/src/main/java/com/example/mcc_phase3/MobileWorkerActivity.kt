package com.example.mcc_phase3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.example.mcc_phase3.services.MobileWorkerService
import com.example.mcc_phase3.data.ConfigManager
import com.example.mcc_phase3.utils.ThemeManager

/**
 * Activity for controlling and monitoring the mobile worker
 */
class MobileWorkerActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MobileWorkerActivity"
    }
    
    private lateinit var statusTextView: TextView
    private lateinit var statsTextView: TextView
    private lateinit var mobileInfoTextView: TextView
    private lateinit var logsTextView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var testButton: Button
    private lateinit var refreshButton: Button
    private lateinit var logsButton: Button
    
    private var mobileWorkerService: MobileWorkerService? = null
    private var isBound = false
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MobileWorkerService.LocalBinder
            mobileWorkerService = binder.getService()
            isBound = true
            Log.d(TAG, "Service connected")
            updateUI()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            mobileWorkerService = null
            isBound = false
            Log.d(TAG, "Service disconnected")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize theme manager and apply current theme
        ThemeManager.initialize(this)
        ThemeManager.applyCurrentTheme()
        
        setContentView(R.layout.activity_mobile_worker)
        
        initializeViews()
        setupClickListeners()
        bindService()
    }
    
    private fun initializeViews() {
        statusTextView = findViewById(R.id.statusTextView)
        statsTextView = findViewById(R.id.statsTextView)
        mobileInfoTextView = findViewById(R.id.mobileInfoTextView)
        logsTextView = findViewById(R.id.logsTextView)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        testButton = findViewById(R.id.testButton)
        refreshButton = findViewById(R.id.refreshButton)
        logsButton = findViewById(R.id.logsButton)
    }
    
    private fun setupClickListeners() {
        startButton.setOnClickListener {
            startMobileWorker()
        }
        
        stopButton.setOnClickListener {
            stopMobileWorker()
        }
        
        testButton.setOnClickListener {
            testMobileWorker()
            // Also show worker ID info after testing
            showWorkerIdInfo()
        }
        
        refreshButton.setOnClickListener {
            updateUI()
        }
        
        logsButton.setOnClickListener {
            updateUI()  // Refresh UI to show latest logs
        }
    }
    
    private fun bindService() {
        val intent = Intent(this, MobileWorkerService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
    
    private fun startMobileWorker() {
        if (isBound) {
            mobileWorkerService?.startWorker(getForemanUrl())
            updateUI()
        } else {
            // Start service if not bound
            val intent = Intent(this, MobileWorkerService::class.java)
            startService(intent)
            bindService()
        }
    }
    
    private fun getForemanUrl(): String {
        // Using ConfigManager for user-configurable settings
        val configManager = ConfigManager.getInstance(this)
        return configManager.getForemanURL()
    }
    
    private fun stopMobileWorker() {
        if (isBound) {
            mobileWorkerService?.stopWorker()
            updateUI()
        }
    }
    
    private fun testMobileWorker() {
        if (isBound) {
            mobileWorkerService?.testWorker()
            
            // Also test task execution
            lifecycleScope.launch {
                try {
                    val taskTestResult = mobileWorkerService?.testTaskExecution()
                    Log.d(TAG, "Task execution test result: $taskTestResult")
                    
                    if (taskTestResult != null) {
                        val success = taskTestResult["success"] as? Boolean ?: false
                        if (success) {
                            val result = taskTestResult["result"]
                            Log.d(TAG, "✅ Task execution test successful: $result")
                        } else {
                            val error = taskTestResult["error"] as? String
                            Log.e(TAG, "❌ Task execution test failed: $error")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Task execution test error", e)
                }
            }
            
            updateUI()
        }
    }
    
    private fun showWorkerIdInfo() {
        if (isBound && mobileWorkerService != null) {
            try {
                val workerIdInfo = mobileWorkerService?.getWorkerIdInfo()
                val currentWorkerId = mobileWorkerService?.getCurrentWorkerId()
                
                val infoText = buildString {
                    appendLine("🆔 Worker ID Information:")
                    appendLine("Current Worker ID: $currentWorkerId")
                    appendLine("")
                    if (workerIdInfo != null) {
                        appendLine("Detailed Info:")
                        workerIdInfo.forEach { (key, value) ->
                            appendLine("$key: $value")
                        }
                    }
                }
                
                // Show in a dialog or update the mobile info text view
                mobileInfoTextView.text = infoText
                Log.d(TAG, "Worker ID info displayed: $infoText")
            } catch (e: Exception) {
                Log.e(TAG, "Error showing worker ID info", e)
                mobileInfoTextView.text = "Error getting worker ID info: ${e.message}"
            }
        }
    }
    
    private fun updateUI() {
        lifecycleScope.launch {
            if (isBound && mobileWorkerService != null) {
                try {
                    // Get detailed worker status
                    val detailedStatus = mobileWorkerService?.getDetailedWorkerStatus()
                    
                    if (detailedStatus != null) {
                        // Update status
                        val isRunning = detailedStatus["is_running"] as? Boolean ?: false
                        val workerThreadAlive = detailedStatus["worker_thread_alive"] as? Boolean ?: false
                        val status = detailedStatus["status"] as? Map<*, *>
                        
                        val statusText = buildString {
                            appendLine("🔄 Worker Status:")
                            appendLine("Service Running: $isRunning")
                            appendLine("Thread Alive: $workerThreadAlive")
                            appendLine("Connection: ${status?.get("status") ?: "Unknown"}")
                            appendLine("Current Task: ${status?.get("current_task") ?: "None"}")
                        }
                        statusTextView.text = statusText
                        
                        // Update stats
                        val stats = status?.get("stats") as? Map<*, *>
                        if (stats != null) {
                            val statsText = buildString {
                                appendLine("📊 Worker Statistics:")
                                appendLine("Tasks Completed: ${stats.get("tasks_completed")}")
                                appendLine("Tasks Failed: ${stats.get("tasks_failed")}")
                                appendLine("Total Execution Time: ${stats.get("total_execution_time")}s")
                                appendLine("Uptime: ${stats.get("uptime_seconds")}s")
                            }
                            statsTextView.text = statsText
                        }
                        
                        // Update mobile info
                        val mobileInfo = detailedStatus["mobile_info"] as? Map<*, *>
                        if (mobileInfo != null) {
                            val mobileText = buildString {
                                appendLine("📱 Device Information:")
                                appendLine("Device ID: ${mobileInfo.get("device_id")}")
                                appendLine("Platform: ${mobileInfo.get("platform")}")
                                appendLine("Battery: ${mobileInfo.get("battery_level")}%")
                                appendLine("Charging: ${mobileInfo.get("is_charging")}")
                                appendLine("Network: ${mobileInfo.get("network_available")}")
                                
                                // Add worker ID info
                                val workerIdInfo = mobileInfo.get("worker_id_info") as? Map<*, *>
                                if (workerIdInfo != null) {
                                    appendLine("")
                                    appendLine("🆔 Worker ID Information:")
                                    appendLine("Worker ID: ${workerIdInfo.get("workerId")}")
                                    appendLine("Device ID: ${workerIdInfo.get("deviceId")}")
                                    appendLine("Generated: ${workerIdInfo.get("generatedAt")}")
                                    appendLine("Has ID: ${workerIdInfo.get("hasWorkerId")}")
                                }
                            }
                            mobileInfoTextView.text = mobileText
                        }
                        
                        // Update logs
                        val logs = detailedStatus["logs"] as? String
                        if (logs != null && logs.isNotEmpty() && logs != "No log file found") {
                            logsTextView.text = "📝 Recent Logs:\n${logs.takeLast(500)}"
                        } else {
                            logsTextView.text = "📝 Recent Logs:\nNo logs available"
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating UI", e)
                    statusTextView.text = "Status: Error - ${e.message}"
                }
            } else {
                statusTextView.text = "Status: Service not connected"
                statsTextView.text = "📊 Worker Statistics:\nNot available"
                mobileInfoTextView.text = "📱 Device Information:\nNot available"
                logsTextView.text = "📝 Recent Logs:\nNot available"
            }
        }
    }
    

    
    override fun onResume() {
        super.onResume()
        updateUI()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
