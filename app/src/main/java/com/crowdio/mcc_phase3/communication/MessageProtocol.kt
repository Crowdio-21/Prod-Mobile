package com.crowdio.mcc_phase3.communication

import android.content.Context
import org.json.JSONObject

/**
 * Message protocol for communication with the foreman
 * Based on the desktop worker FastAPI implementation
 */
object MessageProtocol {
    
    // Message types matching backend format (lowercase to match foreman's MessageType enum values)
    object MessageType {
        const val WORKER_READY = "worker_ready"
        const val ASSIGN_TASK = "assign_task"
        const val TASK_RESULT = "task_result"
        const val TASK_ERROR = "task_error"
        const val PING = "ping"
        const val PONG = "pong"
        const val WORKER_HEARTBEAT = "worker_heartbeat"
        const val WORKER_STATUS = "worker_status"
        const val RESUME_TASK = "resume_task"        // Foreman -> Worker: Resume task from checkpoint
        const val CHECKPOINT_ACK = "checkpoint_ack"  // Foreman -> Worker: Checkpoint received acknowledgment
    }
    
    /**
     * Create a worker ready message with device specifications
     * Enhanced to use DeviceInfoCollector for comprehensive device info
     * 
     * CRITICAL for foreman integration:
     * - worker_type: "android_chaquopy" tells foreman that sys.settrace doesn't work
     *   and code needs instrumentation for checkpoint callbacks
     * - capabilities: tells foreman what checkpoint format this worker uses
     */
    fun createWorkerReadyMessage(
        workerId: String,
        context: Context
    ): String {
        val collector = DeviceInfoCollector(context)
        val specs = collector.getDeviceSpecs()
        
        return JSONObject().apply {
            put("type", MessageType.WORKER_READY)
            put("data", JSONObject().apply {
                put("worker_id", workerId)
                
                // CRITICAL: Tell foreman this is an Android/Chaquopy worker
                // Foreman uses this to instrument code with explicit checkpoint callbacks
                // since sys.settrace() doesn't work on Chaquopy
                put("worker_type", "android_chaquopy")
                
                // Worker capabilities for checkpoint system
                put("capabilities", JSONObject().apply {
                    put("supports_checkpointing", true)
                    put("checkpoint_format", "json")       // JSON format for cross-platform compatibility
                    put("supports_resume", true)           // Can resume tasks from checkpoint state
                    put("supports_delta_checkpoints", true) // Supports incremental delta checkpoints
                    put("compression_supported", "gzip")   // Compression type for checkpoint data
                })
                
                put("device_specs", JSONObject(specs.toMap()))
            })
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
    
    /**
     * Legacy method with manual parameters (deprecated, use context-based version)
     */
    @Deprecated("Use createWorkerReadyMessage(workerId, context) instead")
    fun createWorkerReadyMessageLegacy(
        workerId: String,
        deviceType: String = "Android",
        osType: String = "Android",
        osVersion: String? = null,
        cpuModel: String? = null,
        cpuCores: Int? = null,
        cpuThreads: Int? = null,
        cpuFrequencyMhz: Float? = null,
        ramTotalMb: Float? = null,
        ramAvailableMb: Float? = null,
        gpuModel: String? = null,
        batteryLevel: Float? = null,
        isCharging: Boolean? = null,
        networkType: String? = null,
        pythonVersion: String? = null
    ): String {
        return JSONObject().apply {
            put("type", MessageType.WORKER_READY)
            put("data", JSONObject().apply {
                put("worker_id", workerId)
                
                // Create nested device_specs object matching PC worker format
                put("device_specs", JSONObject().apply {
                    put("device_type", deviceType)
                    put("os_type", osType)
                    
                    // Optional fields - only include if not null
                    osVersion?.let { put("os_version", it) }
                    cpuModel?.let { put("cpu_model", it) }
                    cpuCores?.let { put("cpu_cores", it) }
                    cpuThreads?.let { put("cpu_threads", it) }
                    cpuFrequencyMhz?.let { put("cpu_frequency_mhz", it) }
                    ramTotalMb?.let { put("ram_total_mb", it) }
                    ramAvailableMb?.let { put("ram_available_mb", it) }
                    gpuModel?.let { put("gpu_model", it) }
                    batteryLevel?.let { put("battery_level", it) }
                    isCharging?.let { put("is_charging", it) }
                    networkType?.let { put("network_type", it) }
                    pythonVersion?.let { put("python_version", it) }
                })
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
                        val trimmed = result.trim()
                        // Try to parse as JSON if it looks like JSON
                        try {
                            when {
                                trimmed.startsWith("{") -> JSONObject(trimmed)
                                trimmed.startsWith("[") -> org.json.JSONArray(trimmed)
                                trimmed.isBlank() -> JSONObject()   // blank → empty object
                                else -> result
                            }
                        } catch (e: Exception) {
                            result
                        }
                    }
                    null -> JSONObject()   // null → empty object, never JSONObject.NULL
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
     * Create a task result message with recovery_status for resumed tasks
     */
    fun createTaskResultMessageWithRecoveryStatus(
        taskId: String,
        jobId: String?,
        result: Any?,
        executionTime: Double = 0.0,
        recoveryStatus: String = "resumed"
    ): String {
        return JSONObject().apply {
            put("type", MessageType.TASK_RESULT)
            put("data", JSONObject().apply {
                put("task_id", taskId)
                // Handle result properly - if it's already a JSON string, parse it
                val resultValue = when (result) {
                    is String -> {
                        val trimmed = result.trim()
                        try {
                            when {
                                trimmed.startsWith("{") -> JSONObject(trimmed)
                                trimmed.startsWith("[") -> org.json.JSONArray(trimmed)
                                trimmed.isBlank() -> JSONObject()
                                else -> result
                            }
                        } catch (e: Exception) {
                            result
                        }
                    }
                    null -> JSONObject()
                    else -> result.toString()
                }
                put("result", resultValue)
                put("execution_time", executionTime)
                put("recovery_status", recoveryStatus)  // Indicate this was a resumed task
            })
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
     * Create a heartbeat message with performance metrics
     * Matches desktop worker format with optional performance data
     */
    fun createHeartbeatMessage(
        workerId: String,
        currentTaskId: String? = null,
        jobId: String? = null,
        context: Context? = null
    ): String {
        return JSONObject().apply {
            put("type", MessageType.WORKER_HEARTBEAT)
            
            put("data", JSONObject().apply {
                put("worker_id", workerId)
                put("status", "online")
                put("current_task", currentTaskId)
                
                // Add performance metrics if context is provided
                context?.let {
                    val collector = DeviceInfoCollector(it)
                    val metrics = collector.getPerformanceMetrics()
                    val metricsMap = metrics.toMap()
                    metricsMap.forEach { (key, value) ->
                        put(key, value)
                    }
                }
            })
            
            put("job_id", jobId)
        }.toString()
    }
    
    /**
     * Create a pong message with optional performance metrics
     */
    fun createPongMessage(
        workerId: String,
        context: Context? = null
    ): String {
        return JSONObject().apply {
            put("msg_type", MessageType.PONG)
            put("data", JSONObject().apply {
                put("worker_id", workerId)
                put("status", "online")
                
                // Add performance metrics if context is provided
                context?.let {
                    val collector = DeviceInfoCollector(it)
                    val metrics = collector.getPerformanceMetrics()
                    val metricsMap = metrics.toMap()
                    metricsMap.forEach { (key, value) ->
                        put(key, value)
                    }
                }
            })
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
    
    /**
     * Parse incoming message
     * Handles both "type" and "type" fields for backward compatibility
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
