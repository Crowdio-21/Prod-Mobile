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

    /**
     * Returns true if [taskId] is the task currently being executed
     * (i.e. Python is running it right now).  Does NOT return true for
     * tasks that are only queued and waiting.
     */
    fun isTaskActive(taskId: String): Boolean

    /**
     * Pre-cancel a task that has not started executing yet (still in the
     * internal queue).  If the task is already running, this is a no-op —
     * use [killTask] instead.  The task will be silently dropped when it
     * reaches the front of the queue.
     */
    fun cancelTask(taskId: String)

    fun killTask(taskId: String)

    fun cleanup()
}