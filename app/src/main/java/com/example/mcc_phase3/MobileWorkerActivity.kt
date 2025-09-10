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
            // Test the new architecture components
            lifecycleScope.launch {
                try {
                    val taskTestResult = mobileWorkerService?.testTaskExecution()
                    Log.d(TAG, "Task execution test result: $taskTestResult")
                    
                    if (taskTestResult != null) {
                        val status = taskTestResult["status"] as? String
                        if (status == "success") {
                            val result = taskTestResult["result"]
                            Log.d(TAG, "✅ Task execution test successful: $result")
                        } else {
                            val error = taskTestResult["message"] as? String
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
                    // Get worker status using the new architecture
                    val workerStatus = mobileWorkerService?.getWorkerStatus()
                    
                    if (workerStatus != null) {
                        // Update status
                        val isRunning = workerStatus["is_running"] as? Boolean ?: false
                        val connection = workerStatus["connection"] as? Map<String, Any>
                        val taskProcessor = workerStatus["task_processor"] as? Map<String, Any>
                        val pythonExecutor = workerStatus["python_executor"] as? Map<String, Any>
                        val workerId = workerStatus["worker_id"] as? String
                        
                        val statusText = buildString {
                            appendLine("🔄 Worker Status:")
                            appendLine("Service Running: $isRunning")
                            appendLine("Worker ID: $workerId")
                            appendLine("WebSocket Connected: ${connection?.get("is_connected") ?: false}")
                            appendLine("Python Ready: ${pythonExecutor?.get("initialized") ?: false}")
                            appendLine("Task Processor Ready: ${taskProcessor?.get("processor_initialized") ?: false}")
                            appendLine("Current Task: ${taskProcessor?.get("current_task_id") ?: "None"}")
                            appendLine("Is Busy: ${taskProcessor?.get("is_busy") ?: false}")
                        }
                        statusTextView.text = statusText
                        
                        // Update stats
                        val statsText = buildString {
                            appendLine("📊 Worker Statistics:")
                            appendLine("Python Version: ${pythonExecutor?.get("python_version") ?: "Unknown"}")
                            appendLine("Platform: ${pythonExecutor?.get("platform") ?: "Unknown"}")
                            appendLine("Reconnect Attempts: ${connection?.get("reconnect_attempts") ?: 0}")
                            appendLine("WebSocket Running: ${connection?.get("is_running") ?: false}")
                        }
                        statsTextView.text = statsText
                        
                        // Update mobile info
                        val mobileInfo = mobileWorkerService?.getMobileInfo()
                        if (mobileInfo != null) {
                            val mobileText = buildString {
                                appendLine("📱 Device Information:")
                                appendLine("Platform: ${mobileInfo["platform"]}")
                                appendLine("Worker ID: ${mobileInfo["worker_id"]}")
                                appendLine("Service Running: ${mobileInfo["service_running"]}")
                                
                                // Add Python executor info
                                val pythonInfo = mobileInfo["python_executor"] as? Map<String, Any>
                                if (pythonInfo != null) {
                                    appendLine("")
                                    appendLine("🐍 Python Executor:")
                                    appendLine("Initialized: ${pythonInfo["initialized"]}")
                                    appendLine("Python Version: ${pythonInfo["python_version"]}")
                                    appendLine("Platform: ${pythonInfo["platform"]}")
                                }
                                
                                // Add connection info
                                val connInfo = mobileInfo["connection"] as? Map<String, Any>
                                if (connInfo != null) {
                                    appendLine("")
                                    appendLine("🌐 Connection Status:")
                                    appendLine("Connected: ${connInfo["is_connected"]}")
                                    appendLine("Running: ${connInfo["is_running"]}")
                                    appendLine("Reconnect Attempts: ${connInfo["reconnect_attempts"]}")
                                }
                            }
                            mobileInfoTextView.text = mobileText
                        }
                        
                        // Update logs - show recent status instead of file logs
                        val logsText = buildString {
                            appendLine("📝 Recent Status:")
                            appendLine("Last Update: ${System.currentTimeMillis()}")
                            appendLine("Worker Status: ${if (isRunning) "Running" else "Stopped"}")
                            appendLine("Python Executor: ${if (pythonExecutor?.get("initialized") == true) "Ready" else "Not Ready"}")
                            appendLine("WebSocket: ${if (connection?.get("is_connected") == true) "Connected" else "Disconnected"}")
                        }
                        logsTextView.text = logsText
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
