package com.example.mcc_phase3.communication

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val OUTBOUND_QUEUE_DIR = "ws_resilience"
private const val OUTBOUND_QUEUE_FILE = "outbound_queue.json"

/**
 * Durable queue for outbound websocket messages that should be replayed after reconnect.
 */
class OutboundMessageQueueStore(context: Context) {

    companion object {
        private const val TAG = "OutboundMessageQueue"
        private const val MAX_QUEUE_SIZE = 500
    }

    private val queueFile: File = File(File(context.filesDir, OUTBOUND_QUEUE_DIR).apply { mkdirs() }, OUTBOUND_QUEUE_FILE)

    @Synchronized
    fun enqueue(message: String, type: String) {
        val queue = readQueue()
        if (queue.length() >= MAX_QUEUE_SIZE) {
            queue.remove(0)
        }

        queue.put(
            JSONObject().apply {
                put("message", message)
                put("type", type)
                put("enqueued_at", System.currentTimeMillis())
            }
        )
        writeQueue(queue)
    }

    @Synchronized
    fun drainAllMessages(): List<String> {
        val queue = readQueue()
        val messages = mutableListOf<String>()

        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            val message = item.optString("message", "")
            if (message.isNotBlank()) {
                messages.add(message)
            }
        }

        writeQueue(JSONArray())
        return messages
    }

    @Synchronized
    fun size(): Int = readQueue().length()

    private fun readQueue(): JSONArray {
        if (!queueFile.exists()) {
            return JSONArray()
        }

        return try {
            JSONArray(queueFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read outbound queue, resetting", e)
            JSONArray()
        }
    }

    private fun writeQueue(queue: JSONArray) {
        queueFile.writeText(queue.toString())
    }
}
