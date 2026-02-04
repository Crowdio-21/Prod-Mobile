package com.example.mcc_phase3.execution

import android.content.Context
import android.util.Log
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.communication.MessageProtocol
import com.example.mcc_phase3.checkpoint.CheckpointHandler
import com.example.mcc_phase3.checkpoint.CheckpointMessage
import com.example.mcc_phase3.checkpoint.TaskMetadata
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * TaskProcessor handles task assignment and routing
 * This class is responsible for:
 * - Parsing task messages from backend
 * - Routing tasks to appropriate handlers
 * - Managing task execution flow
 * - Configuring checkpointing from task_metadata
 * - NOT executing Python code (delegated to PythonExecutor)
 */
class TaskProcessor(private val context: Context) {
    
    companion object {
        private const val TAG = "TaskProcessor"
        
        // Task types
        private const val TASK_TYPE_ASSIGN = "assign_task"
        private const val TASK_TYPE_CANCEL = "cancel_task"
        private const val TASK_TYPE_STATUS = "get_status"
        private const val TASK_TYPE_HEARTBEAT = "heartbeat"
        
        // Response types
        private const val RESPONSE_TYPE_TASK_RESULT = "task_result"
        private const val RESPONSE_TYPE_STATUS = "status_response"
        private const val RESPONSE_TYPE_ERROR = "error_response"
    }
    
    private val workerIdManager = WorkerIdManager.getInstance(context)
    private val pythonExecutor = PythonExecutor(context)
    private val isInitialized = AtomicBoolean(false)
    
    // Checkpoint handler for periodic progress reporting
    private val checkpointHandler = CheckpointHandler(checkpointIntervalMs = 5000L)
    private val processorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Callback for sending checkpoint messages via WebSocket
    private var checkpointCallback: (suspend (CheckpointMessage) -> Unit)? = null
    
    // Task execution tracking
    private val currentTaskId = AtomicReference<String?>(null)
    private val currentJobId = AtomicReference<String?>(null)
    private val isTaskRunning = AtomicBoolean(false)
    
    /**
     * Initialize the task processor
     */
    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing TaskProcessor...")
                
                val pythonInitialized = pythonExecutor.initialize()
                if (!pythonInitialized) {
                    Log.e(TAG, "Failed to initialize Python executor")
                    return@withContext false
                }
                
