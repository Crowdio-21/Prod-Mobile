package com.example.mcc_phase3.checkpoint

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

private const val CHECKPOINT_DIR = "local_checkpoints"

/**
 * Persists the latest checkpoint payload per task on local app storage.
 */
class LocalCheckpointStore(context: Context) {

    companion object {
        private const val TAG = "LocalCheckpointStore"
    }

    private val baseDir: File = File(context.filesDir, CHECKPOINT_DIR).apply { mkdirs() }

    fun save(taskId: String, checkpointJson: String) {
        if (taskId.isBlank()) {
            return
        }

        try {
            checkpointFileFor(taskId).writeText(checkpointJson)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist checkpoint for task $taskId: ${e.message}")
        }
    }

    fun load(taskId: String): JSONObject? {
        if (taskId.isBlank()) {
            return null
        }

        return try {
            val file = checkpointFileFor(taskId)
            if (!file.exists()) {
                null
            } else {
                JSONObject(file.readText())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load checkpoint for task $taskId: ${e.message}")
            null
        }
    }

    fun clear(taskId: String) {
        if (taskId.isBlank()) {
            return
        }

        try {
            checkpointFileFor(taskId).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear checkpoint for task $taskId: ${e.message}")
        }
    }

    private fun checkpointFileFor(taskId: String): File {
        val safeTaskId = taskId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(baseDir, "$safeTaskId.json")
    }
}
