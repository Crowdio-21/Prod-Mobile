package com.crowdio.mcc_phase3.communication

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.LinkedHashMap

/**
 * Tracks latest DNN control-plane state and deduplicates repeated updates.
 */
class DnnStateStore(context: Context) {

    companion object {
        private const val TAG = "DnnStateStore"
        private const val DEDUPE_WINDOW_MS = 60_000L
    }

    private val partitionAssignments = mutableMapOf<String, String>()
    private val fallbackModesByTask = mutableMapOf<String, String>()

    @Volatile
    private var latestDeviceTopology: JSONObject? = null

    @Volatile
    private var latestAggregationConfig: JSONObject? = null

    private val recentTopologyKeys = LinkedHashMap<String, Long>()
    private val recentFallbackKeys = LinkedHashMap<String, Long>()

    fun onDeviceTopology(data: JSONObject) {
        latestDeviceTopology = JSONObject(data.toString())
        refreshPartitionAssignments(data)
        Log.i(TAG, "Device topology updated")
    }

    @Synchronized
    fun isDuplicateTopologyUpdate(data: JSONObject): Boolean {
        val key = listOf(
            data.optString("inference_graph_id", ""),
            data.optString("reason", ""),
            data.optJSONArray("nodes")?.toString() ?: "[]",
            data.optJSONArray("edges")?.toString() ?: "[]"
        ).joinToString("|")

        val now = System.currentTimeMillis()
        pruneOld(recentTopologyKeys, now)
        if (recentTopologyKeys.containsKey(key)) {
            return true
        }

        recentTopologyKeys[key] = now
        latestDeviceTopology = JSONObject(data.toString())
        refreshPartitionAssignments(data)
        return false
    }

    fun onAggregationConfig(data: JSONObject) {
        latestAggregationConfig = JSONObject(data.toString())
        Log.i(TAG, "Aggregation config received: ${data.optString("aggregation_strategy", "unknown")}")
    }

    @Synchronized
    fun isDuplicateFallbackDecision(data: JSONObject): Boolean {
        val key = listOf(
            data.optString("task_id", ""),
            data.optString("fallback_mode", ""),
            data.optString("reason", "")
        ).joinToString("|")

        val now = System.currentTimeMillis()
        pruneOld(recentFallbackKeys, now)
        if (recentFallbackKeys.containsKey(key)) {
            return true
        }

        recentFallbackKeys[key] = now
        val taskId = data.optString("task_id", "")
        val mode = data.optString("fallback_mode", "")
        if (taskId.isNotBlank() && mode.isNotBlank()) {
            fallbackModesByTask[taskId] = mode
        }
        return false
    }

    @Synchronized
    fun getAssignedWorkerForPartition(modelPartitionId: String): String? {
        return partitionAssignments[modelPartitionId]
    }

    @Synchronized
    fun getFallbackModeForTask(taskId: String): String? {
        return fallbackModesByTask[taskId]
    }

    @Synchronized
    fun clearFallbackModeForTask(taskId: String) {
        fallbackModesByTask.remove(taskId)
    }

    fun getLatestDeviceTopology(): JSONObject? = latestDeviceTopology?.let { JSONObject(it.toString()) }

    fun getLatestAggregationConfig(): JSONObject? = latestAggregationConfig?.let { JSONObject(it.toString()) }

    private fun pruneOld(entries: LinkedHashMap<String, Long>, now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (now - item.value > DEDUPE_WINDOW_MS) {
                iterator.remove()
            }
        }
    }

    @Synchronized
    private fun refreshPartitionAssignments(data: JSONObject) {
        val nodes = data.optJSONArray("nodes") ?: return

        val updated = mutableMapOf<String, String>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val partitionId = node.optString("model_partition_id", "")
            val workerId = node.optString("worker_id", "")
            if (partitionId.isBlank() || workerId.isBlank()) {
                continue
            }
            updated[partitionId] = workerId
        }

        if (updated.isNotEmpty()) {
            partitionAssignments.clear()
            partitionAssignments.putAll(updated)
            Log.i(TAG, "Updated ${updated.size} partition assignments from topology")
        }
    }
}
