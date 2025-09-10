package com.example.mcc_phase3.execution

import android.content.Context
import android.util.Log
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.communication.MessageProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * TaskProcessor handles task assignment and routing
 * This class is responsible for:
 * - Parsing task messages from backend
 * - Routing tasks to appropriate handlers
 * - Managing task execution flow
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
                Log.d(TAG, "✅ TaskProcessor initialized successfully")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize TaskProcessor", e)
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
            
            if (funcCode.isEmpty()) {
                return createErrorResponse("No function code found in task")
            }
            
            // Set current task and job
            currentTaskId.set(taskId)
            currentJobId.set(jobId)
            isTaskRunning.set(true)
            
            try {
                // Execute the function code (matching desktop worker format)
                val executionResult = pythonExecutor.executeCode(funcCode, taskArgs)
                
                // Create response using the new protocol
                val response = MessageProtocol.createTaskResultMessage(
                    taskId = taskId,
                    jobId = jobId, // Use the actual job ID from the message
                    result = executionResult["result"],
                    executionTime = 0.0 // TODO: Calculate actual execution time
                )
                
                Log.d(TAG, "✅ Task $taskId completed successfully")
                response
                
            } finally {
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
     * Cleanup resources
     */
    fun cleanup() {
        try {
            Log.d(TAG, "Cleaning up TaskProcessor...")
            currentTaskId.set(null)
            isTaskRunning.set(false)
            isInitialized.set(false)
            pythonExecutor.cleanup()
            Log.d(TAG, "✅ TaskProcessor cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
