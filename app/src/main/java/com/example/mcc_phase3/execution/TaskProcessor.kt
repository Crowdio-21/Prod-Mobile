package com.example.mcc_phase3.execution

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.communication.MessageProtocol
import com.example.mcc_phase3.checkpoint.CheckpointHandler
import com.example.mcc_phase3.checkpoint.CheckpointMessage
import com.example.mcc_phase3.checkpoint.TaskMetadata
import com.example.mcc_phase3.data.ConfigManager
import com.example.mcc_phase3.utils.EventLogger
import com.example.mcc_phase3.utils.NotificationHelper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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

        /** Maximum wall-clock time (ms) a single task may run before it is force-cancelled. */
        private const val TASK_TIMEOUT_MS = 10 * 60 * 1000L  // 10 minutes
        private const val MAX_TRANSIENT_RETRIES = 1
        // Keep websocket payloads small for mobile reliability. Inline base64 tensor blobs
        // should be avoided; pass references/URIs instead.
        private const val MAX_OUTPUT_TENSOR_B64_CHARS = 700 * 1024
        private const val DEFAULT_TENSOR_UPLOAD_PORT = 8001
        private const val TENSOR_UPLOAD_PATH = "/upload"
        private const val DEFAULT_MODEL_ARTIFACT_HTTP_PORT = 8001
        private const val DEFAULT_MODEL_STORE_HTTP_PREFIX = "/.model_store"

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
    private val modelRepository = ModelRepository(context)
    private val configManager = ConfigManager.getInstance(context)
    private val tensorUploadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
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
    private val completedPartitionIdxByJob = ConcurrentHashMap<String, Int>()

    // --------------- Task queue for DNN parallel branches ---------------
    // When a task arrives while the worker is busy, it is queued here and
    // drained sequentially so no task is ever rejected.
    private data class QueuedTask(val data: JSONObject, val jobId: String?, val result: CompletableDeferred<String>)
    private val taskQueue = Channel<QueuedTask>(Channel.UNLIMITED)

    private data class StageInfo(
        val stageName: String,
        val partitionIdx: Int? = null
    )

    private data class TflitePreparationResult(
        val taskArgs: String,
        val telemetry: JSONObject?
    )

    private data class TensorUploadResult(
        val fileUrl: String?,
        val error: String?
    )

    init {
        // Single consumer coroutine — processes queued tasks one at a time
        processorScope.launch {
            for (queued in taskQueue) {
                try {
                    val response = processTaskAssignmentInternal(queued.data, queued.jobId)
                    queued.result.complete(response)
                } catch (e: Exception) {
                    val errorResp = MessageProtocol.createTaskErrorMessage(
                        taskId = queued.data.optString("task_id", "unknown"),
                        jobId = queued.jobId,
                        error = "Queued task failed: ${e.message}"
                    )
                    queued.result.complete(errorResp)
                }
            }
        }
    }
    
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

                try {
                    modelRepository.cleanupOnStartup()
                } catch (e: Exception) {
                    Log.w(TAG, "Model cache startup cleanup failed: ${e.message}")
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
     * Process task assignment from backend.
     * If the worker is idle the task runs immediately; otherwise it is queued
     * and executed once the current (and any earlier queued) task completes.
     */
    private suspend fun processTaskAssignment(data: JSONObject?, jobId: String?): String {
        // Declare taskId outside try-catch so it's accessible in catch block
        val taskId = data?.optString("task_id", "") ?: ""
        
        if (data == null) return createErrorResponse("No task data provided")
        if (taskId.isEmpty()) return createErrorResponse("No task ID provided")

        // If a task is already running, queue this one instead of rejecting it
        if (isTaskRunning.get()) {
            Log.i(TAG, "Worker busy with ${currentTaskId.get()}, queuing task $taskId")
            EventLogger.info(EventLogger.Categories.TASK, "Task $taskId queued (worker busy)")
            val deferred = CompletableDeferred<String>()
            taskQueue.trySend(QueuedTask(data, jobId, deferred))
            return deferred.await()
        }

        return processTaskAssignmentInternal(data, jobId)
    }

    /**
     * Internal task execution — always called when the worker is free.
     */
    private suspend fun processTaskAssignmentInternal(data: JSONObject?, jobId: String?): String {
        val taskId = data?.optString("task_id", "") ?: ""
        
        return try {
            Log.d(TAG, "Processing task assignment: $taskId")
            
            // Extract function code and task arguments (matching desktop worker format)
            val funcCode = data?.optString("func_code", "") ?: ""
            val taskArgs = data?.optString("task_args", "") ?: ""
            val stageInfo = inspectStage(funcCode, taskArgs)
            val routePolicy = routePolicyForStage(stageInfo)
            Log.i(TAG, "Stage detected: ${stageInfo.stageName} partition=${stageInfo.partitionIdx} route=$routePolicy")

            if (stageInfo.stageName == "partition_model") {
                Log.w(TAG, "partition_model should run on desktop TensorFlow worker; Android execution will use fallback behavior")
            }
            
            // Parse task_metadata if present (declarative checkpointing configuration)
            val taskMetadataJson = data?.optJSONObject("task_metadata")
            val taskMetadata = TaskMetadata.fromJson(taskMetadataJson)
            
            if (taskMetadataJson != null) {
                Log.d(TAG, "Task metadata: checkpoint_enabled=${taskMetadata.checkpointEnabled}, " +
                        "interval=${taskMetadata.checkpointInterval}s, " +
                        "vars=${taskMetadata.checkpointState}")
            }
            
            if (funcCode.isEmpty()) {
                return createErrorResponse("No function code found in task")
            }

            if (!isStageOrderSatisfied(jobId, stageInfo)) {
                val expected = ((completedPartitionIdxByJob[jobId] ?: -1) + 1)
                return MessageProtocol.createTaskErrorMessage(
                    taskId = taskId,
                    jobId = jobId,
                    error = "Stage order violation: ${stageInfo.stageName} partition=${stageInfo.partitionIdx}, expected partition=$expected"
                )
            }

            val normalizedTaskArgs = normalizeTaskArgsForStage(taskArgs, stageInfo)
            var modelTelemetry: JSONObject? = null
            val finalTaskArgs = if (stageInfo.stageName == "tflite_partition") {
                val prepared = prepareTflitePartitionArgs(normalizedTaskArgs, data, stageInfo.partitionIdx)
                modelTelemetry = prepared.telemetry
                prepared.taskArgs
            } else {
                normalizedTaskArgs
            }

            val finalFuncCode = if (stageInfo.stageName == "tflite_partition") {
                val probe = pythonExecutor.probeTfliteBackend()
                if (!probe.available) {
                    return MessageProtocol.createTaskErrorMessage(
                        taskId = taskId,
                        jobId = jobId,
                        error = "No usable TFLite backend on Android worker (backend=${probe.backend}). ${probe.details}"
                    )
                }
                Log.i(TAG, "TFLite backend probe passed: backend=${probe.backend} details=${probe.details}")
                // Rewrite payload so Android uses pre-resolved local model file.
                val rewrittenCode = funcCode
                    .replace(
                        Regex("def\\s+run_tflite_partition\\s*\\(\\s*task_input\\s*\\):"),
                        "def run_tflite_partition(task_input):\n    local_model_path = task_input.get('local_model_path')\n    if local_model_path and not task_input.get('tflite_b64_str'):\n        with open(local_model_path, 'rb') as _f:\n            task_input['tflite_b64_str'] = base64.b64encode(_f.read()).decode('utf-8')"
                    )
                    .replace(
                        Regex("def\\s+_run_tflite\\s*\\(\\s*tflite_b64_str\\s*,\\s*input_array\\s*\\):"),
                        "def _run_tflite(input_array):"
                    )
                    .replace(
                        Regex("_run_tflite\\s*\\(\\s*tflite_b64_str\\s*,\\s*input_array\\s*\\)"),
                        "_run_tflite(input_array)"
                    )
                    .replace(
                        Regex("tflite_bytes\\s*=\\s*base64\\.b64decode\\(tflite_b64_str\\)"),
                        "tflite_bytes = open(task_input['local_model_path'], 'rb').read()"
                    )
                val rewriteApplied = rewrittenCode != funcCode
                val oldSigStillPresent = Regex("def\\s+_run_tflite\\s*\\(\\s*tflite_b64_str").containsMatchIn(rewrittenCode)
                val oldCallStillPresent = Regex("_run_tflite\\s*\\(\\s*tflite_b64_str\\s*,").containsMatchIn(rewrittenCode)
                val base64DecodeStillPresent = Regex("base64\\.b64decode\\(tflite_b64_str\\)").containsMatchIn(rewrittenCode)
                Log.i(
                    TAG,
                    "TFLite payload rewrite applied=$rewriteApplied oldSigStillPresent=$oldSigStillPresent oldCallStillPresent=$oldCallStillPresent base64DecodeStillPresent=$base64DecodeStillPresent"
                )
                rewrittenCode
            } else {
                funcCode
            }
            
            // Detect and store work type before execution starts
            currentWorkType.set(detectWorkType(finalFuncCode))
            Log.d(TAG, "Work type detected: ${currentWorkType.get()} for task $taskId")

            // Set current task and job
            currentTaskId.set(taskId)
            currentJobId.set(jobId)
            isTaskRunning.set(true)
            taskStartTime.set(System.currentTimeMillis())
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
                
                // Execute the function code (matching desktop worker format)
                val executionResult = executeCodeWithRetry(finalFuncCode, finalTaskArgs, stageInfo)

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
                    val normalized = normalizeAndValidateResult(rawResult, stageInfo, modelTelemetry)
                    if (normalized.optString("status") == "error") {
                        return MessageProtocol.createTaskErrorMessage(
                            taskId = taskId,
                            jobId = jobId,
                            error = normalized.optString("error", "Malformed task result")
                        )
                    }

                    val safeResult: Any = normalized.optString("result", "{}")

                    if (jobId != null && stageInfo.stageName == "tflite_partition" && stageInfo.partitionIdx != null) {
                        completedPartitionIdxByJob[jobId] = maxOf(
                            completedPartitionIdxByJob[jobId] ?: -1,
                            stageInfo.partitionIdx
                        )
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
                Log.i(TAG, " Checkpoint monitoring stopped for task $taskId (sent ${checkpointHandler.getCheckpointCount()} checkpoints)")
                
                // Clear current task, job and work type
                currentTaskId.set(null)
                currentJobId.set(null)
                isTaskRunning.set(false)
                taskStartTime.set(null)
                currentProgress.set(0f)
                lastKnownProgress.set(0f)
                pausedAtTime.set(null)
                totalPausedMs.set(0L)
                currentWorkType.set("Other")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing task assignment", e)
            EventLogger.error(EventLogger.Categories.TASK, "Task $taskId failed: ${e.message}")
            NotificationHelper.notifyTaskFailed(context, taskId, e.message)
            currentTaskId.set(null)
            currentJobId.set(null)
            isTaskRunning.set(false)
            taskStartTime.set(null)
            currentProgress.set(0f)
            lastKnownProgress.set(0f)
            pausedAtTime.set(null)
            totalPausedMs.set(0L)
            currentWorkType.set("Other")
            MessageProtocol.createTaskErrorMessage(
                taskId = taskId,
                jobId = jobId,
                error = "Task execution failed: ${e.message ?: "Unknown error"}"
            )
        }
    }

    private suspend fun executeCodeWithRetry(
        funcCode: String,
        taskArgs: String,
        stageInfo: StageInfo
    ): Map<String, Any?> {
        var attempt = 0
        var last: Map<String, Any?> = emptyMap()

        while (attempt <= MAX_TRANSIENT_RETRIES) {
            val result = pythonExecutor.executeCode(funcCode, taskArgs)
            last = result

            val status = result["status"] as? String ?: "error"
            if (status != "error") return result

            val message = result["message"] as? String ?: ""
            val transient = isTransientFailure(message)
            if (!transient || attempt == MAX_TRANSIENT_RETRIES) {
                return result
            }

            attempt += 1
            Log.w(TAG, "Retrying stage ${stageInfo.stageName} partition=${stageInfo.partitionIdx}; attempt=$attempt reason=$message")
            delay(300)
        }

        return last
    }

    private fun isTransientFailure(message: String): Boolean {
        val msg = message.lowercase()
        return msg.contains("no_tflite_backend") ||
            msg.contains("decode") ||
            msg.contains("base64") ||
            msg.contains("temporarily unavailable")
    }

    private fun inspectStage(funcCode: String, taskArgs: String): StageInfo {
        val code = funcCode.lowercase()
        return when {
            code.contains("def partition_model(") -> StageInfo("partition_model", null)
            code.contains("def run_tflite_partition(") -> StageInfo("tflite_partition", extractPartitionIdx(taskArgs))
            code.contains("def classify(") -> StageInfo("classify", null)
            else -> StageInfo("generic", null)
        }
    }

    private fun routePolicyForStage(stageInfo: StageInfo): String {
        return when (stageInfo.stageName) {
            "partition_model" -> "desktop_tensorflow_pool"
            "tflite_partition" -> "android_tflite_pool"
            "classify" -> "android_or_desktop"
            else -> "default_worker_pool"
        }
    }

    private fun isStageOrderSatisfied(jobId: String?, stageInfo: StageInfo): Boolean {
        if (jobId.isNullOrBlank()) return true
        if (stageInfo.stageName != "tflite_partition") return true

        val idx = stageInfo.partitionIdx ?: return true
        if (idx == 0) {
            completedPartitionIdxByJob[jobId] = -1
            return true
        }

        val lastCompleted = completedPartitionIdxByJob[jobId] ?: -1
        return lastCompleted >= idx - 1
    }

    private fun extractPartitionIdx(taskArgs: String): Int? {
        if (taskArgs.isBlank()) return null
        return try {
            val trimmed = taskArgs.trim()
            val obj = when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    if (arr.length() > 0 && arr.optJSONObject(0) != null) arr.getJSONObject(0) else null
                }
                trimmed.startsWith("{") -> JSONObject(trimmed)
                else -> null
            } ?: return null

            val originalArgs = obj.optJSONObject("original_args")
            when {
                originalArgs != null && originalArgs.has("partition_idx") -> originalArgs.optInt("partition_idx")
                obj.has("partition_idx") -> obj.optInt("partition_idx")
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeTaskArgsForStage(taskArgs: String, stageInfo: StageInfo): String {
        return when (stageInfo.stageName) {
            "tflite_partition" -> normalizeTflitePartitionArgs(taskArgs, stageInfo.partitionIdx)
            "classify" -> normalizeClassifyArgs(taskArgs)
            else -> taskArgs
        }
    }

    private fun normalizeTflitePartitionArgs(taskArgs: String, partitionIdx: Int?): String {
        val idx = partitionIdx ?: return taskArgs

        return try {
            val rootArray = if (taskArgs.trim().startsWith("[")) {
                JSONArray(taskArgs)
            } else {
                JSONArray().apply {
                    if (taskArgs.trim().startsWith("{")) put(JSONObject(taskArgs))
                }
            }

            val first = if (rootArray.length() > 0 && rootArray.optJSONObject(0) != null) {
                rootArray.getJSONObject(0)
            } else {
                JSONObject().also { rootArray.put(it) }
            }

            val originalArgs = first.optJSONObject("original_args") ?: JSONObject().also {
                first.put("original_args", it)
            }
            if (!originalArgs.has("partition_idx")) {
                originalArgs.put("partition_idx", idx)
            }
            // Keep Android payload metadata-only; model bytes are resolved locally.
            first.remove("tflite_models")
            first.remove("tflite_b64")
            first.remove("tflite_b64_str")
            originalArgs.remove("tflite_models")
            originalArgs.remove("tflite_b64")
            originalArgs.remove("tflite_b64_str")
            if (!first.has("upstream_results")) {
                first.put("upstream_results", JSONObject())
            }
            rootArray.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to normalize task_args for tflite_partition: ${e.message}")
            taskArgs
        }
    }

    private fun normalizeClassifyArgs(taskArgs: String): String {
        return try {
            val rootArray = if (taskArgs.trim().startsWith("[")) {
                JSONArray(taskArgs)
            } else {
                JSONArray().apply {
                    if (taskArgs.trim().startsWith("{")) put(JSONObject(taskArgs))
                }
            }

            if (rootArray.length() == 0 || rootArray.optJSONObject(0) == null) {
                return taskArgs
            }

            val first = rootArray.getJSONObject(0)
            val originalArgs = first.optJSONObject("original_args") ?: JSONObject().also {
                first.put("original_args", it)
            }
            val upstreamResults = first.optJSONObject("upstream_results") ?: return rootArray.toString()

            if (upstreamResults.length() == 1) {
                val upstreamKey = upstreamResults.keys().asSequence().firstOrNull()
                val upstreamObj = upstreamKey?.let { upstreamResults.optJSONObject(it) }

                if (upstreamObj != null) {
                    val upstreamStreamId = if (upstreamObj.has("stream_id")) upstreamObj.optInt("stream_id") else null
                    if (upstreamStreamId != null) {
                        val currentStreamId = if (originalArgs.has("stream_id")) originalArgs.optInt("stream_id") else null
                        if (currentStreamId == null || currentStreamId != upstreamStreamId) {
                            originalArgs.put("stream_id", upstreamStreamId)
                            Log.i(TAG, "Normalized classify stream_id to upstream stream_id=$upstreamStreamId")
                        }
                    }

                    // Some classify payloads look for feature_map directly.
                    // Provide alias from upstream output_tensor when available.
                    if (!first.has("feature_map") && upstreamObj.optJSONObject("output_tensor") != null) {
                        first.put("feature_map", upstreamObj.optJSONObject("output_tensor"))
                    }
                }
            }

            rootArray.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to normalize task_args for classify: ${e.message}")
            taskArgs
        }
    }

    private suspend fun prepareTflitePartitionArgs(
        taskArgs: String,
        taskData: JSONObject?,
        partitionIdx: Int?
    ): TflitePreparationResult {
        val idx = partitionIdx ?: return TflitePreparationResult(taskArgs = taskArgs, telemetry = null)
        val started = SystemClock.elapsedRealtime()

        val rootArray = if (taskArgs.trim().startsWith("[")) {
            JSONArray(taskArgs)
        } else {
            JSONArray().apply {
                if (taskArgs.trim().startsWith("{")) put(JSONObject(taskArgs))
            }
        }

        val first = if (rootArray.length() > 0 && rootArray.optJSONObject(0) != null) {
            rootArray.getJSONObject(0)
        } else {
            JSONObject().also { rootArray.put(it) }
        }
        val originalArgs = first.optJSONObject("original_args") ?: JSONObject().also {
            first.put("original_args", it)
        }
        val upstreamModelMeta = readUpstreamModelMeta(first, originalArgs)

        val modelId = readStringField(first, originalArgs, taskData, "model_id", upstreamModelMeta)
            ?: readStringField(first, originalArgs, taskData, "model", upstreamModelMeta)
            ?: readStringField(first, originalArgs, taskData, "model_name", upstreamModelMeta)
        val modelManifest = readManifest(first, originalArgs, taskData, upstreamModelMeta)
        val modelStoreDir = readStringField(first, originalArgs, taskData, "model_store_dir", upstreamModelMeta)
        val explicitModelBaseUrl = readStringField(first, originalArgs, taskData, "model_base_url", upstreamModelMeta)
        val modelBaseUrl = explicitModelBaseUrl ?: deriveDefaultModelBaseUrl()
        val workerCacheDir = readStringField(first, originalArgs, taskData, "worker_model_cache_dir", upstreamModelMeta)

        if (modelId.isNullOrBlank()) {
            throw IllegalStateException("Missing model_id for tflite_partition task")
        }

        if (!first.has("model_id")) first.put("model_id", modelId)
        if (!originalArgs.has("model_id")) originalArgs.put("model_id", modelId)
        if (modelManifest != null && !originalArgs.has("model_manifest")) originalArgs.put("model_manifest", modelManifest)
        if (!modelStoreDir.isNullOrBlank() && !originalArgs.has("model_store_dir")) originalArgs.put("model_store_dir", modelStoreDir)
        if (!modelBaseUrl.isNullOrBlank() && !originalArgs.has("model_base_url")) originalArgs.put("model_base_url", modelBaseUrl)
        if (!workerCacheDir.isNullOrBlank() && !originalArgs.has("worker_model_cache_dir")) originalArgs.put("worker_model_cache_dir", workerCacheDir)

        if (explicitModelBaseUrl.isNullOrBlank() && !modelBaseUrl.isNullOrBlank()) {
            Log.i(TAG, "model_base_url missing in task payload; using default $modelBaseUrl")
        }

        val resolved = try {
            modelRepository.resolvePartition(
                ModelRepository.ResolveRequest(
                    modelId = modelId,
                    partitionIdx = idx,
                    modelManifest = modelManifest,
                    modelStoreDir = modelStoreDir,
                    modelBaseUrl = modelBaseUrl,
                    workerModelCacheDir = workerCacheDir
                )
            )
        } catch (e: Exception) {
            val elapsed = SystemClock.elapsedRealtime() - started
            throw IllegalStateException("Model resolution failed (model_id=$modelId, partition_idx=$idx, model_load_ms=$elapsed): ${e.message}")
        }

        Log.i(TAG, "Model resolved: ${resolved.file.absolutePath}, source=${resolved.source}, cacheHit=${resolved.cacheHit}, downloadMs=${resolved.downloadMs}")

        // Provide local model path aliases for updated Python runtime loaders.
        val localPath = resolved.file.absolutePath
        first.put("local_model_path", localPath)
        first.put("model_local_path", localPath)
        originalArgs.put("local_model_path", localPath)
        originalArgs.put("model_local_path", localPath)

        val telemetry = JSONObject()
            .put("model_source", resolved.source.name.lowercase())
            .put("cache_hit", resolved.cacheHit)
            .put("model_load_ms", resolved.modelLoadMs)
        if (resolved.downloadMs != null) {
            telemetry.put("download_ms", resolved.downloadMs)
        }

        return TflitePreparationResult(taskArgs = rootArray.toString(), telemetry = telemetry)
    }

    private fun readManifest(
        first: JSONObject,
        originalArgs: JSONObject,
        taskData: JSONObject?,
        upstreamModelMeta: JSONObject?
    ): JSONObject? {
        val candidates = listOf(
            first.opt("model_manifest"),
            originalArgs.opt("model_manifest"),
            taskData?.opt("model_manifest"),
            upstreamModelMeta?.opt("model_manifest"),
            upstreamModelMeta?.opt("manifest")
        )
        for (candidate in candidates) {
            when (candidate) {
                is JSONObject -> return candidate
                is String -> {
                    val trimmed = candidate.trim()
                    if (trimmed.startsWith("{")) {
                        try {
                            return JSONObject(trimmed)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
        return null
    }

    private fun readStringField(
        first: JSONObject,
        originalArgs: JSONObject,
        taskData: JSONObject?,
        key: String,
        upstreamModelMeta: JSONObject? = null
    ): String? {
        val v1 = first.optString(key, "").trim()
        if (v1.isNotBlank()) return v1
        val v2 = originalArgs.optString(key, "").trim()
        if (v2.isNotBlank()) return v2
        val v3 = taskData?.optString(key, "")?.trim().orEmpty()
        if (v3.isNotBlank()) return v3
        val v4 = upstreamModelMeta?.optString(key, "")?.trim().orEmpty()
        return v4.ifBlank { null }
    }

    private fun readUpstreamModelMeta(first: JSONObject, originalArgs: JSONObject): JSONObject? {
        val upstreamResults = first.optJSONObject("upstream_results") ?: return null
        if (upstreamResults.length() == 0) return null

        val streamId = if (originalArgs.has("stream_id")) originalArgs.optInt("stream_id", -1) else -1
        var firstObj: JSONObject? = null
        val keys = upstreamResults.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = upstreamResults.optJSONObject(key) ?: continue
            if (firstObj == null) firstObj = obj
            if (streamId >= 0 && obj.optInt("stream_id", Int.MIN_VALUE) == streamId) {
                return obj
            }
        }
        return firstObj
    }

    fun handleLowStorageEvent() {
        processorScope.launch {
            try {
                modelRepository.cleanupForLowStorage()
            } catch (e: Exception) {
                Log.w(TAG, "Model cache low-storage cleanup failed: ${e.message}")
            }
        }
    }

    private fun normalizeAndValidateResult(rawResult: Any?, stageInfo: StageInfo, modelTelemetry: JSONObject?): JSONObject {
        return try {
            val resultObj = when (rawResult) {
                null -> JSONObject()
                is JSONObject -> rawResult
                is String -> {
                    val t = rawResult.trim()
                    if (t.startsWith("{")) JSONObject(t) else JSONObject().put("result", rawResult)
                }
                else -> JSONObject().put("result", rawResult.toString())
            }

            if (resultObj.has("error")) {
                val baseError = resultObj.optString("error", "Task returned error")
                val diagnostics = mutableListOf<String>()

                val backendUsed = resultObj.optString("backend_used", "")
                if (backendUsed.isNotBlank()) diagnostics.add("backend_used=$backendUsed")

                if (resultObj.has("stream_id")) diagnostics.add("stream_id=${resultObj.opt("stream_id")}")
                if (resultObj.has("partition_idx")) diagnostics.add("partition_idx=${resultObj.opt("partition_idx")}")
                val modelId = resultObj.optString("model_id", "")
                if (modelId.isNotBlank()) diagnostics.add("model_id=$modelId")

                val traceArray = resultObj.optJSONArray("trace")
                if (traceArray != null) {
                    diagnostics.add("trace_len=${traceArray.length()}")
                    if (traceArray.length() > 0) {
                        val traceHead = (0 until minOf(3, traceArray.length()))
                            .mapNotNull { idx -> traceArray.optString(idx, null) }
                            .joinToString(" | ")
                        if (traceHead.isNotBlank()) diagnostics.add("trace_head=$traceHead")
                    }
                }

                if (modelTelemetry != null) {
                    diagnostics.add("model_source=${modelTelemetry.optString("model_source", "unknown")}")
                    diagnostics.add("cache_hit=${modelTelemetry.optBoolean("cache_hit", false)}")
                    diagnostics.add("model_load_ms=${modelTelemetry.optLong("model_load_ms", 0L)}")
                }

                val enriched = if (diagnostics.isNotEmpty()) {
                    "$baseError [${diagnostics.joinToString(", ")}]"
                } else {
                    baseError
                }

                return JSONObject().put("status", "error").put("error", enriched)
            }

            if (stageInfo.stageName == "tflite_partition") {
                // Never return model blobs from Android workers.
                if (resultObj.has("tflite_models")) {
                    resultObj.remove("tflite_models")
                }
                resultObj.remove("tflite_b64")
                resultObj.remove("tflite_b64_str")

                if (!resultObj.has("partition_idx")) {
                    stageInfo.partitionIdx?.let { resultObj.put("partition_idx", it) }
                }
                if (!resultObj.has("partition_idx") || !resultObj.has("output_tensor")) {
                    return JSONObject().put("status", "error").put("error", "Malformed tflite_partition result: requires partition_idx and output_tensor")
                }

                if (modelTelemetry != null) {
                    resultObj.put("model_source", modelTelemetry.optString("model_source", "unknown"))
                    resultObj.put("cache_hit", modelTelemetry.optBoolean("cache_hit", false))
                    resultObj.put("model_load_ms", modelTelemetry.optLong("model_load_ms", 0L))
                    if (modelTelemetry.has("download_ms")) {
                        resultObj.put("download_ms", modelTelemetry.optLong("download_ms", 0L))
                    }
                    if (modelTelemetry.has("model_load_error")) {
                        resultObj.put("model_load_error", modelTelemetry.optString("model_load_error", ""))
                    }
                }

                val tensorObj = resultObj.optJSONObject("output_tensor")
                val dataB64 = tensorObj?.optString("data_b64", "") ?: ""
                if (dataB64.isNotEmpty() && dataB64.length > MAX_OUTPUT_TENSOR_B64_CHARS) {
                    val uploadResult = uploadLargeTensorAndGetUrl(dataB64, stageInfo.partitionIdx)
                    val uploadedUrl = uploadResult.fileUrl
                        ?: return JSONObject().put("status", "error")
                            .put(
                                "error",
                                "Tensor payload too large (${dataB64.length} chars) and upload to local HTTP server failed: " +
                                    (uploadResult.error ?: "unknown upload error")
                            )

                    tensorObj?.apply {
                        remove("data")
                        remove("data_b64")
                        put("file_url", uploadedUrl)
                        put("transport", "http_url")
                    }
                    resultObj.put("tensor_offloaded", true)
                }
            }

            JSONObject().put("status", "ok").put("result", resultObj.toString())
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("error", "Failed to normalize result: ${e.message}")
        }
    }

    private fun uploadLargeTensorAndGetUrl(dataB64: String, partitionIdx: Int?): TensorUploadResult {
        val foremanIp = configManager.getForemanIP().trim()
        if (foremanIp.isEmpty()) {
            Log.w(TAG, "Cannot offload tensor: Foreman IP is not configured")
            return TensorUploadResult(fileUrl = null, error = "Foreman IP is not configured")
        }

        val uploadUrl = "http://$foremanIp:$DEFAULT_TENSOR_UPLOAD_PORT$TENSOR_UPLOAD_PATH"
        val suffix = partitionIdx?.toString() ?: "unknown"
        val tempFile = File(context.cacheDir, "tensor_${System.currentTimeMillis()}_${suffix}.npy")

        return try {
            val bytes = Base64.decode(dataB64, Base64.DEFAULT)
            tempFile.writeBytes(bytes)

            val filePart = tempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", tempFile.name, filePart)
                .build()

            val request = Request.Builder()
                .url(uploadUrl)
                .post(multipartBody)
                .build()

            tensorUploadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Tensor upload failed: HTTP ${response.code} (${response.message})")
                    return TensorUploadResult(
                        fileUrl = null,
                        error = "POST $uploadUrl -> HTTP ${response.code} (${response.message})"
                    )
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    Log.w(TAG, "Tensor upload failed: empty response body")
                    return TensorUploadResult(
                        fileUrl = null,
                        error = "POST $uploadUrl -> empty response body"
                    )
                }

                val json = JSONObject(body)
                val fileUrl = json.optString("file_url", "").trim()
                if (fileUrl.isBlank()) {
                    Log.w(TAG, "Tensor upload response missing file_url: $body")
                    TensorUploadResult(
                        fileUrl = null,
                        error = "POST $uploadUrl -> response missing file_url (body=${body.take(300)})"
                    )
                } else {
                    TensorUploadResult(fileUrl = fileUrl, error = null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tensor upload exception: ${e.message}")
            TensorUploadResult(
                fileUrl = null,
                error = "POST $uploadUrl -> ${e.javaClass.simpleName}: ${e.message ?: "unknown exception"}"
            )
        } finally {
            tempFile.delete()
        }
    }

    private fun deriveDefaultModelBaseUrl(): String? {
        val foremanIp = configManager.getForemanIP().trim()
        if (foremanIp.isBlank()) return null
        return "http://$foremanIp:$DEFAULT_MODEL_ARTIFACT_HTTP_PORT$DEFAULT_MODEL_STORE_HTTP_PREFIX"
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
                        Log.i(TAG, "Checkpoint monitoring resumed for task $taskId (continuing from checkpoint #$checkpointCount)")
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
                
                Log.d(TAG, "Resumed task $taskId completed successfully")
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
        
        return mapOf<String, Any>(
            "is_busy" to isBusy,
            "is_paused" to isTaskPaused.get(),
            "current_task_id" to taskId,
            "current_job_id" to jobId,
            "python_ready" to pythonExecutor.isReady(),
            "processor_initialized" to isInitialized.get(),
            "progress_percent" to progressPercent,
            "work_type" to currentWorkType.get()
        )
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
