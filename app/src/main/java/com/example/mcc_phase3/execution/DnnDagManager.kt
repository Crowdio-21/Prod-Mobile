package com.example.mcc_phase3.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class LayerNode(
    val layerId: String,
    val op: String,
    val partitionIdx: Int,
    val successors: List<String>,   // downstream layer IDs
    val predecessors: List<String>, // upstream layer IDs
)

class DnnDagManager {
    // Atomic dependency counters — one per layer, init'd to in-degree
    val dependencyCounters = ConcurrentHashMap<String, AtomicInteger>()

    // Completed layer outputs (activation tensors as byte arrays)
    val layerOutputs = ConcurrentHashMap<String, ByteArray>()

    // DAG adjacency
    val nodes = ConcurrentHashMap<String, LayerNode>()

    fun initFromPythonPlan(layers: List<Map<String, Any>>, edges: List<Pair<String, String>>) {
        // Build adjacency lists
        val succs = mutableMapOf<String, MutableList<String>>()
        val preds = mutableMapOf<String, MutableList<String>>()

        for ((src, dst) in edges) {
            succs.getOrPut(src) { mutableListOf() }.add(dst)
            preds.getOrPut(dst) { mutableListOf() }.add(src)
        }

        for (layer in layers) {
            val id = layer["layer_id"] as String
            val node = LayerNode(
                layerId = id,
                op = layer["op"] as String,
                partitionIdx = (layer["partition_idx"] as Number).toInt(),
                successors = succs[id] ?: emptyList(),
                predecessors = preds[id] ?: emptyList(),
            )
            nodes[id] = node
            // Counter = number of predecessors (in-degree)
            dependencyCounters[id] = AtomicInteger(node.predecessors.size)
        }
    }

    /**
     * Called when a layer finishes on ANY device.
     * Decrements successors' counters; returns layers that just became ready.
     */
    fun onLayerCompleted(completedLayerId: String, outputTensor: ByteArray): List<String> {
        layerOutputs[completedLayerId] = outputTensor

        val readyLayers = mutableListOf<String>()
        val node = nodes[completedLayerId] ?: return readyLayers

        for (successorId in node.successors) {
            val remaining = dependencyCounters[successorId]?.decrementAndGet() ?: continue
            if (remaining == 0) {
                // ALL predecessors done → this layer is ready to fire
                readyLayers.add(successorId)
            }
        }
        return readyLayers
    }
}
