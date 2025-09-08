package com.example.mcc_phase3.ui

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.mcc_phase3.R
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.data.WorkerIdBridge
import java.text.SimpleDateFormat
import java.util.*

/**
 * Test Activity for demonstrating WorkerIdManager functionality
 * This activity can be used to test and verify worker ID generation and storage
 */
class WorkerIdTestActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "WorkerIdTestActivity"
    }
    
    private lateinit var workerIdManager: WorkerIdManager
    private lateinit var workerIdBridge: WorkerIdBridge
    private lateinit var statusTextView: TextView
    private lateinit var workerIdTextView: TextView
    private lateinit var deviceIdTextView: TextView
    private lateinit var generatedAtTextView: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_worker_id_test)
        
        // Initialize managers
        workerIdManager = WorkerIdManager.getInstance(this)
        workerIdBridge = WorkerIdBridge(this)
        
        // Initialize views
        initializeViews()
        
        // Setup button listeners
        setupButtonListeners()
        
        // Display current worker ID information
        displayWorkerIdInfo()
        
        Log.d(TAG, "WorkerIdTestActivity created and initialized")
    }
    
    private fun initializeViews() {
        statusTextView = findViewById(R.id.statusTextView)
        workerIdTextView = findViewById(R.id.workerIdTextView)
        deviceIdTextView = findViewById(R.id.deviceIdTextView)
        generatedAtTextView = findViewById(R.id.generatedAtTextView)
    }
    
    private fun setupButtonListeners() {
        findViewById<Button>(R.id.getWorkerIdButton).setOnClickListener {
            getOrGenerateWorkerId()
        }
        
        findViewById<Button>(R.id.resetWorkerIdButton).setOnClickListener {
            resetWorkerId()
        }
        
        findViewById<Button>(R.id.refreshInfoButton).setOnClickListener {
            displayWorkerIdInfo()
        }
        
        findViewById<Button>(R.id.testPythonBridgeButton).setOnClickListener {
            testPythonBridge()
        }
    }
    
    private fun getOrGenerateWorkerId() {
        try {
            Log.d(TAG, "Getting or generating worker ID...")
            val workerId = workerIdManager.getOrGenerateWorkerId()
            updateStatus("✅ Worker ID retrieved/generated: $workerId")
            displayWorkerIdInfo()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting worker ID", e)
            updateStatus("❌ Error getting worker ID: ${e.message}")
        }
    }
    
    private fun resetWorkerId() {
        try {
            Log.d(TAG, "Resetting worker ID...")
            val newWorkerId = workerIdManager.resetWorkerId()
            updateStatus("🔄 Worker ID reset to: $newWorkerId")
            displayWorkerIdInfo()
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting worker ID", e)
            updateStatus("❌ Error resetting worker ID: ${e.message}")
        }
    }
    
    private fun displayWorkerIdInfo() {
        try {
            val info = workerIdManager.getWorkerIdInfo()
            
            workerIdTextView.text = info.workerId ?: "No worker ID"
            deviceIdTextView.text = info.deviceId
            generatedAtTextView.text = if (info.generatedAt > 0) {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                dateFormat.format(Date(info.generatedAt))
            } else {
                "Not generated"
            }
            
            val statusText = if (info.hasWorkerId) {
                "✅ Worker ID exists in storage"
            } else {
                "⚠️ No worker ID in storage"
            }
            updateStatus(statusText)
            
            Log.d(TAG, "Worker ID info displayed: ${info.workerId}")
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying worker ID info", e)
            updateStatus("❌ Error displaying info: ${e.message}")
        }
    }
    
    private fun testPythonBridge() {
        try {
            Log.d(TAG, "Testing Python bridge...")
            
            // Test getting worker ID through bridge
            val workerId = workerIdBridge.getOrGenerateWorkerId()
            val hasId = workerIdBridge.hasWorkerId()
            val info = workerIdBridge.getWorkerIdInfo()
            
            val bridgeStatus = """
                🐍 Python Bridge Test Results:
                Worker ID: $workerId
                Has ID: $hasId
                Device ID: ${info["deviceId"]}
                Generated At: ${info["generatedAt"]}
            """.trimIndent()
            
            updateStatus(bridgeStatus)
            Log.d(TAG, "Python bridge test completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error testing Python bridge", e)
            updateStatus("❌ Python bridge test failed: ${e.message}")
        }
    }
    
    private fun updateStatus(message: String) {
        statusTextView.text = message
        Log.d(TAG, "Status updated: $message")
    }
}
