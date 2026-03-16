package com.example.mcc_phase3.checkpoint

import android.util.Log
import com.example.mcc_phase3.utils.EventLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

/**
 * Manages checkpointing on the Android worker side.
 * 
 * Captures task state periodically, computes deltas, and sends
 * checkpoint messages to foreman without blocking task execution.
 * 
 * Supports:
 * - Configuration from task_metadata (declarative checkpointing)
 * - BASE and DELTA checkpoint types
 * - JSON serialization for cross-platform compatibility
 * - State variable filtering based on declared checkpoint_state vars
 */
class CheckpointHandler(
    private var checkpointIntervalMs: Long = 5000L // 5 seconds default
) {
    companion object {
        private const val TAG = "CheckpointHandler"
        private const val SERIALIZATION_FORMAT = "json"  // For cross-platform compatibility
    }

    // Checkpoint state
    private var lastCheckpointState: ByteArray? = null
    private var checkpointCount = 0
    private var isBaseSent = false
    private var checkpointJob: Job? = null
    
    // Task metadata configuration
    private var taskMetadata: TaskMetadata? = null
    private var checkpointStateVars: List<String> = emptyList()

    // Current state accessible by task executor
    private val _currentState = MutableStateFlow<CheckpointState?>(null)
    val currentState: StateFlow<CheckpointState?> = _currentState

    /**
     * Generic data class representing checkpoint state.
     * Uses a Map to store all state variables dynamically based on task_metadata.checkpoint_state.
     * No hardcoded task-specific fields - fully generic for any task type.
     */
    data class CheckpointState(
        val stateData: Map<String, Any> = emptyMap(),
        val startTime: Long = System.currentTimeMillis(),
        val status: String = "running"
    ) {
        // Convenience accessors for common fields (optional, for backwards compatibility)
        val progressPercent: Float
            get() = (stateData["progress_percent"] as? Number)?.toFloat() ?: 0f
        
        fun toJson(): JSONObject {
            return JSONObject().apply {
                // Add all dynamic state variables
                stateData.forEach { (key, value) ->
                    when (value) {
                        is Number -> put(key, value)
                        is Boolean -> put(key, value)
                        is String -> put(key, value)
                        is List<*> -> put(key, JSONArray(value))
                        is Map<*, *> -> put(key, JSONObject(value as Map<String, Any>))
                        else -> put(key, value.toString())
                    }
                }
                // Add metadata fields
                put("start_time", startTime)
                put("status", status)
            }
        }
        
        companion object {
            /**
             * Create CheckpointState from a generic map of state variables
             */
            fun fromMap(data: Map<String, Any>): CheckpointState {
                return CheckpointState(stateData = data)
            }
        }
    }

    /**
     * Update the current checkpoint state (called by task executor)
     */
    fun updateState(state: CheckpointState) {
        _currentState.value = state
    }
    
    /**
     * Configure checkpoint handler from task metadata
     * Call this before starting checkpoint monitoring if task_metadata is available
     */
    fun configure(metadata: TaskMetadata) {
        taskMetadata = metadata
        checkpointStateVars = metadata.checkpointState
        
        // Update interval from metadata (convert seconds to milliseconds)
        if (metadata.checkpointInterval > 0) {
            checkpointIntervalMs = (metadata.checkpointInterval * 1000).toLong()
        }
        
        Log.d(TAG, "Configured from task_metadata: " +
                "enabled=${metadata.checkpointEnabled}, " +
                "interval=${checkpointIntervalMs}ms, " +
                "vars=${checkpointStateVars}")
    }
    
    /**
     * Check if checkpointing is enabled (from task_metadata or default)
     */
    fun isCheckpointingEnabled(): Boolean {
        return taskMetadata?.checkpointEnabled ?: true  // Default to enabled if no metadata
    }

    /**
     * Start periodic checkpoint monitoring
     * 
     * @param taskId Task identifier
     * @param jobId Job identifier
     * @param workerId Worker identifier
     * @param scope CoroutineScope for the checkpoint loop
     * @param sendCheckpoint Callback to send checkpoint via WebSocket
     * @param pollState Optional callback to poll state from external source (e.g., Python builtins)
     */
    fun startCheckpointMonitoring(
        taskId: String,
        jobId: String?,
        workerId: String?,
        scope: CoroutineScope,
        sendCheckpoint: suspend (CheckpointMessage) -> Unit,
        pollState: (() -> Unit)? = null
    ) {
        // Reset state for new task
        reset()

        Log.d(TAG, "Starting checkpoint monitoring for task $taskId (interval: ${checkpointIntervalMs}ms)")

        checkpointJob = scope.launch {
            // Shorter initial delay - just enough for task to start executing
            // The checkpoint callback should be set up by now
            delay(500L)

            while (isActive) {
                try {
                    // Wait for interval first, then poll
                    // This gives the task time to make progress before first checkpoint
                    delay(checkpointIntervalMs)
                    
                    Log.d(TAG, "Taking checkpoint for task $taskId")
                    
                    // Poll state from external source if callback provided
                    // This calls PythonExecutor.pollAndUpdateCheckpointState()
                    if (pollState != null) {
                        Log.d(TAG, "Invoking pollState callback...")
                        val pollStartTime = System.currentTimeMillis()
                        pollState.invoke()
                        val pollDuration = System.currentTimeMillis() - pollStartTime
                        Log.d(TAG, "pollState callback completed in ${pollDuration}ms")
                    }

                    val state = _currentState.value
                    if (state != null) {
                        Log.d(TAG, "Got state for task $taskId: progress=${state.progressPercent}%")

                        // Serialize state to JSON, optionally filtering to declared vars
                        val stateJson = filterAndSerializeState(state)
                        val stateBytes = stateJson.toByteArray(Charsets.UTF_8)
                        val compressed = compressGzip(stateBytes)

                        val checkpointMsg = if (!isBaseSent) {
                            // Send base checkpoint first
                            lastCheckpointState = compressed
                            checkpointCount = 1
                            isBaseSent = true

                            Log.i(TAG, "Sending BASE #$checkpointCount for task $taskId | " +
                                    "Size: ${compressed.size} bytes | Progress: ${state.progressPercent}%")
                            EventLogger.info(EventLogger.Categories.CHECKPOINT, 
                                "BASE checkpoint #$checkpointCount sent (${compressed.size} bytes, ${state.progressPercent}% complete)")

                            CheckpointMessage(
                                taskId = taskId,
                                jobId = jobId,
                                workerId = workerId,
                                isBase = true,
                                checkpointType = "base",
                                progressPercent = state.progressPercent,
                                checkpointId = checkpointCount,
                                deltaDataHex = compressed.toHexString(),
                                compressionType = "gzip",
                                serializationFormat = SERIALIZATION_FORMAT,
                                checkpointStateVars = checkpointStateVars,
                                stateSizeBytes = stateBytes.size
                            )
                        } else {
                            // Compute and send delta
                            val deltaBytes = computeDelta(lastCheckpointState!!, compressed)
                            checkpointCount++
                            lastCheckpointState = compressed

                            Log.i(TAG, "Sending DELTA #$checkpointCount for task $taskId | " +
                                    "Delta size: ${deltaBytes.size} bytes | Progress: ${state.progressPercent}%")
                            EventLogger.debug(EventLogger.Categories.CHECKPOINT, 
                                "DELTA checkpoint #$checkpointCount sent (${deltaBytes.size} bytes)")

                            CheckpointMessage(
                                taskId = taskId,
                                jobId = jobId,
                                workerId = workerId,
                                isBase = false,
                                checkpointType = "delta",
                                progressPercent = state.progressPercent,
                                checkpointId = checkpointCount,
                                deltaDataHex = deltaBytes.toHexString(),
                                compressionType = "gzip",
                                serializationFormat = SERIALIZATION_FORMAT,
                                checkpointStateVars = checkpointStateVars,
                                stateSizeBytes = stateBytes.size
                            )
                        }

                        // Send checkpoint
                        sendCheckpoint(checkpointMsg)
                    } else {
                        Log.w(TAG, "No checkpoint state available for task $taskId")
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in checkpoint loop: ${e.message}")
                }

                // Wait for next interval
                delay(checkpointIntervalMs)
            }
        }
    }

    /**
     * Stop checkpoint monitoring and reset for next task
     */
    fun stopCheckpointMonitoring() {
        checkpointJob?.cancel()
        checkpointJob = null
        // Full reset to prepare for next task
        fullReset()
        Log.d(TAG, "Checkpoint monitoring stopped")
    }

    /**
     * Reset checkpoint state (for new task)
     * NOTE: Does NOT reset taskMetadata or checkpointStateVars because those are
     * configured via configure() BEFORE startCheckpointMonitoring() is called.
     */
    fun reset() {
        lastCheckpointState = null
        checkpointCount = 0
        isBaseSent = false
        _currentState.value = null
        // NOTE: Don't clear taskMetadata and checkpointStateVars here!
        // They are set by configure() before startCheckpointMonitoring() is called,
        // and need to persist through the monitoring session.
    }
    
    /**
     * Full reset including configuration (for use between tasks)
     */
    fun fullReset() {
        reset()
        taskMetadata = null
        checkpointStateVars = emptyList()
    }
    
    /**
     * Initialize from existing checkpoint count (for task resumption)
     * Called when resuming a failed task from checkpoint
     * The actual checkpoint state is pre-loaded into Python's builtins._checkpoint_state by PythonExecutor.
     * The Python code handles extracting and using those values.
     * This method just sets up checkpoint count continuity
     * so new checkpoints are numbered correctly.
     * 
     * @param existingCheckpointCount The checkpoint count from the foreman
     */
    fun initializeFromCheckpoint(existingCheckpointCount: Int) {
        // Don't fully reset - preserve checkpoint count continuity
        lastCheckpointState = null
        checkpointCount = existingCheckpointCount
        isBaseSent = true  // We're resuming, so base was already sent before failure
        _currentState.value = null
        Log.d(TAG, "Initialized checkpoint handler for resumption (starting from checkpoint #$existingCheckpointCount)")
    }

    /**
     * Check if checkpoint monitoring is active
     */
    fun isMonitoring(): Boolean {
        return checkpointJob?.isActive == true
    }

    /**
     * Get the current checkpoint count
     */
    fun getCheckpointCount(): Int {
        return checkpointCount
    }
    
    /**
     * Get the configured checkpoint state variables.
     * Returns empty list if no specific variables are configured (capture all).
     */
    fun getCheckpointStateVars(): List<String> {
        return checkpointStateVars
    }
    
    /**
     * Filter and serialize state to JSON string.
     * If checkpointStateVars is configured, only include those variables.
     * Otherwise, include all state variables.
     */
    private fun filterAndSerializeState(state: CheckpointState): String {
        val fullJson = state.toJson()
        
        // If no specific vars configured, return full state
        if (checkpointStateVars.isEmpty()) {
            return fullJson.toString()
        }
        
        // Filter to only declared checkpoint_state variables
        val filteredJson = JSONObject()
        for (varName in checkpointStateVars) {
            if (fullJson.has(varName)) {
                filteredJson.put(varName, fullJson.get(varName))
            }
        }
        
        // Always include progress_percent for tracking
        if (fullJson.has("progress_percent") && !filteredJson.has("progress_percent")) {
            filteredJson.put("progress_percent", fullJson.get("progress_percent"))
        }
        
        return filteredJson.toString()
    }

    /**
     * Compress data using GZIP
     */
    private fun compressGzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(data)
        }
        return bos.toByteArray()
    }

    /**
     * Decompress GZIP data
     */
    private fun decompressGzip(data: ByteArray): ByteArray {
        return GZIPInputStream(data.inputStream()).use { it.readBytes() }
    }

    /**
     * Compute delta between two checkpoint states
     * 
     * For simplicity, we compare JSON and only send changed fields.
     * In production, you might use a more sophisticated diff algorithm.
     */
    private fun computeDelta(lastCheckpoint: ByteArray, currentCheckpoint: ByteArray): ByteArray {
        try {
            // Decompress both
            val lastJson = decompressGzip(lastCheckpoint).toString(Charsets.UTF_8)
            val currentJson = decompressGzip(currentCheckpoint).toString(Charsets.UTF_8)

            val lastObj = JSONObject(lastJson)
            val currentObj = JSONObject(currentJson)

            // Find changed fields
            val delta = JSONObject()
            val currentKeys = currentObj.keys()
            while (currentKeys.hasNext()) {
                val key = currentKeys.next()
                val currentValue = currentObj.get(key)
                val lastValue = lastObj.opt(key)
                
                if (currentValue != lastValue) {
                    delta.put(key, currentValue)
                }
            }

            // Serialize and compress delta
            val deltaJson = delta.toString()
            return compressGzip(deltaJson.toByteArray(Charsets.UTF_8))

        } catch (e: Exception) {
            Log.w(TAG, "Error computing delta, sending full state: ${e.message}")
            return currentCheckpoint
        }
    }

    /**
     * Convert ByteArray to hex string
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}

/**
 * Checkpoint message to send to foreman.
 * 
 * Extended format with support for:
 * - checkpoint_type: "base", "delta", or "compacted"
 * - serialization_format: "json" for cross-platform compatibility
 * - checkpoint_state_vars: list of declared variables in checkpoint
 * - state_size_bytes: uncompressed state size
 */
