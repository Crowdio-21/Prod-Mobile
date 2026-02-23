package com.example.mcc_phase3.communication

import android.content.Context
import android.util.Log
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.execution.TaskProcessor
import com.example.mcc_phase3.checkpoint.CheckpointMessage
import com.example.mcc_phase3.utils.EventLogger
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Native Kotlin WebSocket client for worker communication
 * This class handles:
 * - WebSocket connection to backend
 * - Sending worker registration and status updates
 * - Receiving task assignments
 * - NOT executing Python code (delegated to TaskProcessor)
 */
class WorkerWebSocketClient(
    private val context: Context,
    private val taskProcessor: TaskProcessor
) {
    
    companion object {
        private const val TAG = "WorkerWebSocketClient"
        private const val HEARTBEAT_INTERVAL = 30000L // 30 seconds
        private const val RECONNECT_DELAY = 5000L // 5 seconds
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }
    
    private val workerIdManager = WorkerIdManager.getInstance(context)
    
    private var webSocket: WebSocketClient? = null
    private val isConnected = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private val reconnectAttempts = AtomicLong(0)
    
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Listeners
    private val listeners = mutableSetOf<WorkerWebSocketListener>()
    
    /**
     * Connect to the backend WebSocket server
     */
    suspend fun connect(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to WebSocket: $url")
                
                // Initialize task processor
                val processorInitialized = taskProcessor.initialize()
                if (!processorInitialized) {
                    Log.e(TAG, "Failed to initialize task processor")
                    return@withContext false
                }
                
                // Set up checkpoint callback to send checkpoints via WebSocket
                taskProcessor.setCheckpointCallback { checkpointMsg ->
                    sendCheckpointMessage(checkpointMsg)
                }
                
                // Disconnect if already connected
                if (isConnected.get()) {
                    disconnect()
                }
                
                val uri = URI(url)
                webSocket = object : WebSocketClient(uri) {
                    override fun onOpen(handshake: ServerHandshake?) {
                        Log.d(TAG, "WebSocket connected to $url")
                        EventLogger.success(EventLogger.Categories.WEBSOCKET, "Connected to foreman: $url")
                        isConnected.set(true)
                        reconnectAttempts.set(0)
                        
                        // Start heartbeat
                        startHeartbeat()
                        
                        // Register worker
                        registerWorker()
                        
                        // Notify listeners
                        listeners.forEach { it.onConnected() }
                    }
                    
                    override fun onMessage(message: String?) {
                        message?.let { msg ->
                            Log.d(TAG, "Received message: $msg")
                            clientScope.launch {
                                handleMessage(msg)
                            }
                        }
                    }
                    
                    override fun onClose(code: Int, reason: String?, remote: Boolean) {
                        Log.w(TAG, "WebSocket closed (code=$code, reason=$reason, remote=$remote)")
                        EventLogger.warning(EventLogger.Categories.WEBSOCKET, "Disconnected (code=$code, reason=$reason)")
                        isConnected.set(false)
                        stopHeartbeat()
                        
                        // Notify listeners
                        listeners.forEach { it.onDisconnected() }
                        
                        // Attempt reconnection if not manually disconnected
                        if (isRunning.get() && !remote) {
                            scheduleReconnection(url)
                        }
                    }
                    
                    override fun onError(ex: Exception?) {
                        Log.e(TAG, "WebSocket error", ex)
                        EventLogger.error(EventLogger.Categories.WEBSOCKET, "Connection error: ${ex?.message ?: "Unknown"}")
                        listeners.forEach { it.onError(ex) }
                    }
                }
                
                webSocket?.connect()
                isRunning.set(true)
                
                // Wait for connection with timeout
                var attempts = 0
                while (!isConnected.get() && attempts < 30) { // 30 second timeout
                    delay(1000)
                    attempts++
                }
                
                isConnected.get()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to connect to WebSocket", e)
                false
            }
        }
    }
    
    /**
     * Disconnect from WebSocket
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting WebSocket...")
        isRunning.set(false)
        stopHeartbeat()
        reconnectJob?.cancel()
        webSocket?.close()
        webSocket = null
        isConnected.set(false)
        Log.d(TAG, "WebSocket disconnected")
    }
    
    /**
     * Send message to backend
     */
    fun sendMessage(message: String) {
        if (isConnected.get()) {
            try {
                webSocket?.send(message)
                Log.d(TAG, "Message sent: $message")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        } else {
            Log.w(TAG, "Cannot send message - not connected")
        }
    }
    
    /**
     * Send checkpoint message to foreman via WebSocket
     */
    private suspend fun sendCheckpointMessage(checkpoint: CheckpointMessage) {
        withContext(Dispatchers.IO) {
            try {
                if (isConnected.get()) {
                    val json = checkpoint.toJsonString()
                    webSocket?.send(json)
                    
                    val checkpointType = if (checkpoint.isBase) "BASE" else "DELTA"
                    Log.i(TAG, "[Checkpoint] Task ${checkpoint.taskId} | $checkpointType #${checkpoint.checkpointId} | " +
                            "Progress: ${checkpoint.progressPercent}%")
                } else {
                    Log.w(TAG, "Cannot send checkpoint - not connected")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending checkpoint: ${e.message}")
            }
        }
    }
    
    /**
     * Register worker with backend
     */
    private fun registerWorker() {
        clientScope.launch {
            try {
                val workerId = workerIdManager.getOrGenerateWorkerId()
                
                // Create WORKER_READY message with device specs (auto-collected)
                Log.d(TAG, "Collecting device specifications...")
                val readyMessage = MessageProtocol.createWorkerReadyMessage(
                    workerId = workerId,
                    context = context
                )
                
                sendMessage(readyMessage)
                Log.d(TAG, "Worker ready message sent with device specs: $workerId")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send worker ready message", e)
            }
        }
    }
    
    /**
     * Handle incoming messages from backend
     */
    private suspend fun handleMessage(message: String) {
        try {
            Log.d(TAG, "Processing message: $message")
            
            // Parse the message using the new protocol
            val messageData = MessageProtocol.parseMessage(message)
            if (messageData == null) {
                Log.w(TAG, "Failed to parse message: $message")
                return
            }
            
            // Handle different message types
            when (messageData.type) {
                MessageProtocol.MessageType.ASSIGN_TASK -> {
                    handleTaskAssignment(messageData)
                }
                MessageProtocol.MessageType.RESUME_TASK -> {
                    handleTaskResumption(messageData)
                }
                MessageProtocol.MessageType.PING -> {
                    handlePing(messageData)
                }
                MessageProtocol.MessageType.CHECKPOINT_ACK -> {
                    // Checkpoint acknowledgment received, log for debugging
                    val data = messageData.data
                    val checkpointId = data?.optInt("checkpoint_id", -1) ?: -1
                    val taskId = data?.optString("task_id", "") ?: ""
                    Log.d(TAG, "Checkpoint #$checkpointId acknowledged for task $taskId")
                }
                else -> {
                    Log.w(TAG, "Unknown message type: ${messageData.type}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }
    
    /**
     * Handle task assignment from foreman
     */
    private suspend fun handleTaskAssignment(messageData: MessageProtocol.MessageData) {
        // Declare data outside try-catch so it's accessible in catch block
        val data = messageData.data
        
        try {
            if (data == null) {
                Log.w(TAG, "No data in task assignment message")
                return
            }
            
            val taskId = data.optString("task_id", "")
            val jobId = messageData.jobId
            val funcCode = data.optString("func_code", "")
            val taskArgs = data.optString("task_args", "")
            
            Log.d(TAG, "Received task assignment: $taskId for job: $jobId")
            Log.d(TAG, "Function code length: ${funcCode.length}, Task args: $taskArgs")
            EventLogger.info(EventLogger.Categories.TASK, "Received task assignment: $taskId (Job: $jobId)")
            
            // Create a proper task message for TaskProcessor
            val taskMessage = JSONObject().apply {
                put("type", "assign_task")
                put("data", data)
                if (jobId != null) {
                    put("job_id", jobId)
                }
                put("timestamp", System.currentTimeMillis())
            }.toString()
            
            // Process the task through TaskProcessor
            val response = taskProcessor.processTaskMessage(taskMessage)
            
            // Send response back to backend
            response?.let { resp ->
                sendMessage(resp)
                Log.d(TAG, "Task result sent: $resp")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling task assignment", e)
            
            // Send error response
            val errorResponse = MessageProtocol.createTaskErrorMessage(
                taskId = data?.optString("task_id", "") ?: "unknown",
                jobId = messageData.jobId,
                error = "Task assignment failed: ${e.message ?: "Unknown error"}"
            )
            sendMessage(errorResponse)
        }
    }
    
    /**
     * Handle task resumption from foreman (resume from checkpoint)
     */
    private suspend fun handleTaskResumption(messageData: MessageProtocol.MessageData) {
        val data = messageData.data
        
        try {
            if (data == null) {
                Log.w(TAG, "No data in task resumption message")
                return
            }
            
            val taskId = data.optString("task_id", "")
            val jobId = messageData.jobId
            val funcCode = data.optString("func_code", "")
            val taskArgs = data.optString("task_args", "")
            
            Log.d(TAG, "Received task RESUMPTION: $taskId for job: $jobId")
            Log.d(TAG, "Function code length: ${funcCode.length}, Task args: $taskArgs")
            
            // Check if checkpoint data is present
            val checkpointData = data.optString("checkpoint_data", null)
            val checkpointCount = data.optInt("checkpoint_count", 0)
            if (checkpointData != null) {
                Log.d(TAG, "Checkpoint data present, resuming from checkpoint #$checkpointCount")
            }
            
            // Create a proper resume_task message for TaskProcessor
            val taskMessage = JSONObject().apply {
                put("type", MessageProtocol.MessageType.RESUME_TASK)
                put("data", data)
                if (jobId != null) {
                    put("job_id", jobId)
                }
                put("timestamp", System.currentTimeMillis())
            }.toString()
            
            // Process the task resumption through TaskProcessor
            val response = taskProcessor.processTaskMessage(taskMessage)
            
            // Send response back to backend
            response?.let { resp ->
                sendMessage(resp)
                Log.d(TAG, "Task result sent (resumed): $resp")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling task resumption", e)
            
            // Send error response
            val errorResponse = MessageProtocol.createTaskErrorMessage(
                taskId = data?.optString("task_id", "") ?: "unknown",
                jobId = messageData.jobId,
                error = "Task resumption failed: ${e.message ?: "Unknown error"}"
            )
            sendMessage(errorResponse)
        }
    }
    
    /**
     * Handle ping message from foreman
     */
    private fun handlePing(messageData: MessageProtocol.MessageData) {
        try {
            Log.d(TAG, "Received ping, sending pong")
            val workerId = workerIdManager.getCurrentWorkerId() ?: "unknown"
            val pongMessage = MessageProtocol.createPongMessage(workerId, context)
            sendMessage(pongMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ping", e)
        }
    }
    
    /**
     * Start heartbeat to keep connection alive
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = clientScope.launch {
            // Wait a bit before sending first heartbeat to ensure connection is stable
            delay(5000)
            
            while (isConnected.get() && isRunning.get()) {
                try {
                    if (isConnected.get()) {
                        val workerId = workerIdManager.getCurrentWorkerId() ?: "unknown"
                        val taskStatus = taskProcessor.getCurrentTaskStatus()
                        val currentTaskId = taskStatus["current_task_id"] as? String
                        val currentJobId = taskStatus["current_job_id"] as? String
                        
                        val heartbeatMessage = MessageProtocol.createHeartbeatMessage(
                            workerId,
                            currentTaskId,
                            currentJobId,
                            context
                        )
                        
                        sendMessage(heartbeatMessage)
                        val taskInfo = if (currentTaskId.isNullOrEmpty()) "No task" else currentTaskId
                        Log.d(TAG, "Heartbeat sent - Worker: $workerId, Task: $taskInfo")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending heartbeat", e)
                }
                
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }
    
    /**
     * Stop heartbeat
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    /**
     * Schedule reconnection attempt
     */
    private fun scheduleReconnection(url: String) {
        if (reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "🚨 Max reconnection attempts reached, giving up")
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = clientScope.launch {
            val attempt = reconnectAttempts.incrementAndGet()
            val delay = RECONNECT_DELAY * attempt // Exponential backoff
            
            Log.d(TAG, "Scheduling reconnection attempt $attempt in ${delay}ms")
            delay(delay)
            
            if (isRunning.get() && !isConnected.get()) {
                Log.d(TAG, "Attempting reconnection #$attempt")
                connect(url)
            }
        }
    }
    
    /**
     * Test WebSocket functionality
     */
    suspend fun testWebSocket(): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Testing WebSocket functionality...")
                
                // Test task processor
                val processorTest = taskProcessor.testTaskProcessing()
                
                // Test status request
                val statusMessage = """
                    {
                        "type": "get_status",
                        "data": {}
                    }
                """.trimIndent()
                
                val statusResponse = taskProcessor.processTaskMessage(statusMessage)
                val statusJson = org.json.JSONObject(statusResponse ?: "{}")
                
                val isSuccess = processorTest["status"] == "success" && 
                               statusJson.optString("status") == "ready"
                
                mapOf<String, Any>(
                    "status" to if (isSuccess) "success" else "error",
                    "message" to if (isSuccess) "WebSocket test passed" else "WebSocket test failed",
                    "processor_test" to processorTest,
                    "status_response" to statusJson.toString(),
                    "is_connected" to isConnected.get(),
                    "is_running" to isRunning.get()
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket test failed", e)
                mapOf<String, Any>(
                    "status" to "error",
                    "message" to "WebSocket test failed: ${e.message ?: "Unknown error"}",
                    "error" to e.toString()
                )
            }
        }
    }
    
    /**
     * Get connection status
     */
    fun getConnectionStatus(): Map<String, Any> {
        return mapOf<String, Any>(
            "is_connected" to isConnected.get(),
            "is_running" to isRunning.get(),
            "reconnect_attempts" to reconnectAttempts.get(),
            "worker_id" to (workerIdManager.getCurrentWorkerId() ?: "unknown"),
            "heartbeat_active" to (heartbeatJob?.isActive ?: false),
            "last_heartbeat" to System.currentTimeMillis()
        )
    }
    
    /**
     * Send immediate heartbeat for testing
     */
    fun sendImmediateHeartbeat() {
        if (isConnected.get()) {
            val workerId = workerIdManager.getCurrentWorkerId() ?: "unknown"
            val taskStatus = taskProcessor.getCurrentTaskStatus()
            val currentTaskId = taskStatus["current_task_id"] as? String
            val currentJobId = taskStatus["current_job_id"] as? String
            
            val heartbeatMessage = MessageProtocol.createHeartbeatMessage(workerId, currentTaskId, currentJobId, context)
            sendMessage(heartbeatMessage)
            val taskInfo = if (currentTaskId.isNullOrEmpty()) "No task" else currentTaskId
            Log.d(TAG, "Immediate heartbeat sent - Worker: $workerId, Task: $taskInfo")
        } else {
            Log.w(TAG, "Cannot send heartbeat - not connected")
        }
    }
    
    /**
     * Add listener for WebSocket events
     */
    fun addListener(listener: WorkerWebSocketListener) {
        listeners.add(listener)
    }
    
    /**
     * Remove listener
     */
    fun removeListener(listener: WorkerWebSocketListener) {
        listeners.remove(listener)
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            Log.d(TAG, "Cleaning up WebSocket client...")
            disconnect()
            clientScope.cancel()
            taskProcessor.cleanup()
            listeners.clear()
            Log.d(TAG, "WebSocket client cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    /**
     * Listener interface for WebSocket events
     */
    interface WorkerWebSocketListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: Exception?)
    }
}
