package com.crowdio.mcc_phase3.execution

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

private const val SNAPSHOT_DIR = "task_recovery"
private const val SNAPSHOT_FILE = "active_task_snapshot.json"

/**
 * Persists the currently assigned task identity so process restarts can trigger server-side recovery.
 */
class ActiveTaskSnapshotStore(context: Context) {

    companion object {
        private const val TAG = "ActiveTaskSnapshotStore"
    }

    private val snapshotFile: File = File(File(context.filesDir, SNAPSHOT_DIR).apply { mkdirs() }, SNAPSHOT_FILE)

    @Synchronized
    fun save(snapshot: ActiveTaskSnapshot) {
        val json = JSONObject().apply {
            put("task_id", snapshot.taskId)
            put("job_id", snapshot.jobId)
            put("model_partition_id", snapshot.modelPartitionId)
            put("started_at", snapshot.startedAtEpochMs)
            put("is_resume", snapshot.isResume)
        }
        snapshotFile.writeText(json.toString())
    }

    @Synchronized
    fun load(): ActiveTaskSnapshot? {
        if (!snapshotFile.exists()) {
            return null
        }

        return try {
            val json = JSONObject(snapshotFile.readText())
            val taskId = json.optString("task_id", "")
            if (taskId.isBlank()) {
                null
            } else {
                ActiveTaskSnapshot(
                    taskId = taskId,
                    jobId = json.optString("job_id", null),
                    modelPartitionId = json.optString("model_partition_id", null),
                    startedAtEpochMs = json.optLong("started_at", 0L),
                    isResume = json.optBoolean("is_resume", false)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse active task snapshot", e)
            null
        }
    }

    @Synchronized
    fun clear() {
        if (snapshotFile.exists()) {
            snapshotFile.delete()
        }
    }
}

data class ActiveTaskSnapshot(
    val taskId: String,
    val jobId: String?,
    val modelPartitionId: String?,
    val startedAtEpochMs: Long,
    val isResume: Boolean
)