                isInitialized.set(true)
                Log.d(TAG, " TaskProcessor initialized successfully")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, " Failed to initialize TaskProcessor", e)
                false
            }
        }
    }
    
    /**
     * Process incoming task message from backend
     * @param message JSON message from WebSocket
     * @return Response message to send back to backend
     */
    suspend fun processTaskMessage(message: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized.get()) {
                    Log.w(TAG, "TaskProcessor not initialized, attempting to initialize...")
                    val initialized = initialize()
                    if (!initialized) {
                        return@withContext createErrorResponse("TaskProcessor not initialized")
                    }
                }
                
                Log.d(TAG, "Processing task message: $message")
                
                val jsonMessage = JSONObject(message)
                val messageType = jsonMessage.optString("type", "")
                val data = jsonMessage.optJSONObject("data")
                val jobId = jsonMessage.optString("job_id", null)
                
                when (messageType) {
                    MessageProtocol.MessageType.ASSIGN_TASK -> processTaskAssignment(data, jobId)
                    MessageProtocol.MessageType.RESUME_TASK -> processTaskResumption(data, jobId)
                    MessageProtocol.MessageType.CHECKPOINT_ACK -> processCheckpointAck(data)
                    TASK_TYPE_CANCEL -> processTaskCancellation(data)
                    TASK_TYPE_STATUS -> processStatusRequest()
                    MessageProtocol.MessageType.WORKER_HEARTBEAT -> processHeartbeat()
                    else -> {
                        Log.w(TAG, "Unknown message type: $messageType")
                        createErrorResponse("Unknown message type: $messageType")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing task message", e)
                createErrorResponse("Error processing message: ${e.message}")
            }
        }
    }
    
    /**
     * Process task assignment from backend
     */
    private suspend fun processTaskAssignment(data: JSONObject?, jobId: String?): String {
        // Declare taskId outside try-catch so it's accessible in catch block
        val taskId = data?.optString("task_id", "") ?: ""
        
        return try {
            if (data == null) {
                return createErrorResponse("No task data provided")
            }
            
            if (taskId.isEmpty()) {
                return createErrorResponse("No task ID provided")
            }
            
            // Check if we're already running a task
            if (isTaskRunning.get()) {
                Log.w(TAG, "Already running task ${currentTaskId.get()}, rejecting new task $taskId")
                return createErrorResponse("Worker is busy with task ${currentTaskId.get()}")
            }
            
            Log.d(TAG, "Processing task assignment: $taskId")
            
            // Extract function code and task arguments (matching desktop worker format)
            val funcCode = data.optString("func_code", "")
            val taskArgs = data.optString("task_args", "")
            
            // Parse task_metadata if present (declarative checkpointing configuration)
            val taskMetadataJson = data.optJSONObject("task_metadata")
            val taskMetadata = TaskMetadata.fromJson(taskMetadataJson)
            
            if (taskMetadataJson != null) {
                Log.d(TAG, "Task metadata: checkpoint_enabled=${taskMetadata.checkpointEnabled}, " +
                        "interval=${taskMetadata.checkpointInterval}s, " +
                        "vars=${taskMetadata.checkpointState}")
            }
            
            if (funcCode.isEmpty()) {
                return createErrorResponse("No function code found in task")
            }
            
            // Set current task and job
            currentTaskId.set(taskId)
            currentJobId.set(jobId)
            isTaskRunning.set(true)
            
            try {
                // Start checkpoint monitoring if callback is set and checkpointing is enabled
                checkpointCallback?.let { callback ->
                    // Configure checkpoint handler from task_metadata if present
                    if (taskMetadataJson != null) {
                        checkpointHandler.configure(taskMetadata)
                    }
                    
                    // Only start monitoring if checkpointing is enabled
                    if (checkpointHandler.isCheckpointingEnabled()) {
                        // Pass checkpoint handler to Python executor for progress updates
                        pythonExecutor.setCheckpointHandler(checkpointHandler)
                        
                        checkpointHandler.startCheckpointMonitoring(
                            taskId = taskId,
                            jobId = jobId,
                            workerId = workerIdManager.getCurrentWorkerId(),
                            scope = processorScope,
                            sendCheckpoint = callback,
                            pollState = { pythonExecutor.pollAndUpdateCheckpointState() }
                        )
                        Log.i(TAG, "✅ Checkpoint monitoring started for task $taskId")
                    } else {
                        Log.d(TAG, "Checkpointing disabled for task $taskId (per task_metadata)")
                    }
                }
                
                // Execute the function code (matching desktop worker format)
                val executionResult = pythonExecutor.executeCode(funcCode, taskArgs)
                
                // Create response using the new protocol
                val response = MessageProtocol.createTaskResultMessage(
                    taskId = taskId,
                    jobId = jobId, // Use the actual job ID from the message
                    result = executionResult["result"],
                    executionTime = 0.0 // TODO: Calculate actual execution time
                )
                
                Log.d(TAG, " Task $taskId completed successfully")
                response
                
            } finally {
                // Stop checkpoint monitoring
                checkpointHandler.stopCheckpointMonitoring()
                pythonExecutor.setCheckpointHandler(null)
                Log.i(TAG, " Checkpoint monitoring stopped for task $taskId (sent ${checkpointHandler.getCheckpointCount()} checkpoints)")
                
                // Clear current task and job
                currentTaskId.set(null)
                currentJobId.set(null)
                isTaskRunning.set(false)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing task assignment", e)
            currentTaskId.set(null)
            currentJobId.set(null)
            isTaskRunning.set(false)
            MessageProtocol.createTaskErrorMessage(
                taskId = taskId,
                jobId = jobId,
                error = "Task execution failed: ${e.message ?: "Unknown error"}"
            )
        }
    }
    
    /**
     * Process task resumption from checkpoint (RESUME_TASK message)
     * This handles the foreman's request to resume a failed task from its last checkpoint
     * 
     * Expected message format:
     * {
     *   "type": "resume_task",
     *   "job_id": "job-uuid",
     *   "data": {
     *     "task_id": "task-uuid",
     *     "func_code": "def worker_fn(x): ...",
     *     "args": [...],
     *     "kwargs": {...},
     *     "checkpoint_state": { ... reconstructed state ... },
     *     "checkpoint_count": 4,
     *     "progress_percent": 50.0,
     *     "recovery_status": "resumed",
     *     "task_metadata": { ... checkpointing config ... }
     *   }
     * }
     */
    private suspend fun processTaskResumption(data: JSONObject?, jobId: String?): String {
        val taskId = data?.optString("task_id", "") ?: ""
        
        return try {
            if (data == null) {
                return createErrorResponse("No task data provided for resumption")
            }
            
            if (taskId.isEmpty()) {
                return createErrorResponse("No task ID provided for resumption")
            }
            
            // Check if we're already running a task
            if (isTaskRunning.get()) {
                Log.w(TAG, "Already running task ${currentTaskId.get()}, rejecting resumption $taskId")
                return createErrorResponse("Worker is busy with task ${currentTaskId.get()}")
            }
            
            Log.d(TAG, "🔄 Processing task resumption: $taskId")
            
            // Extract resumption data
            val funcCode = data.optString("func_code", "")
            val checkpointCount = data.optInt("checkpoint_count", 0)
            val isResumed = data.optBoolean("is_resumed", true)
            val recoveryStatus = data.optString("recovery_status", "resumed")
            
            // Parse task_metadata if present (declarative checkpointing configuration)
            val taskMetadataJson = data.optJSONObject("task_metadata")
            val taskMetadata = TaskMetadata.fromJson(taskMetadataJson)
            
            if (taskMetadataJson != null) {
                Log.d(TAG, "Task metadata: checkpoint_enabled=${taskMetadata.checkpointEnabled}, " +
                        "interval=${taskMetadata.checkpointInterval}s, " +
                        "vars=${taskMetadata.checkpointState}")
            }
            
            // Get checkpoint_state as JSON object (new format)
            val checkpointStateObj = data.optJSONObject("checkpoint_state")
            
            // Get task_args/args - the ORIGINAL function arguments
            // This is separate from checkpoint_state and contains the arguments
            // Foreman sends args as "args" (resume) or "task_args" (assign)
            val argsArray: JSONArray? = when {
                data.optJSONArray("args") != null -> data.getJSONArray("args")
                data.optJSONArray("task_args") != null -> data.getJSONArray("task_args")
                data.optString("args", "").isNotEmpty() -> {
                    val argsStr = data.getString("args")
                    JSONArray(if (argsStr.trim().startsWith("[")) argsStr else "[$argsStr]")
                }
                data.optString("task_args", "").isNotEmpty() -> {
                    val argsStr = data.getString("task_args")
                    JSONArray(if (argsStr.trim().startsWith("[")) argsStr else "[$argsStr]")
                }
                else -> null
            }

            // Get kwargs if present (resume message supports "kwargs")
            val kwargsObj = data.optJSONObject("kwargs")

            // Build taskArgsJson in the desktop worker shape: [args, kwargs] when kwargs exist
            val taskArgsJson: String = if (kwargsObj != null && kwargsObj.length() > 0) {
                val combined = JSONArray()
                combined.put(argsArray ?: JSONArray())
                combined.put(kwargsObj)
                combined.toString()
            } else {
                argsArray?.toString() ?: "[]"
            }
            
            // Also support legacy format with reconstructed_state_hex
            val reconstructedStateHex = data.optString("reconstructed_state_hex", "")
            
            if (funcCode.isEmpty()) {
                return createErrorResponse("No function code found in resume task")
            }
            
            if (taskArgsJson == "[]") {
                Log.w(TAG, "No args found in resume_task message - foreman should include original args (checked both 'task_args' and 'args' fields)")
            } else {
                Log.d(TAG, "Found task args: $taskArgsJson")
            }
            
            // Determine checkpoint state JSON
            val checkpointStateJson: String = when {
                checkpointStateObj != null -> {
                    // New format: checkpoint_state is already decoded JSON
                    Log.d(TAG, "Using new checkpoint_state format")
                    checkpointStateObj.toString()
                }
                reconstructedStateHex.isNotEmpty() -> {
                    // Legacy format: hex-encoded gzip-compressed state
                    Log.d(TAG, "Using legacy reconstructed_state_hex format")
                    val reconstructedStateBytes = hexStringToByteArray(reconstructedStateHex)
                    val decompressedState = decompressGzip(reconstructedStateBytes)
                    String(decompressedState, Charsets.UTF_8)
                }
                else -> {
                    return createErrorResponse("No checkpoint state found in resume task")
                }
            }
            
            // Extract progress from checkpoint state for logging
            val progressPercent = checkpointStateObj?.optDouble("progress_percent", 0.0) 
                ?: data.optDouble("progress_percent", 0.0)
            
            Log.i(TAG, "📥 Resuming task $taskId from checkpoint #$checkpointCount")
            Log.i(TAG, "   Progress: $progressPercent%")
            Log.i(TAG, "   Recovery status: $recoveryStatus")
            Log.i(TAG, "   Checkpoint state keys: ${checkpointStateObj?.keys()?.asSequence()?.toList() ?: "N/A"}")
            Log.i(TAG, "   Task args: $taskArgsJson")
            
            // Set current task and job
            currentTaskId.set(taskId)
            currentJobId.set(jobId)
            isTaskRunning.set(true)
            
            try {
                // Start checkpoint monitoring (continuing from where we left off)
                checkpointCallback?.let { callback ->
                    // Configure checkpoint handler from task_metadata if present
                    if (taskMetadataJson != null) {
                        checkpointHandler.configure(taskMetadata)
                    }
                    
                    // Only start monitoring if checkpointing is enabled
                    if (checkpointHandler.isCheckpointingEnabled()) {
                        pythonExecutor.setCheckpointHandler(checkpointHandler)
                        
                        // Initialize checkpoint handler for resumption (continues numbering from where left off)
                        checkpointHandler.initializeFromCheckpoint(checkpointCount)
                        
                        checkpointHandler.startCheckpointMonitoring(
                            taskId = taskId,
                            jobId = jobId,
                            workerId = workerIdManager.getCurrentWorkerId(),
                            scope = processorScope,
                            sendCheckpoint = callback,
                            pollState = { pythonExecutor.pollAndUpdateCheckpointState() }
                        )
                        Log.i(TAG, "✅ Checkpoint monitoring resumed for task $taskId (continuing from checkpoint #$checkpointCount)")
                    } else {
                        Log.d(TAG, "Checkpointing disabled for resumed task $taskId (per task_metadata)")
                    }
                }
                
                // Execute with restored state
                // - checkpointStateJson: sets up builtins._checkpoint_state with _is_resumed=True
                // - taskArgsJson: the original function arguments
                val executionResult = pythonExecutor.executeCodeWithRestoredState(
                    funcCode = funcCode, 
                    checkpointStateJson = checkpointStateJson,
                    taskArgsJson = taskArgsJson
                )
                
                // Create response with recovery_status for resumed tasks
                val response = MessageProtocol.createTaskResultMessageWithRecoveryStatus(
                    taskId = taskId,
                    jobId = jobId,
                    result = executionResult["result"],
                    executionTime = 0.0,
                    recoveryStatus = recoveryStatus
                )
                
                Log.d(TAG, "✅ Resumed task $taskId completed successfully")
                response
                
            } finally {
                // Stop checkpoint monitoring
                checkpointHandler.stopCheckpointMonitoring()
                pythonExecutor.setCheckpointHandler(null)
                Log.i(TAG, "🛑 Checkpoint monitoring stopped for resumed task $taskId")
                
                // Clear current task and job
                currentTaskId.set(null)
                currentJobId.set(null)
                isTaskRunning.set(false)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing task resumption", e)
            currentTaskId.set(null)
            currentJobId.set(null)
            isTaskRunning.set(false)
            MessageProtocol.createTaskErrorMessage(
                taskId = taskId,
                jobId = jobId,
                error = "Task resumption failed: ${e.message ?: "Unknown error"}"
            )
        }
    }
    
    /**
     * Process checkpoint acknowledgment from foreman
     */
    private fun processCheckpointAck(data: JSONObject?): String? {
        return try {
            val taskId = data?.optString("task_id", "") ?: ""
            val checkpointId = data?.optInt("checkpoint_id", 0) ?: 0
            
            Log.d(TAG, " Checkpoint #$checkpointId acknowledged for task $taskId")
            
            // No response needed for ACK messages
            null
            
        } catch (e: Exception) {
            Log.w(TAG, "Error processing checkpoint ACK: ${e.message}")
            null
        }
    }
    
    /**
     * Convert hex string to byte array
     */
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
    
    /**
     * Decompress GZIP data
     */
    private fun decompressGzip(compressed: ByteArray): ByteArray {
        return try {
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(compressed)).use { gis ->
                gis.readBytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decompress gzip, returning original: ${e.message}")
            compressed
        }
    }
    
    /**
     * Process task cancellation request
     */
    private fun processTaskCancellation(data: JSONObject?): String {
        return try {
            val taskId = data?.optString("task_id", "")
            val currentTask = currentTaskId.get()
            
            if (currentTask != null && taskId == currentTask && isTaskRunning.get()) {
                Log.d(TAG, "Cancelling task: $taskId")
                currentTaskId.set(null)
                isTaskRunning.set(false)
                
                val response = JSONObject().apply {
                    put("type", RESPONSE_TYPE_TASK_RESULT)
                    put("task_id", taskId)
                    put("worker_id", workerIdManager.getCurrentWorkerId())
                    put("status", "cancelled")
                    put("message", "Task cancelled successfully")
                    put("timestamp", System.currentTimeMillis())
                }
                response.toString()
            } else {
                createErrorResponse("Task $taskId not found or not running")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing task cancellation", e)
            createErrorResponse("Error cancelling task: ${e.message}")
        }
    }
    
    /**
     * Process status request
     */
    private fun processStatusRequest(): String {
        return try {
            val workerId = workerIdManager.getCurrentWorkerId()
            val environmentInfo = pythonExecutor.getEnvironmentInfo()
            
            val response = JSONObject().apply {
                put("type", RESPONSE_TYPE_STATUS)
                put("worker_id", workerId)
                put("status", "ready")
                put("is_busy", isTaskRunning.get())
                put("current_task", currentTaskId.get())
                put("python_ready", pythonExecutor.isReady())
                put("environment", JSONObject(environmentInfo))
                put("timestamp", System.currentTimeMillis())
            }
            
            Log.d(TAG, "Status request processed")
            response.toString()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing status request", e)
            createErrorResponse("Error getting status: ${e.message}")
        }
    }
    
    /**
     * Process heartbeat request
     */
    private fun processHeartbeat(): String {
        return try {
            val response = JSONObject().apply {
                put("type", "heartbeat_response")
                put("worker_id", workerIdManager.getCurrentWorkerId())
                put("status", "alive")
                put("is_busy", isTaskRunning.get())
                put("timestamp", System.currentTimeMillis())
            }
            
            Log.d(TAG, "Heartbeat processed")
            response.toString()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing heartbeat", e)
            createErrorResponse("Error processing heartbeat: ${e.message}")
        }
    }
    
    /**
     * Extract Python code from task data
     */
    private fun extractPythonCode(data: JSONObject): String? {
        // Try different possible field names for Python code
        val possibleFields = listOf("python_code", "serialized_code", "code", "function_code")
        
        for (field in possibleFields) {
            val code = data.optString(field, "")
            if (code.isNotEmpty()) {
                Log.d(TAG, "Found Python code in field: $field")
                return code
            }
        }
        
        Log.w(TAG, "No Python code found in task data")
        return null
    }
    
    /**
     * Extract Python arguments from task data
     */
    private fun extractPythonArgs(data: JSONObject): String? {
        // Try different possible field names for arguments
        val possibleFields = listOf("python_args", "serialized_args", "args", "arguments", "function_args")
        
        for (field in possibleFields) {
            val args = data.optString(field, "")
            if (args.isNotEmpty()) {
                Log.d(TAG, "Found Python arguments in field: $field")
                return args
            }
        }
        
        Log.d(TAG, "No Python arguments found in task data")
        return null
    }
    
    /**
     * Create error response
     */
    private fun createErrorResponse(message: String): String {
        return try {
            val response = JSONObject().apply {
                put("type", RESPONSE_TYPE_ERROR)
                put("worker_id", workerIdManager.getCurrentWorkerId())
                put("status", "error")
                put("message", message)
                put("timestamp", System.currentTimeMillis())
            }
            response.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating error response", e)
            """{"type":"error_response","status":"error","message":"Failed to create error response"}"""
        }
    }
    
    /**
     * Test task processing functionality
     */
    suspend fun testTaskProcessing(): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Testing task processing...")
                
                // Test Python executor
                val pythonTest = pythonExecutor.testExecution()
                
                // Test status request
                val statusResponse = processStatusRequest()
                val statusJson = JSONObject(statusResponse)
                
                val isSuccess = pythonTest["status"] == "success" && 
                               statusJson.optString("status") == "ready"
                
                mapOf<String, Any>(
                    "status" to if (isSuccess) "success" else "error",
                    "message" to if (isSuccess) "Task processing test passed" else "Task processing test failed",
                    "python_test" to pythonTest,
                    "status_response" to statusJson.toString()
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Task processing test failed", e)
                mapOf<String, Any>(
                    "status" to "error",
                    "message" to "Task processing test failed: ${e.message ?: "Unknown error"}",
                    "error" to e.toString()
                )
            }
        }
    }
    
    /**
     * Get current task status
     */
    fun getCurrentTaskStatus(): Map<String, Any> {
        return mapOf<String, Any>(
            "is_busy" to isTaskRunning.get(),
            "current_task_id" to (currentTaskId.get() ?: ""),  // Use empty string instead of null
            "current_job_id" to (currentJobId.get() ?: ""),    // Include current job ID
            "python_ready" to pythonExecutor.isReady(),
            "processor_initialized" to isInitialized.get()
        )
    }
    
    /**
     * Get Python version from Python executor
     */
    fun getPythonVersion(): String? {
        return try {
            val envInfo = pythonExecutor.getEnvironmentInfo()
            envInfo["python_version"] as? String
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Python version", e)
            null
        }
    }
    
    /**
     * Set the checkpoint callback for sending checkpoint messages
     * This should be called by WorkerWebSocketClient after connection
     */
    fun setCheckpointCallback(callback: suspend (CheckpointMessage) -> Unit) {
        checkpointCallback = callback
        Log.d(TAG, "Checkpoint callback set")
    }
    
    /**
     * Update checkpoint state (called during task execution)
     * This allows Python code to report progress for checkpointing
     */
    fun updateCheckpointState(
        trialsCompleted: Int,
        totalCount: Long,
        numTrials: Int,
        progressPercent: Float,
        estimatedE: Double = 0.0,
        customData: Map<String, Any>? = null
    ) {
        checkpointHandler.updateState(
            CheckpointHandler.CheckpointState(
                trialsCompleted = trialsCompleted,
                totalCount = totalCount,
                numTrials = numTrials,
                progressPercent = progressPercent,
                estimatedE = estimatedE,
                customData = customData
            )
        )
    }
    
    /**
     * Get the checkpoint handler for direct state updates
     */
    fun getCheckpointHandler(): CheckpointHandler {
        return checkpointHandler
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            Log.d(TAG, "Cleaning up TaskProcessor...")
            checkpointHandler.stopCheckpointMonitoring()
            processorScope.cancel()
            currentTaskId.set(null)
            isTaskRunning.set(false)
            isInitialized.set(false)
            pythonExecutor.cleanup()
            Log.d(TAG, " TaskProcessor cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
