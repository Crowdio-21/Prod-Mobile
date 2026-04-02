package com.example.mcc_phase3.execution

import android.content.Context
import android.util.Log
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.communication.DnnStateStore
import com.example.mcc_phase3.communication.MessageProtocol
import com.example.mcc_phase3.checkpoint.CheckpointHandler
import com.example.mcc_phase3.checkpoint.CheckpointMessage
import com.example.mcc_phase3.checkpoint.TaskMetadata
import com.example.mcc_phase3.model.ModelArtifactCache
import com.example.mcc_phase3.model.ModelArtifactMetadata
import com.example.mcc_phase3.utils.EventLogger
import com.example.mcc_phase3.utils.NotificationHelper
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

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

        /** Maximum wall-clock time (ms) a single task may run before it is force-cancelled. */
        private const val TASK_TIMEOUT_MS = 10 * 60 * 1000L  // 10 minutes

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
    private val dnnStateStore = DnnStateStore(context)
    private val pythonExecutor = PythonExecutor(context)
    private val isInitialized = AtomicBoolean(false)
    
    // Checkpoint handler for periodic progress reporting
    private val checkpointHandler = CheckpointHandler(checkpointIntervalMs = 5000L)
    private val activeTaskSnapshotStore = ActiveTaskSnapshotStore(context)
    private val modelArtifactCache = ModelArtifactCache(context)
    private val modelArtifactRegistry = ConcurrentHashMap<String, ModelArtifactMetadata>()
    private val tflitePartitionExecutor = TFLitePartitionExecutor()
    private val onnxPartitionExecutor = OnnxPartitionExecutor()
    private val processorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Callback for sending checkpoint messages via WebSocket
    private var checkpointCallback: (suspend (CheckpointMessage) -> Unit)? = null
    
    // Task execution tracking
    private val currentTaskId = AtomicReference<String?>(null)
    private val currentJobId = AtomicReference<String?>(null)
    private val isTaskRunning = AtomicBoolean(false)
    private val isTaskPaused = AtomicBoolean(false)
    private val taskStartTime = AtomicReference<Long?>(null)
    
    // Real-time progress tracking (independent of checkpointing)
    private val currentProgress = AtomicReference<Float>(0f)
    private val lastProgressUpdate = AtomicReference<Long>(0L)

    // Pause-aware progress: caches last reported value so the bar doesn't drop to 0% when paused
    private val lastKnownProgress = AtomicReference<Float>(0f)
    // Tracks when the current pause started, and cumulative ms spent paused (for time-based fallback)
    private val pausedAtTime = AtomicReference<Long?>(null)
    private val totalPausedMs = AtomicReference<Long>(0L)

    // Work type detected from incoming func_code
    private val currentWorkType = AtomicReference<String>("Other")
    
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
                val jobId = if (jsonMessage.has("job_id") && !jsonMessage.isNull("job_id")) {
                    jsonMessage.optString("job_id")
                } else {
                    null
                }
                
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
            val executionContext = parseExecutionContext(data, taskMetadataJson)
            var modelPartitionId = executionContext.modelPartitionId
            
            if (taskMetadataJson != null) {
                Log.d(TAG, "Task metadata: checkpoint_enabled=${taskMetadata.checkpointEnabled}, " +
                        "interval=${taskMetadata.checkpointInterval}s, " +
                        "vars=${taskMetadata.checkpointState}")
            }
            
            val isNativeExecution = isNativeExecutionMode(executionContext.executionMode)

            if (funcCode.isEmpty() && !isNativeExecution) {
                return createErrorResponse("No function code found in task")
            }

            if (modelPartitionId.isBlank()) {
                modelPartitionId = inferSingleCachedPartitionId()
            }

            val fallbackMode = dnnStateStore.getFallbackModeForTask(taskId)
            val normalizedFallbackMode = normalizeFallbackMode(fallbackMode)

            if (modelPartitionId.isNotBlank()) {
                val currentWorkerId = workerIdManager.getCurrentWorkerId()
                val assignedWorkerId = dnnStateStore.getAssignedWorkerForPartition(modelPartitionId)
                val enforceAssignment = !isStandaloneFallbackMode(normalizedFallbackMode)
                if (enforceAssignment && !assignedWorkerId.isNullOrBlank() && !currentWorkerId.isNullOrBlank() && assignedWorkerId != currentWorkerId) {
                    Log.w(TAG, "Task $taskId partition $modelPartitionId is assigned to $assignedWorkerId, not $currentWorkerId")
                    return MessageProtocol.createTaskErrorMessage(
                        taskId = taskId,
                        jobId = jobId,
                        error = "Partition $modelPartitionId currently assigned to another worker"
                    )
                }
            }

            val modelMetadata = if (modelPartitionId.isNotBlank()) {
                modelArtifactRegistry[modelPartitionId] ?: modelArtifactCache.getArtifact(modelPartitionId)
            } else {
                null
            }

            if (modelPartitionId.isNotBlank() && modelMetadata == null && fallbackMode.isNullOrBlank()) {
                Log.e(TAG, "Model partition $modelPartitionId is required but not loaded")
                return MessageProtocol.createTaskErrorMessage(
                    taskId = taskId,
                    jobId = jobId,
                    error = "Required model partition '$modelPartitionId' not loaded yet"
                )
            }
            
            // Detect and store work type before execution starts
            currentWorkType.set(detectWorkType(funcCode))
            Log.d(TAG, "Work type detected: ${currentWorkType.get()} for task $taskId")

            // Set current task and job
            currentTaskId.set(taskId)
            currentJobId.set(jobId)
            isTaskRunning.set(true)
            taskStartTime.set(System.currentTimeMillis())
            activeTaskSnapshotStore.save(
                ActiveTaskSnapshot(
                    taskId = taskId,
                    jobId = jobId,
                    modelPartitionId = modelPartitionId.ifBlank { null },
                    startedAtEpochMs = taskStartTime.get() ?: System.currentTimeMillis(),
                    isResume = false
                )
            )
            currentProgress.set(0f)
            lastKnownProgress.set(0f)
            pausedAtTime.set(null)
            totalPausedMs.set(0L)
            Log.d(TAG, "Task $taskId started at ${taskStartTime.get()}")
            EventLogger.info(EventLogger.Categories.TASK, "Task $taskId started (Job: $jobId)")
            NotificationHelper.notifyTaskAssigned(context, taskId, jobId)
            
            var progressPollingJob: Job? = null
            var taskTimeoutJob: Job? = null
            
            try {
                // Set up real-time progress callback (works with or without checkpointing)
                pythonExecutor.setProgressCallback { progress ->
                    updateProgress(progress)
                }

                pythonExecutor.setCurrentModelPartitionContext(modelMetadata)

                // Watchdog: cancel this coroutine if the task exceeds TASK_TIMEOUT_MS
                taskTimeoutJob = processorScope.launch {
                    delay(TASK_TIMEOUT_MS)
                    if (isTaskRunning.get()) {
                        Log.e(TAG, "Task $taskId exceeded timeout (${TASK_TIMEOUT_MS / 1000}s) – force-failing")
                        EventLogger.error(EventLogger.Categories.TASK, "Task $taskId timed out after ${TASK_TIMEOUT_MS / 1000}s")
                        // Deliver empty images in case the picker is hanging
                        ImagePickerManager.getInstance().deliverImages(emptyList())
                    }
                }
                
                // Start progress polling in background (independent of checkpointing)
                progressPollingJob = processorScope.launch {
                    while (isTaskRunning.get()) {
                        try {
                            pythonExecutor.pollProgressAndUpdate()
                            delay(1000) // Poll every second
                        } catch (e: Exception) {
                            Log.d(TAG, "Progress polling interrupted: ${e.message}")
                            break
                        }
                    }
                }
                
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
                        Log.i(TAG, "Checkpoint monitoring started for task $taskId")
                        EventLogger.info(EventLogger.Categories.CHECKPOINT, "Checkpoint monitoring started for task $taskId")
                    } else {
                        Log.d(TAG, "Checkpointing disabled for task $taskId (per task_metadata)")
                    }
                }
                
                    val shouldRunNativeModel = when {
                        modelMetadata == null -> false
                        isNativeExecution && normalizedFallbackMode == null -> true
                        isStandaloneFallbackMode(normalizedFallbackMode) -> true
                        else -> false
                    }

                    val executionResult = if (shouldRunNativeModel) {
                        if (!normalizedFallbackMode.isNullOrBlank()) {
                            Log.i(TAG, "Applying fallback mode '$normalizedFallbackMode' with local native-model execution for task $taskId")
                        }
                        runNativeModelExecution(
                            taskId = taskId,
                            modelMetadata = modelMetadata!!,
                            taskArgsJson = taskArgs,
                            executionMode = executionContext.executionMode
                        )
                    } else {
                    if (!normalizedFallbackMode.isNullOrBlank()) {
                        Log.i(TAG, "Applying fallback mode '$normalizedFallbackMode' with Python execution for task $taskId")
                    }
                    pythonExecutor.executeCode(funcCode, taskArgs)
                }

                val executionStatus = executionResult["status"] as? String ?: "error"
                val executionMessage = executionResult["message"] as? String ?: "Unknown error"

                // Route to error message if Python reported a failure
                val response = if (executionStatus == "error") {
                    Log.w(TAG, "Task $taskId finished with Python error: $executionMessage")
                    EventLogger.error(EventLogger.Categories.TASK, "Task $taskId Python error: $executionMessage")
                    MessageProtocol.createTaskErrorMessage(
                        taskId = taskId,
                        jobId = jobId,
                        error = executionMessage
                    )
                } else {
                    // Ensure result is never null — fall back to empty JSON object
                    val rawResult = executionResult["result"]
                    val safeResult: Any = when {
                        rawResult == null -> "{}"
                        rawResult is String && rawResult.isBlank() -> "{}"
                        else -> rawResult
                    }
                    MessageProtocol.createTaskResultMessage(
                        taskId = taskId,
                        jobId = jobId,
                        result = safeResult,
                        executionTime = 0.0
                    )
                }

                Log.d(TAG, "Task $taskId completed (status=$executionStatus)")
                EventLogger.success(EventLogger.Categories.TASK, "Task $taskId completed successfully")
                NotificationHelper.notifyTaskCompleted(context, taskId)
                response
                
            } finally {
                // Cancel watchdog and progress polling
                taskTimeoutJob?.cancel()
                progressPollingJob?.cancel()
                
                // Stop checkpoint monitoring
                checkpointHandler.stopCheckpointMonitoring()
                pythonExecutor.setCheckpointHandler(null)
                pythonExecutor.setProgressCallback(null)
                pythonExecutor.setCurrentModelPartitionContext(null)
                Log.i(TAG, " Checkpoint monitoring stopped for task $taskId (sent ${checkpointHandler.getCheckpointCount()} checkpoints)")
                
                // Clear current task, job and work type
                currentTaskId.set(null)
                currentJobId.set(null)
                isTaskRunning.set(false)
                activeTaskSnapshotStore.clear()
                taskStartTime.set(null)
                currentProgress.set(0f)
                lastKnownProgress.set(0f)
                pausedAtTime.set(null)
                totalPausedMs.set(0L)
                currentWorkType.set("Other")
                dnnStateStore.clearFallbackModeForTask(taskId)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing task assignment", e)
            EventLogger.error(EventLogger.Categories.TASK, "Task $taskId failed: ${e.message}")
            NotificationHelper.notifyTaskFailed(context, taskId, e.message)
            currentTaskId.set(null)
            currentJobId.set(null)
            isTaskRunning.set(false)
            activeTaskSnapshotStore.clear()
            taskStartTime.set(null)
            currentProgress.set(0f)
            lastKnownProgress.set(0f)
            pausedAtTime.set(null)
            totalPausedMs.set(0L)
            currentWorkType.set("Other")
            dnnStateStore.clearFallbackModeForTask(taskId)
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
            
            Log.d(TAG, "Processing task resumption: $taskId")
            
            // Extract resumption data
            val funcCode = data.optString("func_code", "")
            val checkpointCount = data.optInt("checkpoint_count", 0)
            val isResumed = data.optBoolean("is_resumed", true)
            val recoveryStatus = data.optString("recovery_status", "resumed")
            
            // Parse task_metadata if present (declarative checkpointing configuration)
            val taskMetadataJson = data.optJSONObject("task_metadata")
            val taskMetadata = TaskMetadata.fromJson(taskMetadataJson)
            val executionContext = parseExecutionContext(data, taskMetadataJson)
            var modelPartitionId = executionContext.modelPartitionId
            if (modelPartitionId.isBlank()) {
                modelPartitionId = inferSingleCachedPartitionId()
            }
            val modelMetadata = if (modelPartitionId.isNotBlank()) {
                modelArtifactRegistry[modelPartitionId] ?: modelArtifactCache.getArtifact(modelPartitionId)
            } else {
                null
            }
            
            if (taskMetadataJson != null) {
                Log.d(TAG, "Task metadata: checkpoint_enabled=${taskMetadata.checkpointEnabled}, " +
                        "interval=${taskMetadata.checkpointInterval}s, " +
                        "vars=${taskMetadata.checkpointState}")
            }

            val isNativeExecution = isNativeExecutionMode(executionContext.executionMode)

            if (funcCode.isEmpty() && !isNativeExecution) {
                return createErrorResponse("No function code found in resume task")
            }

            if (isNativeExecution && modelMetadata == null) {
                return createErrorResponse("No model metadata found for native model resume")
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
            
            if (taskArgsJson == "[]") {
                Log.w(TAG, "No args found in resume_task message - foreman should include original args (checked both 'task_args' and 'args' fields)")
            } else {
                Log.d(TAG, "Found task args: $taskArgsJson")
            }
            
            // Determine checkpoint state JSON
            val checkpointStateJson: String? = when {
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
                else -> null
            }

            if (!isNativeExecution && checkpointStateJson == null) {
                return createErrorResponse("No checkpoint state found in resume task")
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
            activeTaskSnapshotStore.save(
                ActiveTaskSnapshot(
                    taskId = taskId,
                    jobId = jobId,
                    modelPartitionId = modelPartitionId.ifBlank { null },
                    startedAtEpochMs = System.currentTimeMillis(),
                    isResume = true
                )
            )
            
            try {
                pythonExecutor.setCurrentModelPartitionContext(modelMetadata)
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
                        Log.i(TAG, "Checkpoint monitoring resumed for task $taskId (continuing from checkpoint #$checkpointCount)")
                    } else {
                        Log.d(TAG, "Checkpointing disabled for resumed task $taskId (per task_metadata)")
                    }
                }
                
                val executionResult = if (isNativeExecution && modelMetadata != null) {
                    runNativeModelExecution(
                        taskId = taskId,
                        modelMetadata = modelMetadata,
                        taskArgsJson = taskArgsJson,
                        executionMode = executionContext.executionMode
                    )
                } else {
                    // Execute with restored state
                    // - checkpointStateJson: sets up builtins._checkpoint_state with _is_resumed=True
                    // - taskArgsJson: the original function arguments
                    pythonExecutor.executeCodeWithRestoredState(
                        funcCode = funcCode,
                        checkpointStateJson = checkpointStateJson ?: "{}",
                        taskArgsJson = taskArgsJson
                    )
                }
                
                // Create response with recovery_status for resumed tasks
                val response = MessageProtocol.createTaskResultMessageWithRecoveryStatus(
                    taskId = taskId,
                    jobId = jobId,
                    result = executionResult["result"],
                    executionTime = 0.0,
                    recoveryStatus = recoveryStatus
                )
                
                Log.d(TAG, "Resumed task $taskId completed successfully")
                response
                
            } finally {
                // Stop checkpoint monitoring
                checkpointHandler.stopCheckpointMonitoring()
                pythonExecutor.setCheckpointHandler(null)
                pythonExecutor.setCurrentModelPartitionContext(null)
                Log.i(TAG, "🛑 Checkpoint monitoring stopped for resumed task $taskId")
                
                // Clear current task and job
                currentTaskId.set(null)
                currentJobId.set(null)
                isTaskRunning.set(false)
                activeTaskSnapshotStore.clear()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing task resumption", e)
            currentTaskId.set(null)
            currentJobId.set(null)
            isTaskRunning.set(false)
            activeTaskSnapshotStore.clear()
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

    fun registerModelArtifact(metadata: ModelArtifactMetadata) {
        if (metadata.modelPartitionId.isBlank()) {
            return
        }
        modelArtifactRegistry[metadata.modelPartitionId] = metadata
        Log.i(TAG, "Registered model partition metadata for ${metadata.modelPartitionId}")
    }

    fun unregisterModelArtifact(modelPartitionId: String) {
        if (modelPartitionId.isBlank()) {
            return
        }

        val removed = modelArtifactRegistry.remove(modelPartitionId)
        if (removed != null) {
            // Close any cached OrtSession for this model file
            onnxPartitionExecutor.closeSession(removed.localPath)
            Log.i(TAG, "Unregistered model partition metadata for $modelPartitionId")
        }
    }

    fun getPendingRecoverySnapshot(): ActiveTaskSnapshot? {
        if (isTaskRunning.get()) {
            return null
        }
        return activeTaskSnapshotStore.load()
    }

    fun clearPendingRecoverySnapshot() {
        activeTaskSnapshotStore.clear()
    }

    fun onDeviceTopology(data: JSONObject) {
        dnnStateStore.onDeviceTopology(data)
    }

    fun onTopologyUpdate(data: JSONObject) {
        // Duplicate filtering is handled by WorkerWebSocketClient.
        dnnStateStore.onDeviceTopology(data)
    }

    fun onAggregationConfig(data: JSONObject) {
        dnnStateStore.onAggregationConfig(data)
    }

    fun onFallbackDecision(data: JSONObject) {
        dnnStateStore.isDuplicateFallbackDecision(data)
    }

    private fun normalizeFallbackMode(mode: String?): String? {
        val normalized = mode?.trim()?.lowercase()
        return if (normalized.isNullOrBlank()) null else normalized
    }

    private fun isStandaloneFallbackMode(mode: String?): Boolean {
        if (mode.isNullOrBlank()) {
            return false
        }

        return mode.contains("standalone") || mode.contains("local")
    }

    private fun inferSingleCachedPartitionId(): String {
        val registered = modelArtifactRegistry.keys.toList()
        if (registered.size == 1) {
            return registered.first()
        }

        val cached = modelArtifactCache.allArtifacts()
        return if (cached.size == 1) cached.first().modelPartitionId else ""
    }

    private fun parseExecutionContext(
        data: JSONObject,
        taskMetadataJson: JSONObject?
    ): ExecutionContext {
        val executionMetadata = data.optJSONObject("execution_metadata")

        val executionMode = firstNonBlank(
            data.optString("execution_mode", ""),
            executionMetadata?.optString("execution_mode", "") ?: "",
            taskMetadataJson?.optString("execution_mode", "") ?: ""
        )?.lowercase()

        val modelPartitionId = firstNonBlank(
            data.optString("model_partition_id", ""),
            executionMetadata?.optString("model_partition_id", "") ?: "",
            taskMetadataJson?.optString("model_partition_id", "") ?: ""
        ).orEmpty()

        return ExecutionContext(
            executionMode = executionMode,
            modelPartitionId = modelPartitionId
        )
    }

    private fun isNativeExecutionMode(executionMode: String?): Boolean {
        val mode = executionMode?.trim()?.lowercase() ?: return false
        return mode == "native_model" || mode == "onnx" || mode == "onnx_model" || mode == "tflite" || mode == "tflite_model"
    }

    private fun resolveNativeRuntime(executionMode: String?, modelMetadata: ModelArtifactMetadata): String {
        val mode = executionMode?.trim()?.lowercase()
        if (mode == "onnx" || mode == "onnx_model") {
            return "onnx"
        }
        if (mode == "tflite" || mode == "tflite_model") {
            return "tflite"
        }

        val metadataRuntime = modelMetadata.modelRuntime.trim().lowercase()
        if (metadataRuntime == "onnx" || metadataRuntime == "tflite") {
            return metadataRuntime
        }

        return if (mode == "native_model") "onnx" else "unknown"
    }

    private fun runNativeModelExecution(
        taskId: String,
        modelMetadata: ModelArtifactMetadata,
        taskArgsJson: String,
        executionMode: String?
    ): Map<String, Any> {
        val modelPath = modelMetadata.localPath
        if (modelPath.isBlank()) {
            return mapOf(
                "status" to "error",
                "message" to "Model metadata/path missing for native model execution",
                "result" to "{}"
            )
        }

        val runtime = resolveNativeRuntime(executionMode, modelMetadata)

        return try {
            val nativeResult = when (runtime) {
                "onnx" -> onnxPartitionExecutor.execute(
                    modelPath = modelPath,
                    taskArgsJson = taskArgsJson
                )
                "tflite" -> tflitePartitionExecutor.execute(
                    modelPath = modelPath,
                    taskArgsJson = taskArgsJson
                )
                else -> throw IllegalStateException("Unsupported native runtime '$runtime' for task $taskId")
            }

            mapOf(
                "status" to "success",
                "message" to "${runtime.uppercase()} partition executed",
                "result" to nativeResult.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Native model execution failed for task $taskId with runtime=$runtime", e)
            mapOf(
                "status" to "error",
                "message" to "Native model execution failed: ${e.message}",
                "result" to "{}"
            )
        }
    }

    private fun firstNonBlank(vararg values: String): String? {
        for (value in values) {
            val trimmed = value.trim()
            if (trimmed.isNotBlank()) {
                return trimmed
            }
        }
        return null
    }

    private data class ExecutionContext(
        val executionMode: String?,
        val modelPartitionId: String
    )
    
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
     * Classify the nature of a task from its function source code.
     * Returns one of: "Image Processing", "Monte Carlo", "Sentiment Analysis", "Other"
     */
    private fun detectWorkType(funcCode: String): String {
        val code = funcCode.lowercase()
        return when {
            code.contains("process_images") ||
            code.contains("from pil import") ||
            code.contains("import cv2") ||
            (code.contains("image") && (code.contains("glob") || code.contains(".jpg") || code.contains(".png"))) ->
                "Image Processing"

            code.contains("monte_carlo") ||
            code.contains("montecarlo") ||
            (code.contains("random") && (code.contains("simulation") || code.contains("pi_estim") || code.contains("estimate"))) ->
                "Monte Carlo"

            code.contains("sentiment") ||
            code.contains("textblob") ||
            code.contains("from transformers") ||
            code.contains("import transformers") ||
            (code.contains("nlp") && code.contains("text")) ->
                "Sentiment Analysis"

            else -> "Other"
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
        return try {
            // Capture all atomic values at once to avoid race conditions
            val isBusy = isTaskRunning.get()
            val taskId = currentTaskId.get() ?: ""
            val jobId = currentJobId.get() ?: ""
            val startTime = taskStartTime.get()

            // Get progress from multiple sources (priority order)
            var progressPercent = currentProgress.get()  // 1. Real-time progress from Python callback

            // 2. Fallback to checkpoint progress if no real-time updates
            if (progressPercent <= 0f) {
                val currentCheckpointState = checkpointHandler.currentState.value
                progressPercent = currentCheckpointState?.progressPercent ?: 0f
            }

            // 3. Fallback to time-based progress if no other source
            //    Skip when paused – the task isn't making progress, so the bar should freeze.
            if (progressPercent <= 0f && isBusy && !isTaskPaused.get() && startTime != null) {
                val elapsedMs = System.currentTimeMillis() - startTime - totalPausedMs.get()
                // Simulate progress: 0-99% spread evenly across the full task timeout window.
                progressPercent = ((elapsedMs / TASK_TIMEOUT_MS.toFloat()) * 99f).coerceIn(0f, 99f)
                Log.d(TAG, "Using time-based fallback progress: $progressPercent% (elapsed: ${elapsedMs}ms)")
            }

            // When paused and no live source reported a value, return the cached progress
            if (isTaskPaused.get() && progressPercent <= 0f) {
                progressPercent = lastKnownProgress.get()
            }

            // Cache the latest non-zero progress so we can show it while paused
            if (progressPercent > 0f) {
                lastKnownProgress.set(progressPercent)
            }

            Log.d(TAG, "getCurrentTaskStatus - busy: $isBusy, taskId: $taskId, progress: $progressPercent%")

            mapOf<String, Any>(
                "is_busy" to isBusy,
                "is_paused" to isTaskPaused.get(),
                "current_task_id" to taskId,
                "current_job_id" to jobId,
                "python_ready" to pythonExecutor.isReady(),
                "processor_initialized" to isInitialized.get(),
                "progress_percent" to progressPercent,
                "work_type" to currentWorkType.get()
            )
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM while building task status snapshot", oom)
            System.gc()
            mapOf<String, Any>(
                "is_busy" to isTaskRunning.get(),
                "is_paused" to isTaskPaused.get(),
                "current_task_id" to (currentTaskId.get() ?: ""),
                "current_job_id" to (currentJobId.get() ?: ""),
                "python_ready" to false,
                "processor_initialized" to isInitialized.get(),
                "progress_percent" to 0.0,
                "work_type" to currentWorkType.get()
            )
        }
    }
    
    /**
     * Update real-time task progress (independent of checkpointing)
     * This can be called by Python code via callback to report progress
     * Works even when checkpointing is disabled
     * 
     * @param progressPercent Progress percentage (0-100)
     */
    fun updateProgress(progressPercent: Float) {
        if (isTaskRunning.get()) {
            val clampedProgress = progressPercent.coerceIn(0f, 100f)
            val previousProgress = currentProgress.get()
            currentProgress.set(clampedProgress)
            lastProgressUpdate.set(System.currentTimeMillis())
            Log.d(TAG, "Progress updated: $clampedProgress%")
            
            // Log milestone events (every 25%)
            val prevMilestone = (previousProgress / 25).toInt()
            val currMilestone = (clampedProgress / 25).toInt()
            if (currMilestone > prevMilestone) {
                val taskId = currentTaskId.get() ?: "unknown"
                EventLogger.info(EventLogger.Categories.PROGRESS, "Task $taskId: ${clampedProgress.toInt()}% complete")
            }
        }
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
     * This allows Python code to report progress for checkpointing.
     * Uses a generic Map-based approach - no hardcoded variable names.
     * 
     * @param stateData Map of state variable names to values
     */
    fun updateCheckpointState(stateData: Map<String, Any>) {
        checkpointHandler.updateState(
            CheckpointHandler.CheckpointState.fromMap(stateData)
        )
    }
    
    /**
     * Legacy overload for backwards compatibility - converts to generic Map
     */
    @Deprecated("Use updateCheckpointState(Map) instead for generic state handling")
    fun updateCheckpointState(
        trialsCompleted: Int,
        totalCount: Long,
        numTrials: Int,
        progressPercent: Float,
        estimatedE: Double = 0.0,
        customData: Map<String, Any>? = null
    ) {
        val stateData = mutableMapOf<String, Any>(
            "trials_completed" to trialsCompleted,
            "total_count" to totalCount,
            "num_trials" to numTrials,
            "progress_percent" to progressPercent,
            "estimated_e" to estimatedE
        )
        customData?.let { stateData.putAll(it) }
        checkpointHandler.updateState(
            CheckpointHandler.CheckpointState.fromMap(stateData)
        )
    }
    
    /**
     * Get the checkpoint handler for direct state updates
     */
    fun getCheckpointHandler(): CheckpointHandler {
        return checkpointHandler
    }
    
    /**
     * Pause the currently running task.
     * Sets the cooperative `paused` flag inside the Python exec namespace.
     */
    fun pauseCurrentTask() {
        if (!isTaskRunning.get()) {
            Log.w(TAG, "pauseCurrentTask: no task running")
            return
        }
        pythonExecutor.pauseExecution()
        isTaskPaused.set(true)
        pausedAtTime.set(System.currentTimeMillis())
        Log.d(TAG, "Task ${currentTaskId.get()} paused")
    }

    /**
     * Resume a paused task.
     */
    fun resumeCurrentTask() {
        if (!isTaskPaused.get()) {
            Log.w(TAG, "resumeCurrentTask: task is not paused")
            return
        }
        // Accumulate paused duration so time-based fallback stays accurate
        val pauseStart = pausedAtTime.getAndSet(null)
        if (pauseStart != null) {
            totalPausedMs.set(totalPausedMs.get() + (System.currentTimeMillis() - pauseStart))
        }
        pythonExecutor.resumeExecution()
        isTaskPaused.set(false)
        Log.d(TAG, "Task ${currentTaskId.get()} resumed")
    }

    /**
     * Kill (cancel) the currently running task.
     */
    fun killCurrentTask() {
        if (!isTaskRunning.get()) {
            Log.w(TAG, "killCurrentTask: no task running")
            return
        }
        // If the task is paused, resume it first so the Python `while paused`
        // loop exits and the code reaches the `if killed` check.
        if (isTaskPaused.get()) {
            pythonExecutor.resumeExecution()
        }
        pythonExecutor.killExecution()
        isTaskPaused.set(false)
        Log.d(TAG, "Task ${currentTaskId.get()} killed")
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
            isTaskPaused.set(false)
            activeTaskSnapshotStore.clear()
            lastKnownProgress.set(0f)
            pausedAtTime.set(null)
            totalPausedMs.set(0L)
            isInitialized.set(false)
            pythonExecutor.cleanup()
            Log.d(TAG, " TaskProcessor cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
