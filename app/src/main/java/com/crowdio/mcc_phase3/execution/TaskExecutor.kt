package com.crowdio.mcc_phase3.execution

import com.crowdio.mcc_phase3.checkpoint.CheckpointMessage
import org.json.JSONObject

data class TaskExecutionResult(
    val result: Any?,
    val executionTime: Double = 0.0,
    val recoveryStatus: String? = null
)

interface TaskExecutor {
    suspend fun initialize(): Boolean

    suspend fun executeTask(
        taskId: String,
        jobId: String?,
        payload: JSONObject,
        onResult: suspend (TaskExecutionResult) -> Unit,
        onError: suspend (String) -> Unit,
        onCheckpoint: suspend (CheckpointMessage) -> Unit
    )

    suspend fun resumeFromCheckpoint(
        taskId: String,
        jobId: String?,
        checkpointId: String,
        deltaDataHex: String,
        stateVars: Map<String, Any?>,
        onResult: suspend (TaskExecutionResult) -> Unit,
        onError: suspend (String) -> Unit,
        onCheckpoint: suspend (CheckpointMessage) -> Unit
    )

    fun getCurrentTaskStatus(): Map<String, Any>

    fun cleanup()
}