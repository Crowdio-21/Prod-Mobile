package com.example.mcc_phase3.checkpoint

import java.util.concurrent.atomic.AtomicReference

data class CheckpointSnapshot(
    val checkpointId: String,
    val taskId: String,
    val jobId: String?,
    val deltaDataHex: String,
    val progressPercent: Float,
    val stateVars: Map<String, Any?>
)

class CheckpointStateHolder {
    private val latestState = AtomicReference<CheckpointSnapshot?>(null)

    fun update(
        checkpointId: String,
        taskId: String,
        jobId: String?,
        deltaDataHex: String,
        progressPercent: Float,
        stateVars: Map<String, Any?>
    ) {
        val mergedStateVars = latestState.get()
            ?.takeIf { it.taskId == taskId && it.jobId == jobId }
            ?.stateVars
            .orEmpty() + stateVars

        latestState.set(
            CheckpointSnapshot(
                checkpointId = checkpointId,
                taskId = taskId,
                jobId = jobId,
                deltaDataHex = deltaDataHex,
                progressPercent = progressPercent,
                stateVars = mergedStateVars
            )
        )
    }

    fun get(): CheckpointSnapshot? = latestState.get()

    fun clear() {
        latestState.set(null)
    }
}