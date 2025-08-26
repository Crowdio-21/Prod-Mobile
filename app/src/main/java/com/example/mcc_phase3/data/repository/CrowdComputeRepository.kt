package com.example.mcc_phase3.data.repository

import android.util.Log
import com.example.mcc_phase3.data.api.ApiClient
import com.example.mcc_phase3.data.api.ApiService
import com.example.mcc_phase3.data.models.*
import com.example.mcc_phase3.data.websocket.WebSocketManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import com.example.mcc_phase3.data.websocket.WebSocketManager.WebSocketListener

class CrowdComputeRepository(private val context: android.content.Context) {
    private val apiService: ApiService = ApiClient.getApiService(context)
    private val webSocketManager = WebSocketManager.getInstance() // ✅ Use singleton instance

    companion object {
        private const val TAG = "CrowdComputeRepository"
    }

    init {
        Log.d(TAG, "=== CrowdComputeRepository Initialized ===")
        Log.d(TAG, "ApiService: ${apiService.javaClass.simpleName}")
        Log.d(TAG, "WebSocketManager: ${webSocketManager.javaClass.simpleName}")
        Log.d(TAG, "Base URL: ${ApiClient.getBaseUrl(context)}")
    }

    suspend fun getStats(): Result<Stats> = withContext(Dispatchers.IO) {
        Log.d(TAG, "📊 getStats() called")
        ApiClient.logApiCall(context, "/api/stats")

        try {
            val startTime = System.currentTimeMillis()
            val stats = apiService.getStats()
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ getStats() successful in ${duration}ms")
            Log.d(TAG, "📊 Stats data: totalJobs=${stats.totalJobs}, totalTasks=${stats.totalTasks}, totalWorkers=${stats.totalWorkers}")
            ApiClient.logApiSuccess("/api/stats", 200)

            Result.success(stats)
        } catch (e: Exception) {
            Log.e(TAG, "❌ getStats() failed", e)
            ApiClient.logApiError(context, "/api/stats", e)
            Result.failure(e)
        }
    }

    suspend fun getJobs(skip: Int = 0, limit: Int = 100): Result<List<Job>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "💼 getJobs() called with skip=$skip, limit=$limit")
        ApiClient.logApiCall(context, "/api/jobs?skip=$skip&limit=$limit")

        try {
            val startTime = System.currentTimeMillis()
            val jobs = apiService.getJobs(skip, limit)
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ getJobs() successful in ${duration}ms")
            Log.d(TAG, "💼 Retrieved ${jobs.size} jobs")
            jobs.forEachIndexed { index, job ->
                Log.v(TAG, "💼 Job[$index]: id=${job.id}, status=${job.status}, progress=${job.completedTasks}/${job.totalTasks}")
            }
            ApiClient.logApiSuccess("/api/jobs", 200, jobs.size)

            Result.success(jobs)
        } catch (e: Exception) {
            Log.e(TAG, "❌ getJobs() failed", e)
            ApiClient.logApiError(context, "/api/jobs", e)
            Result.failure(e)
        }
    }

    suspend fun getJob(jobId: String): Result<Job> = withContext(Dispatchers.IO) {
        Log.d(TAG, "💼 getJob() called for jobId=$jobId")
        ApiClient.logApiCall(context, "/api/jobs/$jobId")

        try {
            val startTime = System.currentTimeMillis()
            val job = apiService.getJob(jobId)
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ getJob() successful in ${duration}ms")
            Log.d(TAG, "💼 Job details: id=${job.id}, status=${job.status}, progress=${job.completedTasks}/${job.totalTasks}")
            ApiClient.logApiSuccess("/api/jobs/$jobId", 200)

            Result.success(job)
        } catch (e: Exception) {
            Log.e(TAG, "❌ getJob($jobId) failed", e)
            ApiClient.logApiError(context, "/api/jobs/$jobId", e)
            Result.failure(e)
        }
    }

    suspend fun getWorkers(): Result<List<Worker>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "👷 getWorkers() called")
        ApiClient.logApiCall(context, "/api/workers")

        try {
            val startTime = System.currentTimeMillis()
            val workers = apiService.getWorkers()
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ getWorkers() successful in ${duration}ms")
            Log.d(TAG, "👷 Retrieved ${workers.size} workers")

            val onlineCount = workers.count { it.status == "online" }
            val offlineCount = workers.size - onlineCount
            Log.d(TAG, "👷 Worker status: $onlineCount online, $offlineCount offline")

            workers.forEachIndexed { index, worker ->
                Log.v(TAG, "👷 Worker[$index]: id=${worker.id}, status=${worker.status}, task=${worker.currentTaskId}")
            }
            ApiClient.logApiSuccess("/api/workers", 200, workers.size)

            Result.success(workers)
        } catch (e: Exception) {
            Log.e(TAG, "❌ getWorkers() failed", e)
            ApiClient.logApiError(context, "/api/workers", e)
            Result.failure(e)
        }
    }

    suspend fun getWebsocketStats(): Result<WebsocketStats> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔌 getWebsocketStats() called")
        ApiClient.logApiCall(context, "/api/websocket-stats")

        try {
            val startTime = System.currentTimeMillis()
            val stats = apiService.getWebsocketStats()
            val duration = System.currentTimeMillis() - startTime

            Log.d(TAG, "✅ getWebsocketStats() successful in ${duration}ms")
            Log.d(TAG, "🔌 WebSocket stats: connected=${stats.connectedWorkers}, available=${stats.availableWorkers}")
            ApiClient.logApiSuccess("/api/websocket-stats", 200)

            Result.success(stats)
        } catch (e: Exception) {
            Log.e(TAG, "❌ getWebsocketStats() failed", e)
            ApiClient.logApiError(context, "/api/websocket-stats", e)
            Result.failure(e)
        }
    }

    fun connectWebSocket(url: String) {
        Log.d(TAG, "🔌 connectWebSocket() called with URL: $url")
        webSocketManager.connect(url)
    }

    fun disconnectWebSocket() {
        Log.d(TAG, "🔌 disconnectWebSocket() called")
        webSocketManager.disconnect()
    }

    fun addWebSocketListener(listener: WebSocketListener) {
        Log.d(TAG, "👂 addWebSocketListener() called: ${listener.javaClass.simpleName}")
        webSocketManager.addListener(listener)
    }

    fun removeWebSocketListener(listener: WebSocketListener) {
        Log.d(TAG, "👂 removeWebSocketListener() called: ${listener.javaClass.simpleName}")
        webSocketManager.removeListener(listener)
    }

    fun isWebSocketConnected(): Boolean {
        val connected = webSocketManager.isConnected()
        Log.v(TAG, "🔌 isWebSocketConnected() returning: $connected")
        return connected
    }

    fun sendWebSocketMessage(message: String) {
        Log.d(TAG, "📤 sendWebSocketMessage() called (${message.length} chars)")
        webSocketManager.sendMessage(message)
    }

    fun getConnectionSummary(): String {
        val wsConnected = webSocketManager.isConnected()
        val summary = "Repository - API: ${ApiClient.getBaseUrl(context)}, WebSocket: ${if (wsConnected) "Connected" else "Disconnected"}"
        Log.d(TAG, "📋 getConnectionSummary(): $summary")
        return summary
    }
}