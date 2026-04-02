package com.example.mcc_phase3.communication

import android.content.Context
import android.util.Log
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.execution.TaskProcessor
import com.example.mcc_phase3.checkpoint.CheckpointMessage
import com.example.mcc_phase3.checkpoint.LocalCheckpointStore
import com.example.mcc_phase3.model.ModelArtifactCache
import com.example.mcc_phase3.model.ModelDownloadClient
import com.example.mcc_phase3.utils.EventLogger
import com.example.mcc_phase3.utils.NotificationHelper
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Kotlin WebSocket client for worker communication
 * This class handles:
 * - WebSocket connection to backend
 * - Sending worker registration and status updates
 * - Receiving task assignments
 * - NOT executing Python code (delegated to TaskProcessor)
 */
class WorkerWebSocketClient(
    private val context: Context,
    private val taskProcessor: TaskProcessor
) {
    
    companion object {
        private const val TAG = "WorkerWebSocketClient"
        private const val HEARTBEAT_INTERVAL = 30000L // 30 seconds
        private const val RECONNECT_DELAY = 5000L // 5 seconds
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }
    
    private val workerIdManager = WorkerIdManager.getInstance(context)
    private val dnnStateStore = DnnStateStore(context)
    private val modelArtifactCache = ModelArtifactCache(context)
    private val modelDownloadClient = ModelDownloadClient()
    private val outboundQueueStore = OutboundMessageQueueStore(context)
    private val localCheckpointStore = LocalCheckpointStore(context)
    private val pendingIntermediateFeatures = ConcurrentHashMap<String, MutableList<JSONObject>>()
    
    private var webSocket: WebSocketClient? = null
    private val isConnected = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private val reconnectAttempts = AtomicLong(0)
    
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Listeners
    private val listeners = mutableSetOf<WorkerWebSocketListener>()
    
    /**
     * Connect to the backend WebSocket server
     */
    suspend fun connect(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to WebSocket: $url")
                
                // Initialize task processor
                val processorInitialized = taskProcessor.initialize()
                if (!processorInitialized) {
                    Log.e(TAG, "Failed to initialize task processor")
                    return@withContext false
                }
                
                // Set up checkpoint callback to send checkpoints via WebSocket
                taskProcessor.setCheckpointCallback { checkpointMsg ->
                    sendCheckpointMessage(checkpointMsg)
                }
                
                // Disconnect if already connected
                if (isConnected.get()) {
                    disconnect()
                }
                
                val uri = URI(url)
                webSocket = object : WebSocketClient(uri) {
                    override fun onOpen(handshake: ServerHandshake?) {
                        Log.d(TAG, "WebSocket connected to $url")
                        EventLogger.success(EventLogger.Categories.WEBSOCKET, "Connected to foreman: $url")
                        NotificationHelper.notifyWorkerConnected(context)
                        isConnected.set(true)
                        reconnectAttempts.set(0)
                        
                        // Start heartbeat
                        startHeartbeat()
                        
                        // Register worker
                        registerWorker()

                        // Replay unsent durable messages (results/checkpoints/features)
                        replayQueuedMessages()
                        
                        // Notify listeners
                        listeners.forEach { it.onConnected() }
                    }
                    
                    override fun onMessage(message: String?) {
                        message?.let { msg ->
                            Log.d(TAG, "Received message: $msg")
                            clientScope.launch {
                                handleMessage(msg)
                            }
                        }
                    }
                    
                    override fun onClose(code: Int, reason: String?, remote: Boolean) {
                        Log.w(TAG, "WebSocket closed (code=$code, reason=$reason, remote=$remote)")
                        EventLogger.warning(EventLogger.Categories.WEBSOCKET, "Disconnected (code=$code, reason=$reason)")
                        NotificationHelper.notifyWorkerDisconnected(context, reason)
                        isConnected.set(false)
                        stopHeartbeat()
                        
                        // Notify listeners
                        listeners.forEach { it.onDisconnected() }
                        
                        // Attempt reconnection if not manually disconnected
                        if (isRunning.get() && !remote) {
                            scheduleReconnection(url)
                        }
                    }
                    
                    override fun onError(ex: Exception?) {
                        Log.e(TAG, "WebSocket error", ex)
                        EventLogger.error(EventLogger.Categories.WEBSOCKET, "Connection error: ${ex?.message ?: "Unknown"}")
                        listeners.forEach { it.onError(ex) }
                    }
                }
                
                webSocket?.connect()
                isRunning.set(true)
                
                // Wait for connection with timeout
                var attempts = 0
                while (!isConnected.get() && attempts < 30) { // 30 second timeout
                    delay(1000)
                    attempts++
                }
                
                isConnected.get()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to connect to WebSocket", e)
                false
            }
        }
    }
    
    /**
     * Disconnect from WebSocket
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting WebSocket...")
        isRunning.set(false)
        stopHeartbeat()
        reconnectJob?.cancel()
        webSocket?.close()
        webSocket = null
        isConnected.set(false)
        Log.d(TAG, "WebSocket disconnected")
    }
    
    /**
     * Send message to backend
     */
    fun sendMessage(message: String) {
        val type = extractMessageType(message)
        val taskId = extractTaskId(message)

        if (isConnected.get()) {
            try {
                webSocket?.send(message)
                Log.d(TAG, "Message sent: $message")
                maybeClearLocalCheckpoint(type, taskId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                if (shouldPersistForReplay(type)) {
                    outboundQueueStore.enqueue(message, type)
                    Log.w(TAG, "Queued outbound message for replay (type=$type)")
                }
            }
        } else {
            Log.w(TAG, "Cannot send message - not connected")
            if (shouldPersistForReplay(type)) {
                outboundQueueStore.enqueue(message, type)
                Log.w(TAG, "Queued outbound message while offline (type=$type)")
            }
        }
    }
    
    /**
     * Send checkpoint message to foreman via WebSocket
     */
    private suspend fun sendCheckpointMessage(checkpoint: CheckpointMessage) {
        withContext(Dispatchers.IO) {
            try {
                val json = checkpoint.toJsonString()
                sendMessage(json)
                localCheckpointStore.save(checkpoint.taskId, json)

                val checkpointType = if (checkpoint.isBase) "BASE" else "DELTA"
                Log.i(TAG, "[Checkpoint] Task ${checkpoint.taskId} | $checkpointType #${checkpoint.checkpointId} | " +
                        "Progress: ${checkpoint.progressPercent}%")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending checkpoint: ${e.message}")
            }
        }
    }

    private fun replayQueuedMessages() {
        clientScope.launch {
            val pendingCount = outboundQueueStore.size()
            if (pendingCount <= 0) {
                return@launch
            }

            val queuedMessages = outboundQueueStore.drainAllMessages()
            if (queuedMessages.isEmpty()) {
                return@launch
            }

            Log.i(TAG, "Replaying ${queuedMessages.size} queued outbound messages")

            for (message in queuedMessages) {
                if (!isConnected.get()) {
                    val type = extractMessageType(message)
                    if (shouldPersistForReplay(type)) {
                        outboundQueueStore.enqueue(message, type)
                    }
                    break
                }

                try {
                    webSocket?.send(message)
                } catch (e: Exception) {
                    val type = extractMessageType(message)
                    if (shouldPersistForReplay(type)) {
                        outboundQueueStore.enqueue(message, type)
                    }
                    Log.w(TAG, "Failed replay for message type=$type, re-queued")
                    break
                }
            }
        }
    }

    private fun extractMessageType(message: String): String {
        return try {
            val json = JSONObject(message)
            val type = json.optString("type", json.optString("msg_type", ""))
            type.lowercase()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractTaskId(message: String): String {
        return try {
            val json = JSONObject(message)
            val data = json.optJSONObject("data")
            data?.optString("task_id", "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun maybeClearLocalCheckpoint(type: String, taskId: String) {
        if (taskId.isBlank()) {
            return
        }

        if (type == MessageProtocol.MessageType.TASK_RESULT || type == MessageProtocol.MessageType.TASK_ERROR) {
            localCheckpointStore.clear(taskId)
        }
    }

    private fun shouldPersistForReplay(type: String): Boolean {
        return type == MessageProtocol.MessageType.TASK_RESULT ||
            type == MessageProtocol.MessageType.TASK_ERROR ||
            type == MessageProtocol.MessageType.INTERMEDIATE_FEATURE ||
            type == "task_checkpoint"
    }

    private fun maybeReportPendingRecovery() {
        val snapshot = taskProcessor.getPendingRecoverySnapshot() ?: return
        if (snapshot.taskId.isBlank()) {
            return
        }

        val latestCheckpoint = localCheckpointStore.load(snapshot.taskId)
        val checkpointHint = latestCheckpoint
            ?.optJSONObject("data")
            ?.optInt("checkpoint_id", -1)
            ?.takeIf { it >= 0 }

        val recoveryError = MessageProtocol.createTaskErrorMessage(
            taskId = snapshot.taskId,
            jobId = snapshot.jobId,
            error = if (checkpointHint != null) {
                "Worker restart detected before task completion; resume from latest checkpoint (last local checkpoint_id=$checkpointHint)"
            } else {
                "Worker restart detected before task completion; resume from latest checkpoint"
            }
        )

        sendMessage(recoveryError)
        taskProcessor.clearPendingRecoverySnapshot()
        Log.i(TAG, "Reported pending recovery task ${snapshot.taskId} to foreman")
    }
    
    /**
     * Register worker with backend
     */
    private fun registerWorker() {
        clientScope.launch {
            try {
                val workerId = workerIdManager.getOrGenerateWorkerId()
                
                // Create WORKER_READY message with device specs (auto-collected)
                Log.d(TAG, "Collecting device specifications...")
                val readyJson = JSONObject(MessageProtocol.createWorkerReadyMessage(
                    workerId = workerId,
                    context = context
                ))

                // Report model partitions already cached on disk so foreman
                // can skip re-downloading them (from_cache optimisation).
                val cachedPartitions = modelArtifactCache.allArtifacts()
                    .map { it.modelPartitionId }
                if (cachedPartitions.isNotEmpty()) {
                    readyJson.getJSONObject("data")
                        .put("cached_model_partitions", org.json.JSONArray(cachedPartitions))
                    Log.d(TAG, "Reporting ${cachedPartitions.size} cached model partitions")
                }

                sendMessage(readyJson.toString())
                Log.d(TAG, "Worker ready message sent with device specs: $workerId")
                maybeReportPendingRecovery()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send worker ready message", e)
            }
        }
    }
    
    /**
     * Handle incoming messages from backend
     */
    private suspend fun handleMessage(message: String) {
        try {
            Log.d(TAG, "Processing message: $message")
            
            // Parse the message using the new protocol
            val messageData = MessageProtocol.parseMessage(message)
            if (messageData == null) {
                Log.w(TAG, "Failed to parse message: $message")
                return
            }
            
            // Handle different message types
            when (messageData.type) {
                MessageProtocol.MessageType.ASSIGN_TASK -> {
                    handleTaskAssignment(messageData)
                }
                MessageProtocol.MessageType.RESUME_TASK -> {
                    handleTaskResumption(messageData)
                }
                MessageProtocol.MessageType.PING -> {
                    handlePing(messageData)
                }
                MessageProtocol.MessageType.CHECKPOINT_ACK -> {
                    // Checkpoint acknowledgment received, log for debugging
                    val data = messageData.data
                    val checkpointId = data?.optInt("checkpoint_id", -1) ?: -1
                    val taskId = data?.optString("task_id", "") ?: ""
                    Log.d(TAG, "Checkpoint #$checkpointId acknowledged for task $taskId")
                }
                MessageProtocol.MessageType.LOAD_MODEL -> {
                    handleLoadModel(messageData)
                }
                MessageProtocol.MessageType.UNLOAD_MODEL -> {
                    handleUnloadModel(messageData)
                }
                MessageProtocol.MessageType.DEVICE_TOPOLOGY -> {
                    handleDeviceTopology(messageData)
                }
                MessageProtocol.MessageType.TOPOLOGY_UPDATE -> {
                    handleTopologyUpdate(messageData)
                }
                MessageProtocol.MessageType.AGGREGATION_CONFIG -> {
                    handleAggregationConfig(messageData)
                }
                MessageProtocol.MessageType.FALLBACK_DECISION -> {
                    handleFallbackDecision(messageData)
                }
                MessageProtocol.MessageType.INTERMEDIATE_FEATURE -> {
                    handleIntermediateFeature(messageData)
                }
                else -> {
                    Log.w(TAG, "Unknown message type: ${messageData.type}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }
    
    /**
     * Handle task assignment from foreman
     */
    private suspend fun handleTaskAssignment(messageData: MessageProtocol.MessageData) {
        // Declare data outside try-catch so it's accessible in catch block
        val data = messageData.data
        
        try {
            if (data == null) {
                Log.w(TAG, "No data in task assignment message")
                return
            }
            
            val taskId = data.optString("task_id", "")
            val jobId = messageData.jobId
            val funcCode = data.optString("func_code", "")
            val taskArgs = data.optString("task_args", "")

            injectPendingIntermediateFeatures(data, taskId)
            
            Log.d(TAG, "Received task assignment: $taskId for job: $jobId")
            Log.d(TAG, "Function code length: ${funcCode.length}, Task args: $taskArgs")
            EventLogger.info(EventLogger.Categories.TASK, "Received task assignment: $taskId (Job: $jobId)")
            
            // Create a proper task message for TaskProcessor
            val taskMessage = JSONObject().apply {
                put("type", "assign_task")
                put("data", data)
                if (jobId != null) {
                    put("job_id", jobId)
                }
                put("timestamp", System.currentTimeMillis())
            }.toString()
            
            // Process the task through TaskProcessor
            val response = taskProcessor.processTaskMessage(taskMessage)
            
            // Send response back to backend
            response?.let { resp ->
                sendMessage(resp)
                maybeSendIntermediateFeature(jobId = jobId, taskId = taskId, taskResultMessage = resp)
                Log.d(TAG, "Task result sent: $resp")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling task assignment", e)
            
            // Send error response
            val errorResponse = MessageProtocol.createTaskErrorMessage(
                taskId = data?.optString("task_id", "") ?: "unknown",
                jobId = messageData.jobId,
                error = "Task assignment failed: ${e.message ?: "Unknown error"}"
            )
            sendMessage(errorResponse)
        }
    }
    
    /**
     * Handle task resumption from foreman (resume from checkpoint)
     */
    private suspend fun handleTaskResumption(messageData: MessageProtocol.MessageData) {
        val data = messageData.data
        
        try {
            if (data == null) {
                Log.w(TAG, "No data in task resumption message")
                return
            }
            
            val taskId = data.optString("task_id", "")
            val jobId = messageData.jobId
            val funcCode = data.optString("func_code", "")
            val taskArgs = data.optString("task_args", "")

            injectPendingIntermediateFeatures(data, taskId)
            
            Log.d(TAG, "Received task RESUMPTION: $taskId for job: $jobId")
            Log.d(TAG, "Function code length: ${funcCode.length}, Task args: $taskArgs")
            
            // Check if checkpoint data is present
            val checkpointData = if (data.has("checkpoint_data") && !data.isNull("checkpoint_data")) {
                data.optString("checkpoint_data")
            } else {
                null
            }
            val checkpointCount = data.optInt("checkpoint_count", 0)
            if (checkpointData != null) {
                Log.d(TAG, "Checkpoint data present, resuming from checkpoint #$checkpointCount")
            }
            
            // Create a proper resume_task message for TaskProcessor
            val taskMessage = JSONObject().apply {
                put("type", MessageProtocol.MessageType.RESUME_TASK)
                put("data", data)
                if (jobId != null) {
                    put("job_id", jobId)
                }
                put("timestamp", System.currentTimeMillis())
            }.toString()
            
            // Process the task resumption through TaskProcessor
            val response = taskProcessor.processTaskMessage(taskMessage)
            
            // Send response back to backend
            response?.let { resp ->
                sendMessage(resp)
                maybeSendIntermediateFeature(jobId = jobId, taskId = taskId, taskResultMessage = resp)
                Log.d(TAG, "Task result sent (resumed): $resp")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling task resumption", e)
            
            // Send error response
            val errorResponse = MessageProtocol.createTaskErrorMessage(
                taskId = data?.optString("task_id", "") ?: "unknown",
                jobId = messageData.jobId,
                error = "Task resumption failed: ${e.message ?: "Unknown error"}"
            )
            sendMessage(errorResponse)
        }
    }
    
    /**
     * Handle ping message from foreman
     */
    private fun handlePing(messageData: MessageProtocol.MessageData) {
        try {
            Log.d(TAG, "Received ping, sending pong")
            val workerId = workerIdManager.getCurrentWorkerId() ?: "unknown"
            val pongMessage = MessageProtocol.createPongMessage(workerId, context)
            sendMessage(pongMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ping", e)
        }
    }

    private suspend fun handleLoadModel(messageData: MessageProtocol.MessageData) {
        val data = messageData.data
        if (data == null) {
            Log.w(TAG, "LOAD_MODEL message had no data")
            return
        }

        val modelVersionId = data.optString("model_version_id", "")
        val modelPartitionId = data.optString("model_partition_id", "")
        val modelUri = data.optString("model_uri", "")
        val checksum = data.optString("checksum", "")
        val fromCache = data.optBoolean("from_cache", false)

        if (modelVersionId.isBlank() || modelPartitionId.isBlank()) {
            Log.e(TAG, "LOAD_MODEL missing required fields: version=$modelVersionId partition=$modelPartitionId")
            return
        }

        // Fast path: model already on disk, just re-register metadata
        if (fromCache) {
            try {
                val cached = modelArtifactCache.getArtifact(modelPartitionId)
                if (cached != null) {
                    taskProcessor.registerModelArtifact(cached)
                    Log.i(TAG, "LOAD_MODEL from_cache hit for partition $modelPartitionId")
                    val workerId = workerIdManager.getCurrentWorkerId() ?: "unknown"
                    val ackMessage = MessageProtocol.createModelLoadedMessage(
                        workerId = workerId,
                        jobId = messageData.jobId,
                        modelVersionId = modelVersionId,
                        modelPartitionId = modelPartitionId
                    )
                    sendMessage(ackMessage)
                    return
                }
                Log.w(TAG, "LOAD_MODEL from_cache miss for partition $modelPartitionId, falling through to download")
            } catch (e: Exception) {
                Log.w(TAG, "LOAD_MODEL from_cache check failed for $modelPartitionId, falling through to download", e)
            }
        }

        if (modelUri.isBlank() || checksum.isBlank()) {
            Log.e(TAG, "LOAD_MODEL missing uri/checksum for download: uri=${modelUri.isNotBlank()} checksum=${checksum.isNotBlank()}")
            return
        }

        var tempArtifactPath: String? = null
        try {
            val downloadedArtifact = modelDownloadClient.downloadToTempFile(
                modelUri = modelUri,
                tempDir = context.cacheDir
            )
            tempArtifactPath = downloadedArtifact.tempFile.absolutePath

            val modelRuntime = inferModelRuntime(modelUri)
            val modelFileExtension = extractModelFileExtension(modelUri)
            val metadata = modelArtifactCache.putArtifactFromFile(
                modelVersionId = modelVersionId,
                modelPartitionId = modelPartitionId,
                checksumSha256Hex = checksum,
                sourceFile = downloadedArtifact.tempFile,
                modelRuntime = modelRuntime,
                fileExtension = modelFileExtension,
                actualChecksumSha256Hex = downloadedArtifact.checksumSha256Hex
            )
            taskProcessor.registerModelArtifact(metadata)
            Log.i(
                TAG,
                "LOAD_MODEL completed for partition $modelPartitionId (runtime=$modelRuntime, bytes=${downloadedArtifact.bytesDownloaded})"
            )
            EventLogger.success(EventLogger.Categories.TASK, "Model partition loaded: $modelPartitionId")

            // Foreman stage dispatch is gated on MODEL_LOADED for DNN jobs.
            val workerId = workerIdManager.getCurrentWorkerId() ?: "unknown"
            val ackMessage = MessageProtocol.createModelLoadedMessage(
                workerId = workerId,
                jobId = messageData.jobId,
                modelVersionId = modelVersionId,
                modelPartitionId = modelPartitionId
            )
            sendMessage(ackMessage)
            Log.i(TAG, "MODEL_LOADED ack sent for partition $modelPartitionId")
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "LOAD_MODEL ran out of memory for partition $modelPartitionId", oom)
            EventLogger.error(EventLogger.Categories.TASK, "Model load OOM for $modelPartitionId: ${oom.message}")
            System.gc()
        } catch (e: Exception) {
            Log.e(TAG, "LOAD_MODEL failed for partition $modelPartitionId", e)
            EventLogger.error(EventLogger.Categories.TASK, "Model load failed for $modelPartitionId: ${e.message}")
        } finally {
            tempArtifactPath?.let { path ->
                try {
                    val staleTemp = java.io.File(path)
                    if (staleTemp.exists()) {
                        staleTemp.delete()
                    }
                } catch (_: Exception) {
                    // Best-effort cleanup.
                }
            }
        }
    }

    private fun handleUnloadModel(messageData: MessageProtocol.MessageData) {
        val data = messageData.data
        if (data == null) {
            Log.w(TAG, "UNLOAD_MODEL message had no data")
            return
        }

        val modelPartitionId = data.optString("model_partition_id", "")
        if (modelPartitionId.isBlank()) {
            Log.w(TAG, "UNLOAD_MODEL missing model_partition_id")
            return
        }

        try {
            taskProcessor.unregisterModelArtifact(modelPartitionId)
            val removed = modelArtifactCache.removeArtifact(modelPartitionId)

            if (!removed) {
                Log.i(TAG, "UNLOAD_MODEL partition $modelPartitionId already absent from cache")
            }

            System.gc()
            Log.i(TAG, "UNLOAD_MODEL completed for partition $modelPartitionId")
            EventLogger.info(EventLogger.Categories.TASK, "Model partition unloaded: $modelPartitionId")
        } catch (e: Exception) {
            Log.e(TAG, "UNLOAD_MODEL failed for partition $modelPartitionId", e)
        }
    }

    private fun inferModelRuntime(modelUri: String): String {
        val extension = extractModelFileExtension(modelUri)?.lowercase()
        return when (extension) {
            ".onnx" -> "onnx"
            ".tflite" -> "tflite"
            else -> "unknown"
        }
    }

    private fun extractModelFileExtension(modelUri: String): String? {
        val trimmed = modelUri.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val uriWithoutQuery = trimmed.substringBefore('?').substringBefore('#')
        val fileName = uriWithoutQuery.substringAfterLast('/')
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == fileName.length - 1) {
            return null
        }

        return fileName.substring(dotIndex)
    }

    private fun handleDeviceTopology(messageData: MessageProtocol.MessageData) {
        val data = messageData.data ?: return
        dnnStateStore.onDeviceTopology(data)
        taskProcessor.onDeviceTopology(data)
        Log.i(TAG, "DEVICE_TOPOLOGY applied for graph=${data.optString("inference_graph_id", "unknown")}")
    }

    private fun handleTopologyUpdate(messageData: MessageProtocol.MessageData) {
        val data = messageData.data ?: return
        if (dnnStateStore.isDuplicateTopologyUpdate(data)) {
            Log.d(TAG, "Ignoring duplicate TOPOLOGY_UPDATE")
            return
        }
        taskProcessor.onTopologyUpdate(data)
        Log.i(TAG, "TOPOLOGY_UPDATE applied for graph=${data.optString("inference_graph_id", "unknown")}")
    }

    private fun handleAggregationConfig(messageData: MessageProtocol.MessageData) {
        val data = messageData.data ?: return
        dnnStateStore.onAggregationConfig(data)
        taskProcessor.onAggregationConfig(data)
    }

    private fun handleFallbackDecision(messageData: MessageProtocol.MessageData) {
        val data = messageData.data ?: return
        if (dnnStateStore.isDuplicateFallbackDecision(data)) {
            Log.d(TAG, "Ignoring duplicate FALLBACK_DECISION")
            return
        }
        taskProcessor.onFallbackDecision(data)
        Log.i(TAG, "FALLBACK_DECISION received for task=${data.optString("task_id", "")}, mode=${data.optString("fallback_mode", "")}")
    }

    private fun handleIntermediateFeature(messageData: MessageProtocol.MessageData) {
        val data = messageData.data ?: return
        val sourceTaskId = data.optString("task_id", "")
        val targetTaskId = data.optString("target_task_id", "")
        if (targetTaskId.isBlank()) {
            Log.w(TAG, "INTERMEDIATE_FEATURE missing target_task_id; dropping")
            return
        }

        val payload = data.opt("payload") ?: JSONObject.NULL
        val payloadFormat = data.optString("payload_format", "json")

        val featureEnvelope = JSONObject().apply {
            put("source_task_id", sourceTaskId)
            put("target_task_id", targetTaskId)
            put("payload", payload)
            put("payload_format", payloadFormat)
            put("received_at", System.currentTimeMillis())
        }

        synchronized(pendingIntermediateFeatures) {
            val queue = pendingIntermediateFeatures.getOrPut(targetTaskId) { mutableListOf() }
            queue.add(featureEnvelope)
        }

        Log.i(TAG, "INTERMEDIATE_FEATURE received: source=$sourceTaskId target=$targetTaskId")
    }

    private fun injectPendingIntermediateFeatures(taskData: JSONObject, taskId: String) {
        if (taskId.isBlank()) {
            return
        }

        val pendingFeatures: List<JSONObject> = synchronized(pendingIntermediateFeatures) {
            val queued = pendingIntermediateFeatures.remove(taskId) ?: emptyList()
            queued.map { JSONObject(it.toString()) }
        }

        if (pendingFeatures.isEmpty()) {
            return
        }

        val featuresArray = JSONArray()
        pendingFeatures.forEach { featuresArray.put(it) }
        taskData.put("intermediate_features", featuresArray)

        val originalTaskArgs = taskData.optString("task_args", "")
        taskData.put("task_args", mergeTaskArgsWithIntermediateFeatures(originalTaskArgs, pendingFeatures))

        Log.i(TAG, "Injected ${pendingFeatures.size} pending INTERMEDIATE_FEATURE payload(s) into task $taskId")
    }

    private fun mergeTaskArgsWithIntermediateFeatures(taskArgsRaw: String, features: List<JSONObject>): String {
        val argsArray = parseTaskArgsAsArray(taskArgsRaw)
        for (feature in features) {
            argsArray.put(JSONObject(feature.toString()))
        }
        return argsArray.toString()
    }

    private fun parseTaskArgsAsArray(taskArgsRaw: String): JSONArray {
        val trimmed = taskArgsRaw.trim()
        if (trimmed.isBlank()) {
            return JSONArray()
        }

        return try {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONArray().put(JSONObject(trimmed))
                else -> JSONArray().put(trimmed)
            }
        } catch (_: Exception) {
            JSONArray().put(taskArgsRaw)
        }
    }

    private fun maybeSendIntermediateFeature(jobId: String?, taskId: String, taskResultMessage: String) {
        try {
            val responseJson = JSONObject(taskResultMessage)
            if (responseJson.optString("type", "") != MessageProtocol.MessageType.TASK_RESULT) {
                return
            }

            val data = responseJson.optJSONObject("data") ?: return
            val resultObj = when (val resultValue = data.opt("result")) {
                is JSONObject -> resultValue
                is String -> {
                    val trimmed = resultValue.trim()
                    if (trimmed.startsWith("{")) JSONObject(trimmed) else null
                }
                else -> null
            } ?: return

            val intermediate = resultObj.optJSONObject("intermediate_feature") ?: return
            val payload = intermediate.opt("payload") ?: return
            val payloadFormat = intermediate.optString("payload_format", "json")

            val workerId = workerIdManager.getCurrentWorkerId() ?: "unknown"

            val directTarget = intermediate.optString("target_task_id", "")
            if (directTarget.isNotBlank()) {
                val featureMessage = MessageProtocol.createIntermediateFeatureMessage(
                    jobId = jobId,
                    taskId = taskId,
                    sourceWorkerId = workerId,
                    targetTaskId = directTarget,
                    payload = payload,
                    payloadFormat = payloadFormat
                )
                sendMessage(featureMessage)
                return
            }

            val targetList = intermediate.optJSONArray("target_task_ids") ?: return
            for (i in 0 until targetList.length()) {
                val targetId = targetList.optString(i, "")
                if (targetId.isBlank()) {
                    continue
                }
                val featureMessage = MessageProtocol.createIntermediateFeatureMessage(
                    jobId = jobId,
                    taskId = taskId,
                    sourceWorkerId = workerId,
                    targetTaskId = targetId,
                    payload = payload,
                    payloadFormat = payloadFormat
                )
                sendMessage(featureMessage)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to emit INTERMEDIATE_FEATURE from task result: ${e.message}")
        }
    }
    
    private fun safeGetTaskStatusForHeartbeat(): Map<String, Any> {
        return try {
            taskProcessor.getCurrentTaskStatus()
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM while collecting task status for heartbeat", oom)
            System.gc()
            emptyMap()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to collect task status for heartbeat: ${t.message}")
            emptyMap()
        }
    }

    /**
     * Start heartbeat to keep connection alive
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = clientScope.launch {
            // Wait a bit before sending first heartbeat to ensure connection is stable
            delay(5000)
            
            while (isConnected.get() && isRunning.get()) {
                try {
                    if (isConnected.get()) {
                        val workerId = workerIdManager.getCurrentWorkerId() ?: "unknown"
                        val taskStatus = safeGetTaskStatusForHeartbeat()
                        val currentTaskId = taskStatus["current_task_id"] as? String
                        val currentJobId = taskStatus["current_job_id"] as? String
                        
                        val heartbeatMessage = MessageProtocol.createHeartbeatMessage(
                            workerId,
                            currentTaskId,
                            currentJobId,
                            context
                        )
                        
                        sendMessage(heartbeatMessage)
                        val taskInfo = if (currentTaskId.isNullOrEmpty()) "No task" else currentTaskId
                        Log.d(TAG, "Heartbeat sent - Worker: $workerId, Task: $taskInfo")
                    }
                } catch (oom: OutOfMemoryError) {
                    Log.e(TAG, "OOM while preparing heartbeat", oom)
                    System.gc()
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending heartbeat", e)
                }
                
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }
    
    /**
     * Stop heartbeat
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    /**
     * Schedule reconnection attempt
     */
    private fun scheduleReconnection(url: String) {
        if (reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "🚨 Max reconnection attempts reached, giving up")
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = clientScope.launch {
            val attempt = reconnectAttempts.incrementAndGet()
            val delay = RECONNECT_DELAY * attempt // Exponential backoff
            
            Log.d(TAG, "Scheduling reconnection attempt $attempt in ${delay}ms")
            delay(delay)
            
            if (isRunning.get() && !isConnected.get()) {
                Log.d(TAG, "Attempting reconnection #$attempt")
                connect(url)
            }
        }
    }
    
    /**
     * Test WebSocket functionality
     */
    suspend fun testWebSocket(): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Testing WebSocket functionality...")
                
                // Test task processor
                val processorTest = taskProcessor.testTaskProcessing()
                
                // Test status request
                val statusMessage = """
                    {
                        "type": "get_status",
                        "data": {}
                    }
                """.trimIndent()
                
                val statusResponse = taskProcessor.processTaskMessage(statusMessage)
                val statusJson = org.json.JSONObject(statusResponse ?: "{}")
                
                val isSuccess = processorTest["status"] == "success" && 
                               statusJson.optString("status") == "ready"
                
                mapOf<String, Any>(
                    "status" to if (isSuccess) "success" else "error",
                    "message" to if (isSuccess) "WebSocket test passed" else "WebSocket test failed",
                    "processor_test" to processorTest,
                    "status_response" to statusJson.toString(),
                    "is_connected" to isConnected.get(),
                    "is_running" to isRunning.get()
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket test failed", e)
                mapOf<String, Any>(
                    "status" to "error",
                    "message" to "WebSocket test failed: ${e.message ?: "Unknown error"}",
                    "error" to e.toString()
                )
            }
        }
    }
    
    /**
     * Get connection status
     */
    fun getConnectionStatus(): Map<String, Any> {
        return mapOf<String, Any>(
            "is_connected" to isConnected.get(),
            "is_running" to isRunning.get(),
            "reconnect_attempts" to reconnectAttempts.get(),
            "worker_id" to (workerIdManager.getCurrentWorkerId() ?: "unknown"),
            "heartbeat_active" to (heartbeatJob?.isActive ?: false),
            "last_heartbeat" to System.currentTimeMillis()
        )
    }
    
    /**
     * Send immediate heartbeat for testing
     */
    fun sendImmediateHeartbeat() {
        if (isConnected.get()) {
            val workerId = workerIdManager.getCurrentWorkerId() ?: "unknown"
            val taskStatus = safeGetTaskStatusForHeartbeat()
            val currentTaskId = taskStatus["current_task_id"] as? String
            val currentJobId = taskStatus["current_job_id"] as? String
            
            val heartbeatMessage = MessageProtocol.createHeartbeatMessage(workerId, currentTaskId, currentJobId, context)
            sendMessage(heartbeatMessage)
            val taskInfo = if (currentTaskId.isNullOrEmpty()) "No task" else currentTaskId
            Log.d(TAG, "Immediate heartbeat sent - Worker: $workerId, Task: $taskInfo")
        } else {
            Log.w(TAG, "Cannot send heartbeat - not connected")
        }
    }
    
    /**
     * Add listener for WebSocket events
     */
    fun addListener(listener: WorkerWebSocketListener) {
        listeners.add(listener)
    }
    
    /**
     * Remove listener
     */
    fun removeListener(listener: WorkerWebSocketListener) {
        listeners.remove(listener)
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            Log.d(TAG, "Cleaning up WebSocket client...")
            disconnect()
            clientScope.cancel()
            taskProcessor.cleanup()
            listeners.clear()
            Log.d(TAG, "WebSocket client cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    /**
     * Listener interface for WebSocket events
     */
    interface WorkerWebSocketListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: Exception?)
    }
}
