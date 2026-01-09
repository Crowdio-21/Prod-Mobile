package com.example.mcc_phase3.communication

import org.json.JSONObject

/**
 * Message protocol for communication with the foreman
 * Based on the desktop worker FastAPI implementation
 */
object MessageProtocol {
    
    // Message types matching backend format
    object MessageType {
        const val WORKER_READY = "worker_ready"
        const val ASSIGN_TASK = "assign_task"
        const val TASK_RESULT = "task_result"
        const val TASK_ERROR = "task_error"
        const val PING = "ping"
        const val PONG = "pong"
        const val WORKER_HEARTBEAT = "worker_heartbeat"
        const val WORKER_STATUS = "WORKER_STATUS"
    }
    
    /**
     * Create a worker ready message
     */
    fun createWorkerReadyMessage(workerId: String): String {
        return JSONObject().apply {
            put("type", MessageType.WORKER_READY)
            put("data", JSONObject().apply {
                put("worker_id", workerId)
            })
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
    
    /**
     * Create a task result message
     */
    fun createTaskResultMessage(
        taskId: String,
        jobId: String?,
        result: Any?,
        executionTime: Double = 0.0
    ): String {
        return JSONObject().apply {
            put("type", MessageType.TASK_RESULT)
            put("data", JSONObject().apply {
                put("task_id", taskId)
                // Handle result properly - if it's already a JSON string, parse it
                // Otherwise convert to string
                val resultValue = when (result) {
                    is String -> {
                        // Try to parse as JSON if it looks like JSON
                        try {
                            if (result.trim().startsWith("{") || result.trim().startsWith("[")) {
                                JSONObject(result)
                            } else {
                                result
                            }
                        } catch (e: Exception) {
                            result
                        }
                    }
                    null -> JSONObject.NULL
                    else -> result.toString()
                }
                put("result", resultValue)
                put("execution_time", executionTime)
            })
            // Always include job_id field, even if null (backend expects this field)
            put("job_id", jobId)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
    
    /**
     * Create a task error message
     */
    fun createTaskErrorMessage(
        taskId: String,
        jobId: String?,
        error: String
    ): String {
        return JSONObject().apply {
            put("type", MessageType.TASK_ERROR)
            put("data", JSONObject().apply {
                put("task_id", taskId)
                put("error", error)
            })
            // Always include job_id field, even if null (backend expects this field)
            put("job_id", jobId)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
    
    /**
     * Create a heartbeat message
     * Matches desktop worker format exactly:
     * {"type": "worker_heartbeat", "data": {"worker_id": "worker-3db33644", "status": "online", "current_task": null}, "job_id": null}
     * job_id is null only when no job is assigned, otherwise contains the actual job ID
     */
    fun createHeartbeatMessage(
        workerId: String,
        currentTaskId: String? = null,
        jobId: String? = null
    ): String {
        return JSONObject().apply {
            // type field (matches desktop worker format)
            put("type", MessageType.WORKER_HEARTBEAT)
            
            // data field (matches desktop worker format)
            put("data", JSONObject().apply {
                put("worker_id", workerId)
                put("status", "online")
                put("current_task", currentTaskId)
            })
            
            // job_id field - null only if not assigned, otherwise contains actual job ID
            put("job_id", jobId)
        }.toString()
    }
    
    /**
     * Create a pong message
     */
    fun createPongMessage(workerId: String): String {
        return JSONObject().apply {
            put("type", MessageType.PONG)
            put("data", JSONObject().apply {
                put("worker_id", workerId)
            })
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
    
    /**
     * Parse incoming message
     * Handles both "type" and "msg_type" fields for backward compatibility
     */
    fun parseMessage(message: String): MessageData? {
        return try {
            val json = JSONObject(message)
            // Try "msg_type" first (backend format), fallback to "type" (legacy format)
            val type = json.optString("msg_type", json.optString("type", ""))
            val data = json.optJSONObject("data")
            val jobId = json.optString("job_id", null)
            val timestamp = json.optLong("timestamp", 0)
            
            MessageData(
                type = type,
                data = data,
                jobId = jobId,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Data class for parsed messages
     */
    data class MessageData(
        val type: String,
        val data: JSONObject?,
        val jobId: String?,
        val timestamp: Long
    )
}
