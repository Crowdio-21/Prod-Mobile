package com.crowdio.mcc_phase3.communication

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

object MessageType {
    const val WORKER_READY = "worker_ready"
    const val ASSIGN_TASK = "assign_task"
    const val RESUME_TASK = "resume_task"
    const val TASK_RESULT = "task_result"
    const val TASK_ERROR = "task_error"
    const val TASK_CHECKPOINT = "task_checkpoint"
    const val PING = "ping"
    const val PONG = "pong"
    const val WORKER_HEARTBEAT = "worker_heartbeat"
    const val WORKER_STATUS = "worker_status"
    const val CHECKPOINT_ACK = "checkpoint_ack"
    const val KILL_TASK = "kill_task"
    const val KILL_ACK = "kill_ack"
}

data class InboundMessage(
    val type: String,
    val jobId: String?,
    val data: JSONObject?,
    val timestamp: Long,
    val raw: JSONObject
)

data class ResumeTaskPayload(
    val taskId: String,
    val jobId: String?,
    val checkpointId: String,
    val deltaDataHex: String,
    val progressPercent: Float,
    val stateVars: Map<String, Any?>,
    val checkpointPayload: JSONObject
) {
    companion object {
        fun from(message: InboundMessage): ResumeTaskPayload {
            require(message.type == MessageType.RESUME_TASK) {
                "Expected ${MessageType.RESUME_TASK}, got ${message.type}"
            }

            val data = message.data ?: throw IllegalArgumentException("resume_task missing data payload")
            val checkpointPayload = data.optJSONObject("checkpoint_payload")
                ?: throw IllegalArgumentException("resume_task missing checkpoint_payload")

            val taskId = data.optString("task_id")
                .ifBlank { checkpointPayload.optString("task_id") }
                .ifBlank { throw IllegalArgumentException("resume_task missing task_id") }

            val jobId = message.jobId
                ?: checkpointPayload.optString("job_id").takeIf { it.isNotBlank() }
                ?: data.optString("job_id").takeIf { it.isNotBlank() }

            val checkpointId = data.opt("checkpoint_id")?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("resume_task missing checkpoint_id")

            val deltaDataHex = checkpointPayload.optString("delta_data_hex")
                .ifBlank { throw IllegalArgumentException("resume_task missing checkpoint_payload.delta_data_hex") }

            val stateObject = ProtocolCodec.decodeCheckpointState(deltaDataHex)
            val stateVars = stateObject?.let { ProtocolCodec.jsonObjectToMap(it) }.orEmpty()
            val progressPercent = when (val progressValue = stateVars["progress_percent"]) {
                is Number -> progressValue.toFloat()
                is String -> progressValue.toFloatOrNull() ?: 0f
                else -> 0f
            }

            return ResumeTaskPayload(
                taskId = taskId,
                jobId = jobId,
                checkpointId = checkpointId,
                deltaDataHex = deltaDataHex,
                progressPercent = progressPercent,
                stateVars = stateVars,
                checkpointPayload = checkpointPayload
            )
        }
    }
}

object Protocol {
    fun parseInboundMessage(rawMessage: String): InboundMessage? {
        return try {
            val rawJson = JSONObject(rawMessage)
            val type = rawJson.optString("type", rawJson.optString("msg_type", ""))
            if (type.isBlank()) {
                return null
            }

            InboundMessage(
                type = type,
                jobId = rawJson.optString("job_id").takeIf { it.isNotBlank() },
                data = rawJson.optJSONObject("data"),
                timestamp = rawJson.optLong("timestamp", 0L),
                raw = rawJson
            )
        } catch (e: Exception) {
            Log.w("Protocol", "Failed to parse inbound message: ${e.message}")
            null
        }
    }
}

object ProtocolCodec {
    fun decodeCheckpointState(deltaDataHex: String): JSONObject? {
        if (deltaDataHex.isBlank()) {
            return null
        }

        return try {
            val bytes = hexToBytes(deltaDataHex)
            val json = GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
            JSONObject(json)
        } catch (e: Exception) {
            Log.w("ProtocolCodec", "Failed to decode checkpoint state: ${e.message}")
            null
        }
    }

    fun jsonObjectToMap(jsonObject: JSONObject): Map<String, Any?> {
        val keys = jsonObject.keys()
        val result = linkedMapOf<String, Any?>()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = jsonValueToKotlin(jsonObject.opt(key))
        }
        return result
    }

    fun mapToJsonObject(values: Map<String, Any?>): JSONObject {
        return JSONObject().apply {
            values.forEach { (key, value) ->
                put(key, kotlinValueToJson(value))
            }
        }
    }

    private fun jsonValueToKotlin(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> buildList(value.length()) {
                for (index in 0 until value.length()) {
                    add(jsonValueToKotlin(value.opt(index)))
                }
            }
            else -> value
        }
    }

    private fun kotlinValueToJson(value: Any?): Any? {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply {
                value.forEach { (nestedKey, nestedValue) ->
                    if (nestedKey is String) {
                        put(nestedKey, kotlinValueToJson(nestedValue))
                    }
                }
            }
            is Iterable<*> -> JSONArray().apply {
                value.forEach { item ->
                    put(kotlinValueToJson(item))
                }
            }
            is Array<*> -> JSONArray().apply {
                value.forEach { item ->
                    put(kotlinValueToJson(item))
                }
            }
            else -> value
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(hex.length / 2) { index ->
            val first = Character.digit(hex[index * 2], 16)
            val second = Character.digit(hex[index * 2 + 1], 16)
            require(first >= 0 && second >= 0) { "Invalid hex string" }
            ((first shl 4) + second).toByte()
        }
    }
}