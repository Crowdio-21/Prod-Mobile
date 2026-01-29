package com.example.mcc_phase3.checkpoint

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

/**
 * Manages checkpointing on the Android worker side.
 * 
 * Captures task state periodically, computes deltas, and sends
 * checkpoint messages to foreman without blocking task execution.
 */
class CheckpointHandler(
    private val checkpointIntervalMs: Long = 5000L // 5 seconds default
) {
    companion object {
        private const val TAG = "CheckpointHandler"
    }

    // Checkpoint state
    private var lastCheckpointState: ByteArray? = null
    private var checkpointCount = 0
    private var isBaseSent = false
    private var checkpointJob: Job? = null

    // Current state accessible by task executor
    private val _currentState = MutableStateFlow<CheckpointState?>(null)
    val currentState: StateFlow<CheckpointState?> = _currentState

    /**
     * Data class representing checkpoint state
     */
    data class CheckpointState(
        val trialsCompleted: Int = 0,
        val totalCount: Long = 0,
        val numTrials: Int = 0,
        val progressPercent: Float = 0f,
        val estimatedE: Double = 0.0,
        val startTime: Long = System.currentTimeMillis(),
        val status: String = "running",
        // Generic fields for other task types
        val customData: Map<String, Any>? = null
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("trials_completed", trialsCompleted)
                put("total_count", totalCount)
                put("num_trials", numTrials)
                put("progress_percent", progressPercent)
                put("estimated_e", estimatedE)
                put("start_time", startTime)
                put("status", status)
                customData?.forEach { (key, value) ->
                    put(key, value)
                }
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
            // Initial delay to let task start
            delay(1000L)

            while (isActive) {
                try {
                    Log.d(TAG, "Taking checkpoint for task $taskId")
                    
                    // Poll state from external source if callback provided
                    pollState?.invoke()

                    val state = _currentState.value
                    if (state != null) {
                        Log.d(TAG, "Got state for task $taskId: progress=${state.progressPercent}%")

                        // Serialize and compress state
                        val stateJson = state.toJson().toString()
                        val compressed = compressGzip(stateJson.toByteArray(Charsets.UTF_8))

                        val checkpointMsg = if (!isBaseSent) {
                            // Send base checkpoint first
                            lastCheckpointState = compressed
                            checkpointCount = 1
                            isBaseSent = true

                            Log.i(TAG, "📦 Sending BASE #$checkpointCount for task $taskId | " +
                                    "Size: ${compressed.size} bytes | Progress: ${state.progressPercent}%")

                            CheckpointMessage(
                                taskId = taskId,
                                jobId = jobId,
                                workerId = workerId,
                                isBase = true,
                                progressPercent = state.progressPercent,
                                checkpointId = checkpointCount,
                                deltaDataHex = compressed.toHexString(),
                                compressionType = "gzip"
                            )
                        } else {
                            // Compute and send delta
                            val deltaBytes = computeDelta(lastCheckpointState!!, compressed)
                            checkpointCount++
                            lastCheckpointState = compressed

                            Log.i(TAG, "📦 Sending DELTA #$checkpointCount for task $taskId | " +
                                    "Delta size: ${deltaBytes.size} bytes | Progress: ${state.progressPercent}%")

                            CheckpointMessage(
                                taskId = taskId,
                                jobId = jobId,
                                workerId = workerId,
                                isBase = false,
                                progressPercent = state.progressPercent,
                                checkpointId = checkpointCount,
                                deltaDataHex = deltaBytes.toHexString(),
                                compressionType = "gzip"
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
     * Stop checkpoint monitoring
     */
    fun stopCheckpointMonitoring() {
        checkpointJob?.cancel()
        checkpointJob = null
        Log.d(TAG, "Checkpoint monitoring stopped")
    }

    /**
     * Reset checkpoint state (for new task)
     */
    fun reset() {
        lastCheckpointState = null
        checkpointCount = 0
        isBaseSent = false
        _currentState.value = null
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
 * Checkpoint message to send to foreman
 */
data class CheckpointMessage(
    val taskId: String,
    val jobId: String?,
    val workerId: String?,
    val isBase: Boolean,
    val progressPercent: Float,
    val checkpointId: Int,
    val deltaDataHex: String,
    val compressionType: String = "gzip"
) {
    /**
     * Convert to JSON string for WebSocket transmission
     * Must match foreman's expected format:
     * - "type" field (not "msg_type")
     * - lowercase enum value "task_checkpoint" (matching MessageType.TASK_CHECKPOINT.value)
     */
    fun toJsonString(): String {
        return JSONObject().apply {
            put("type", "task_checkpoint")  // Must be lowercase to match foreman's MessageType enum value
            put("job_id", jobId)
            put("data", JSONObject().apply {
                put("task_id", taskId)
                put("worker_id", workerId)
                put("is_base", isBase)
                put("progress_percent", progressPercent)
                put("checkpoint_id", checkpointId)
                put("delta_data_hex", deltaDataHex)
                put("compression_type", compressionType)
            })
        }.toString()
    }
}