data class CheckpointMessage(
    val taskId: String,
    val jobId: String?,
    val workerId: String?,
    val isBase: Boolean,
    val checkpointType: String = if (isBase) "base" else "delta",  // "base", "delta", or "compacted"
    val progressPercent: Float,
    val checkpointId: Int,
    val deltaDataHex: String,
    val compressionType: String = "gzip",
    val serializationFormat: String = "json",      // "json" or "pickle"
    val checkpointStateVars: List<String> = emptyList(),  // Variables included in checkpoint
    val stateSizeBytes: Int = 0                    // Uncompressed state size
) {
    /**
     * Convert to JSON string for WebSocket transmission
     * Must match foreman's expected format:
     * - "type" field (not "msg_type")
     * - lowercase enum value "task_checkpoint" (matching MessageType.TASK_CHECKPOINT.value)
     * - "serialization_format" MUST be at the data level so foreman knows to use JSON (not pickle)
     */
    fun toJsonString(): String {
        return JSONObject().apply {
            put("type", "task_checkpoint")  // Must be lowercase to match foreman's MessageType enum value
            put("job_id", jobId)
            put("data", JSONObject().apply {
                put("task_id", taskId)
                put("worker_id", workerId)
                put("is_base", isBase)
                put("checkpoint_type", checkpointType)
                put("progress_percent", progressPercent)
                put("checkpoint_id", checkpointId)
                put("delta_data_hex", deltaDataHex)
                put("compression_type", compressionType)
                // CRITICAL: serialization_format tells foreman to use json.loads() instead of pickle.loads()
                // Without this, foreman will try to unpickle the JSON data and fail
                put("serialization_format", serializationFormat)
                put("checkpoint_state_vars", JSONArray(checkpointStateVars))
                put("state_size_bytes", stateSizeBytes)
            })
        }.toString()
    }
}
