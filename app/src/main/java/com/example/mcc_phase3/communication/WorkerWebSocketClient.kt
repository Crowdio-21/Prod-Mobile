package com.example.mcc_phase3.communication

import android.content.Context
import android.util.Log
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
import com.example.mcc_phase3.checkpoint.CheckpointStateHolder
import com.example.mcc_phase3.data.ConfigManager
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.execution.TaskExecutionResult
import com.example.mcc_phase3.execution.TaskExecutor
import com.example.mcc_phase3.utils.EventLogger
import com.example.mcc_phase3.utils.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class WorkerWebSocketClient(
    private val context: Context,
    private val taskExecutor: TaskExecutor



) {

    companion object {
        private const val TAG = "WorkerWebSocketClient"
        private const val HEARTBEAT_INTERVAL = 30_000L
        private const val MAX_OUTBOUND_MESSAGE_BYTES = 4 * 1024 * 1024
        private const val DISCONNECT_NOTIFY_COOLDOWN_MS = 30_000L
        private const val DEFAULT_RESULT_UPLOAD_PORT = 8001
        private const val RESULT_UPLOAD_PATH = "/upload"
    }

    private data class UploadOutcome(
        val fileUrl: String?,
        val error: String?
    )

    private val workerIdManager = WorkerIdManager.getInstance(context)
    private val dnnStateStore = DnnStateStore(context)
    private val modelArtifactCache = ModelArtifactCache(context)
    private val modelDownloadClient = ModelDownloadClient()
    private val outboundQueueStore = OutboundMessageQueueStore(context)
    private val localCheckpointStore = LocalCheckpointStore(context)
    private val pendingIntermediateFeatures = ConcurrentHashMap<String, MutableList<JSONObject>>()
    
    private var webSocket: WebSocketClient? = null
    private val configManager = ConfigManager.getInstance(context)
    private val checkpointStateHolder = CheckpointStateHolder()
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val currentUrl = AtomicReference<String?>(null)
    private val webSocketRef = AtomicReference<WebSocket?>(null)
    private val pendingConnect = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private val lastDisconnectNotificationAt = AtomicLong(0L)
    private val listeners = mutableSetOf<WorkerWebSocketListener>()

    private val resultUploadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val webSocketClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val reconnectionManager = ReconnectionManager(clientScope) {
        attemptConnect(currentUrl.get())
    }

    private var heartbeatJob: Job? = null

    suspend fun connect(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                currentUrl.set(url)

                if (!taskExecutor.initialize()) {
                    Log.e(TAG, "Failed to initialize task executor")
                    return@withContext false
                }

                if (isConnected.get() && currentUrl.get() == url) {
                    return@withContext true
                }

                isRunning.set(true)
                reconnectionManager.start()

                val deferred = CompletableDeferred<Boolean>()
                pendingConnect.getAndSet(deferred)?.cancel()
                attemptConnect(url)

                withTimeoutOrNull(30_000L) { deferred.await() } ?: false
            } catch (e: CancellationException) {
                Log.i(TAG, "WebSocket connect coroutine cancelled: ${e.message}")
                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to foreman", e)
                false
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting WebSocket client")
        isRunning.set(false)
        reconnectionManager.stop()
        stopHeartbeat()
        pendingConnect.getAndSet(null)?.cancel()
        webSocketRef.getAndSet(null)?.close(1000, "Client disconnect")
        isConnected.set(false)
        isConnecting.set(false)
    }

    private suspend fun attemptConnect(url: String?) {
        if (url.isNullOrBlank() || !isRunning.get()) {
            return
        }

        if (!isConnecting.compareAndSet(false, true)) {
            return
        }

        try {
            Log.d(TAG, "Connecting to foreman: $url")
            val request = Request.Builder().url(url).build()
            val webSocket = webSocketClient.newWebSocket(request, createListener())
            webSocketRef.set(webSocket)
        } catch (e: Exception) {
            isConnecting.set(false)
            completePendingConnect(false)
            reconnectionManager.onDisconnected()
            throw e
        }
    }

    private fun createListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocketRef.get() !== webSocket) {
                    webSocket.close(1000, "Superseded connection")
                    return
                }

                isConnecting.set(false)
                isConnected.set(true)
                reconnectionManager.onConnected()
                completePendingConnect(true)

                Log.d(TAG, "WebSocket connected to ${currentUrl.get()}")
                EventLogger.success(EventLogger.Categories.WEBSOCKET, "Connected to foreman: ${currentUrl.get()}")
                NotificationHelper.notifyWorkerConnected(context)

                startHeartbeat()
                sendWorkerReady()
                listeners.forEach { it.onConnected() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val preview = if (text.length > 512) "${text.take(512)}..." else text
                Log.d(TAG, "Received message (${text.length} chars): $preview")
                clientScope.launch {
                    handleMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleSocketClosed(webSocket, code, reason, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleSocketClosed(webSocket, response?.code ?: -1, t.message, t)
                listeners.forEach { it.onError(Exception(t)) }
            }
        }
    }

    private fun handleSocketClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String?,
        error: Throwable?
    ) {
        if (webSocketRef.get() !== webSocket) {
            return
        }

        webSocketRef.compareAndSet(webSocket, null)
        val wasConnected = isConnected.getAndSet(false)
        isConnecting.set(false)
        stopHeartbeat()

        val message = reason ?: error?.message
        Log.w(TAG, "WebSocket disconnected (code=$code, reason=$message)")
        EventLogger.warning(
            EventLogger.Categories.WEBSOCKET,
            "Disconnected from foreman (code=$code, reason=${message ?: "unknown"})"
        )

        maybeNotifyDisconnected(wasConnected, message)
        completePendingConnect(false)
        listeners.forEach { it.onDisconnected() }

        if (isRunning.get()) {
            reconnectionManager.onDisconnected()
        }
    }

    private fun completePendingConnect(success: Boolean) {
        pendingConnect.getAndSet(null)?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(success)
            }
        }
    }

    private fun sendWorkerReady() {
        val workerId = workerIdManager.getOrGenerateWorkerId()
        val deviceSpecs = DeviceInfoCollector(context).getDeviceSpecs()

        val message = JSONObject().apply {
            put("type", MessageType.WORKER_READY)
            put("timestamp", System.currentTimeMillis())
            put("data", JSONObject().apply {
                put("worker_id", workerId)
                put("worker_type", "android_kotlin")
                put("platform", "android")
                put("runtime", "jvm")
                put("capabilities", JSONObject().apply {
                    put("supports_settrace", false)
                    put("supports_frame_introspection", false)
                })
                put("device_specs", JSONObject(deviceSpecs.toMap()))
            })
        }.toString()

        sendMessage(message)
    }

    private suspend fun handleMessage(rawMessage: String) {
        val inboundMessage = Protocol.parseInboundMessage(rawMessage)
        if (inboundMessage == null) {
            Log.w(TAG, "Failed to parse inbound message")
            return
        }

        when (inboundMessage.type) {
            MessageType.ASSIGN_TASK -> handleAssignTask(inboundMessage)
            MessageType.RESUME_TASK -> handleResumeTask(inboundMessage)
            MessageType.PING -> handlePing()
            MessageType.CHECKPOINT_ACK -> handleCheckpointAck(inboundMessage)
            else -> Log.w(TAG, "Unhandled message type: ${inboundMessage.type}")
        }
    }

    private suspend fun handleAssignTask(message: InboundMessage) {
        val payload = message.data
        if (payload == null) {
            Log.w(TAG, "assign_task missing data payload")
            return
        }

        val taskId = payload.optString("task_id")
        if (taskId.isBlank()) {
            Log.w(TAG, "assign_task missing task_id")
            return
        }

        taskExecutor.executeTask(
            taskId = taskId,
            jobId = message.jobId,
            payload = payload,
            onResult = { result ->
                sendTaskResult(taskId, message.jobId, result)
            },
            onError = { error ->
                sendTaskError(taskId, message.jobId, error)
            },
            onCheckpoint = { checkpoint ->
                sendCheckpoint(checkpoint)
            }
        )
    }

    private suspend fun handleResumeTask(message: InboundMessage) {
        val payload = try {
            ResumeTaskPayload.from(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse resume_task payload", e)
            sendTaskError(
                taskId = message.data?.optString("task_id") ?: "unknown",
                jobId = message.jobId,
                error = "Invalid resume payload: ${e.message ?: "unknown error"}"
            )
            return
        }

        checkpointStateHolder.update(
            checkpointId = payload.checkpointId,
            taskId = payload.taskId,
            jobId = payload.jobId,
            deltaDataHex = payload.deltaDataHex,
            progressPercent = payload.progressPercent,
            stateVars = payload.stateVars
        )

        taskExecutor.resumeFromCheckpoint(
            taskId = payload.taskId,
            jobId = payload.jobId,
            checkpointId = payload.checkpointId,
            deltaDataHex = payload.deltaDataHex,
            stateVars = payload.stateVars,
            onResult = { result ->
                sendTaskResult(payload.taskId, payload.jobId, result)
            },
            onError = { error ->
                sendTaskError(payload.taskId, payload.jobId, error)
            },
            onCheckpoint = { checkpoint ->
                sendCheckpoint(checkpoint)
            }
        )
    }

    private fun handlePing() {
        val workerId = workerIdManager.getOrGenerateWorkerId()
        val metrics = DeviceInfoCollector(context).getPerformanceMetrics().toMap()
        val pong = JSONObject().apply {
            put("type", MessageType.PONG)
            put("timestamp", System.currentTimeMillis())
            put("data", JSONObject().apply {
                put("worker_id", workerId)
                put("status", "online")
                metrics.forEach { (key, value) -> put(key, value) }
            })
        }.toString()
        sendMessage(pong)
    }

    private fun handleCheckpointAck(message: InboundMessage) {
        val taskId = message.data?.optString("task_id").orEmpty()
        val checkpointId = message.data?.optString("checkpoint_id").orEmpty()
        Log.d(TAG, "Checkpoint $checkpointId acknowledged for task $taskId")
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = clientScope.launch {
            delay(5_000L)
            while (isRunning.get() && isConnected.get()) {
                try {
                    sendHeartbeatMessage()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send heartbeat", e)
                }

                delay(HEARTBEAT_INTERVAL)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendHeartbeatMessage() {
        val workerId = workerIdManager.getOrGenerateWorkerId()
        val taskStatus = taskExecutor.getCurrentTaskStatus()
        val heartbeat = JSONObject().apply {
            put("type", MessageType.WORKER_HEARTBEAT)
            put("job_id", taskStatus["current_job_id"] as? String)
            put("timestamp", System.currentTimeMillis())
            put("data", JSONObject().apply {
                put("worker_id", workerId)
                put("status", if (taskStatus["is_busy"] == true) "busy" else "online")
                put("current_task", taskStatus["current_task_id"] as? String)
                put("progress_percent", normalizeProgressValue(taskStatus["progress_percent"]))
            })
        }.toString()

        sendMessage(heartbeat)
    }

    private fun sendTaskResult(taskId: String, jobId: String?, result: TaskExecutionResult): Boolean {
        val payload = JSONObject().apply {
            put("type", MessageType.TASK_RESULT)
            put("job_id", jobId)
            put("timestamp", System.currentTimeMillis())
            put("data", JSONObject().apply {
                put("task_id", taskId)
                put("result", normalizeResultValue(result.result))
                put("execution_time", result.executionTime)
                result.recoveryStatus?.let { put("recovery_status", it) }
            })
        }.toString()

        val sent = sendTaskResponseWithSizeGuard(taskId, jobId, payload)
        if (sent) {
            checkpointStateHolder.clear()
        }
        return sent
    }

    private fun sendTaskError(taskId: String, jobId: String?, error: String): Boolean {
        val payload = JSONObject().apply {
            put("type", MessageType.TASK_ERROR)
            put("job_id", jobId)
            put("timestamp", System.currentTimeMillis())
            put("data", JSONObject().apply {
                put("task_id", taskId)
                put("error", error)
            })
        }.toString()

        val sent = sendMessage(payload)
        if (sent) {
            checkpointStateHolder.clear()
        }
        return sent
    }

    private suspend fun sendCheckpoint(checkpoint: CheckpointMessage): Boolean {
        val decodedState = ProtocolCodec.decodeCheckpointState(checkpoint.deltaDataHex)
        checkpointStateHolder.update(
            checkpointId = checkpoint.checkpointId.toString(),
            taskId = checkpoint.taskId,
            jobId = checkpoint.jobId,
            deltaDataHex = checkpoint.deltaDataHex,
            progressPercent = checkpoint.progressPercent,
            stateVars = decodedState?.let { ProtocolCodec.jsonObjectToMap(it) }.orEmpty()
        )

        val sent = sendMessage(checkpoint.toJsonString())
        if (!sent) {
            Log.w(TAG, "Checkpoint for task ${checkpoint.taskId} could not be sent because the socket is unavailable")
        }
        return sent
    }

    private fun sendTaskResponseWithSizeGuard(taskId: String, jobId: String?, response: String): Boolean {
        val responseSize = response.toByteArray(Charsets.UTF_8).size
        if (responseSize <= MAX_OUTBOUND_MESSAGE_BYTES) {
            return sendMessage(response)
        }

        Log.w(TAG, "Task response too large (${responseSize}B), uploading over HTTP")
        val upload = uploadOversizedTaskResponse(taskId, response)
        if (!upload.fileUrl.isNullOrBlank()) {
            val offloadedMessage = createOffloadedTaskResultMessage(taskId, jobId, upload.fileUrl, responseSize)
            return sendMessage(offloadedMessage)
        }

        Log.e(TAG, "Failed to offload oversized task result: ${upload.error ?: "unknown upload error"}")
        return sendTaskError(
            taskId = taskId,
            jobId = jobId,
            error = "Task result too large for transport (${responseSize} bytes) and upload failed: ${upload.error ?: "unknown upload error"}"
        )
    }

    fun sendImmediateHeartbeat() {
        if (!isConnected.get()) {
            Log.w(TAG, "Cannot send heartbeat because the socket is not connected")
            return
        }

        clientScope.launch {
            try {
                sendHeartbeatMessage()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send immediate heartbeat", e)
            }
        }
    }

    fun getConnectionStatus(): Map<String, Any> {
        return mapOf(
            "is_connected" to isConnected.get(),
            "is_connecting" to isConnecting.get(),
            "is_running" to isRunning.get(),
            "worker_id" to workerIdManager.getOrGenerateWorkerId(),
            "current_url" to (currentUrl.get() ?: ""),
            "checkpoint_cached" to (checkpointStateHolder.get() != null),
            "heartbeat_active" to (heartbeatJob?.isActive == true)
        )
    }

    suspend fun testWebSocket(): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            mapOf(
                "status" to if (isConnected.get()) "success" else "error",
                "message" to if (isConnected.get()) "WebSocket connected" else "WebSocket disconnected",
                "is_connected" to isConnected.get(),
                "is_running" to isRunning.get(),
                "worker_id" to workerIdManager.getOrGenerateWorkerId(),
                "checkpoint_cached" to (checkpointStateHolder.get() != null)
            )
        }
    }

    private fun sendMessage(message: String): Boolean {
        val webSocket = webSocketRef.get()
        if (!isConnected.get() || webSocket == null) {
            Log.w(TAG, "Cannot send message because the socket is not connected")
            return false
        }

        val sizeBytes = message.toByteArray(Charsets.UTF_8).size
        if (sizeBytes > MAX_OUTBOUND_MESSAGE_BYTES) {
            Log.e(TAG, "Refusing to send oversized WebSocket message (${sizeBytes}B)")
            return false
        }

        return try {
            webSocket.send(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WebSocket message", e)
            false
        }
    }

    private fun normalizeResultValue(result: Any?): Any {
        return when (result) {
            null -> JSONObject()
            is JSONObject, is JSONArray, is Number, is Boolean -> result
            is Map<*, *> -> ProtocolCodec.mapToJsonObject(
                result.entries
                    .filter { it.key is String }
                    .associate { it.key as String to it.value }
            )
            is String -> {
                val trimmed = result.trim()
                try {
                    when {
                        trimmed.startsWith("{") -> JSONObject(trimmed)
                        trimmed.startsWith("[") -> JSONArray(trimmed)
                        trimmed.isBlank() -> JSONObject()
                        else -> result
                    }
                } catch (_: Exception) {
                    result
                }
            }
            else -> result.toString()
        }
    }

    private fun normalizeProgressValue(value: Any?): Float {
        return when (value) {
            is Float -> value
            is Double -> value.toFloat()
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull() ?: 0f
            else -> 0f
        }
    }

    private fun uploadOversizedTaskResponse(taskId: String, response: String): UploadOutcome {
        val foremanIp = configManager.getForemanIP().trim()
        if (foremanIp.isBlank()) {
            return UploadOutcome(fileUrl = null, error = "Foreman IP is not configured")
        }

        val uploadUrl = "http://$foremanIp:$DEFAULT_RESULT_UPLOAD_PORT$RESULT_UPLOAD_PATH"
        val tempFile = File(context.cacheDir, "task_result_${System.currentTimeMillis()}_${taskId}.json")

        return try {
            tempFile.writeText(response, Charsets.UTF_8)

            val filePart = tempFile.asRequestBody("application/json".toMediaTypeOrNull())
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", tempFile.name, filePart)
                .build()

            val request = Request.Builder()
                .url(uploadUrl)
                .post(multipartBody)
                .build()

            resultUploadClient.newCall(request).execute().use { responseHttp ->
                if (!responseHttp.isSuccessful) {
                    return UploadOutcome(
                        fileUrl = null,
                        error = "POST $uploadUrl -> HTTP ${responseHttp.code} (${responseHttp.message})"
                    )
                }

                val body = responseHttp.body?.string().orEmpty()
                if (body.isBlank()) {
                    return UploadOutcome(fileUrl = null, error = "POST $uploadUrl -> empty response body")
                }

                val json = JSONObject(body)
                val fileUrl = json.optString("file_url", "").trim()
                if (fileUrl.isBlank()) {
                    UploadOutcome(
                        fileUrl = null,
                        error = "POST $uploadUrl -> response missing file_url (body=${body.take(300)})"
                    )
                } else {
                    UploadOutcome(fileUrl = fileUrl, error = null)
                }
            }
        } catch (e: Exception) {
            UploadOutcome(
                fileUrl = null,
                error = "POST $uploadUrl -> ${e.javaClass.simpleName}: ${e.message ?: "unknown exception"}"
            )
        } finally {
            tempFile.delete()
        }
    }

    private fun createOffloadedTaskResultMessage(
        taskId: String,
        jobId: String?,
        fileUrl: String,
        originalBytes: Int
    ): String {
        return JSONObject().apply {
            put("type", MessageType.TASK_RESULT)
            put("job_id", jobId)
            put("timestamp", System.currentTimeMillis())
            put("data", JSONObject().apply {
                put("task_id", taskId)
                put("execution_time", 0.0)
                put(
                    "result",
                    JSONObject().apply {
                        put("transport", "http_url")
                        put("result_file_url", fileUrl)
                        put("result_offloaded", true)
                        put("original_result_bytes", originalBytes)
                    }
                )
            })
        }.toString()
    }

    private fun maybeNotifyDisconnected(wasConnected: Boolean, reason: String?) {
        if (!wasConnected) {
            return
        }

        val now = System.currentTimeMillis()
        val previous = lastDisconnectNotificationAt.get()
        if (now - previous < DISCONNECT_NOTIFY_COOLDOWN_MS) {
            return
        }

        if (lastDisconnectNotificationAt.compareAndSet(previous, now)) {
            NotificationHelper.notifyWorkerDisconnected(context, reason)
        }
    }

    fun addListener(listener: WorkerWebSocketListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: WorkerWebSocketListener) {
        listeners.remove(listener)
    }

    fun cleanup() {
        try {
            disconnect()
            clientScope.cancel()
            taskExecutor.cleanup()
            listeners.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    interface WorkerWebSocketListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: Exception?)
    }
}
