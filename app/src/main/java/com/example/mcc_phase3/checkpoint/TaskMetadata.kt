package com.example.mcc_phase3.checkpoint

import org.json.JSONObject

/**
 * Task metadata containing checkpointing configuration from the @crowdio.task decorator.
 * This is sent by foreman in ASSIGN_TASK and RESUME_TASK messages.
 */
data class TaskMetadata(
    val checkpointEnabled: Boolean = false,
    val checkpointInterval: Float = 10.0f,          // Seconds between checkpoints
    val checkpointState: List<String> = emptyList(), // Variable names to capture
    val retryOnFailure: Boolean = false,
    val maxRetries: Int = 3,
    val parallel: Boolean = true
) {
    companion object {
        /**
         * Parse TaskMetadata from JSON object
         */
        fun fromJson(json: JSONObject?): TaskMetadata {
            if (json == null) {
                return TaskMetadata()
            }
            
            // Parse checkpoint_state array
            val checkpointStateVars = mutableListOf<String>()
            val stateArray = json.optJSONArray("checkpoint_state")
            if (stateArray != null) {
                for (i in 0 until stateArray.length()) {
                    checkpointStateVars.add(stateArray.optString(i, ""))
                }
            }
            
            return TaskMetadata(
                checkpointEnabled = json.optBoolean("checkpoint_enabled", false),
                checkpointInterval = json.optDouble("checkpoint_interval", 10.0).toFloat(),
                checkpointState = checkpointStateVars,
                retryOnFailure = json.optBoolean("retry_on_failure", false),
                maxRetries = json.optInt("max_retries", 3),
                parallel = json.optBoolean("parallel", true)
            )
        }
    }
    
    /**
     * Convert to JSON object
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("checkpoint_enabled", checkpointEnabled)
            put("checkpoint_interval", checkpointInterval)
            put("checkpoint_state", checkpointState)
            put("retry_on_failure", retryOnFailure)
            put("max_retries", maxRetries)
            put("parallel", parallel)
        }
    }
}
