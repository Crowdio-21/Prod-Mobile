package com.example.mcc_phase3.execution

import kotlinx.coroutines.*

/**
 * Single-device orchestrator: no Ktor networking needed, just runs layers sequentially.
 */
class SingleDeviceOrchestrator(
    private val dagManager: DnnDagManager,
    private val inferenceEngine: PythonInferenceEngine
) {
    suspend fun runAll(inputTensor: ByteArray) {
        val ready = mutableListOf<String>()
        for ((layerId, counter) in dagManager.dependencyCounters) {
            if (counter.get() == 0) ready.add(layerId)
        }

        while (ready.isNotEmpty()) {
            val layerId = ready.removeFirst()
            val node = dagManager.nodes[layerId]!!

            val inputs = node.predecessors.mapNotNull { dagManager.layerOutputs[it] }
            val input = if (inputs.isEmpty()) inputTensor
                        else inputs.reduce { a, b -> a + b }

            val output = withContext(Dispatchers.IO) {
                inferenceEngine.runLayerSlice(layerId, input, mapOf("op" to node.op))
            }

            val nextReady = dagManager.onLayerCompleted(layerId, output)
            ready.addAll(nextReady)
        }
    }
}

class DnnOrchestrator(
    private val dagManager: DnnDagManager,
    private val inferenceEngine: PythonInferenceEngine,
    private val transport: TensorTransport,
    private val deviceId: Int,                          // this device's partition index
    private val peerDevices: Map<Int, Pair<String, Int>> // partitionIdx → (host, port)
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        // Listen for incoming tensors from peer devices
        scope.launch {
            transport.receiveTensor(port = 5050) { layerId, tensorBytes ->
                handleIncomingTensor(layerId, tensorBytes)
            }
        }
    }

    private suspend fun handleIncomingTensor(completedLayerId: String, outputTensor: ByteArray) {
        // Atomic decrement — returns layers whose counters just hit zero
        val readyLayers = dagManager.onLayerCompleted(completedLayerId, outputTensor)

        for (layerId in readyLayers) {
            val node = dagManager.nodes[layerId]!!
            if (node.partitionIdx == deviceId) {
                // This device owns this layer → execute locally in Python
                executeLayer(layerId, node)
            }
            // If owned by another device, that device will also receive the
            // tensor via its own transport listener and trigger execution there.
        }
    }

    private suspend fun executeLayer(layerId: String, node: LayerNode) {
        // Gather all predecessor outputs (they are all available since counter=0)
        val inputs = node.predecessors.mapNotNull { dagManager.layerOutputs[it] }

        // Concatenate or select primary input
        val inputBytes = if (inputs.size == 1) inputs[0]
                         else inputs.reduce { a, b -> a + b } // fuse in Python

        val params = mapOf("op" to node.op, "filters" to 64)

        // Run inference via Chaquopy
        val output = withContext(Dispatchers.IO) {
            inferenceEngine.runLayerSlice(layerId, inputBytes, params)
        }

        // Notify DAG and propagate
        val nextReady = dagManager.onLayerCompleted(layerId, output)

        for (nextId in nextReady) {
            val nextNode = dagManager.nodes[nextId]!!
            if (nextNode.partitionIdx == deviceId) {
                executeLayer(nextId, nextNode)
            } else {
                // Send tensor to the device that owns the next layer
                val (host, port) = peerDevices[nextNode.partitionIdx]!!
                transport.sendTensor(host, port, layerId, output)
            }
        }
    }
}
