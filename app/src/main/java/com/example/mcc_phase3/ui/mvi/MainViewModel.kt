package com.example.mcc_phase3.ui.mvi

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mcc_phase3.data.models.*
import com.example.mcc_phase3.data.repository.CrowdComputeRepository
import com.example.mcc_phase3.data.websocket.WebSocketManager
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val repository = CrowdComputeRepository()

    private val _state = MutableLiveData<MainState>(MainState.Loading)
    val state: LiveData<MainState> = _state

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "MainViewModel"
    }

    // ✅ FIXED: Only implement methods that exist in WebSocketManager.WebSocketListener interface
    private val webSocketListener = object : WebSocketManager.WebSocketListener {
        override fun onConnected() {
            Log.d(TAG, "🔌 WebSocket connected")
            updateWebSocketStatus(true)
        }

        override fun onMessage(message: String) {
            Log.d(TAG, "📨 WebSocket message received: $message")
            // Parse message to see if it's an error or rejection
            if (message.contains("error") || message.contains("reject") || message.contains("invalid")) {
                Log.e(TAG, "🚨 SERVER ERROR/REJECTION: $message")
            }
            mainHandler.post { loadData() }
        }

        override fun onDisconnected() {
            Log.w(TAG, "🔌 WebSocket disconnected")
            updateWebSocketStatus(false)
        }

        override fun onError(error: Exception?) {
            Log.e(TAG, "❌ WebSocket error", error)
            updateWebSocketStatus(false)
        }
    }

    init {
        Log.d(TAG, "=== MainViewModel Initialized ===")
        repository.addWebSocketListener(webSocketListener)
        loadData()
    }

    fun handleEvent(event: MainEvent) {
        Log.d(TAG, "🎯 handleEvent(): ${event.javaClass.simpleName}")
        when (event) {
            is MainEvent.LoadData -> loadData()
            is MainEvent.RefreshData -> refreshData()
            is MainEvent.ConnectWebSocket -> connectWebSocket()
            is MainEvent.DisconnectWebSocket -> disconnectWebSocket()
            is MainEvent.LoadJobDetails -> loadJobDetails(event.jobId)
            is MainEvent.LoadWorkerDetails -> loadWorkerDetails(event.workerId)
        }
    }

    private fun loadData() {
        Log.d(TAG, "📊 loadData() called")
        viewModelScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                _state.value = MainState.Loading

                // ✅ Sequential data fetching with detailed logging
                Log.d(TAG, "📊 Fetching stats...")
                val stats = repository.getStats()
                Log.d(TAG, "📊 Stats result: ${if (stats.isSuccess) "✅ Success" else "❌ Failed - ${stats.exceptionOrNull()?.message}"}")

                Log.d(TAG, "📊 Fetching jobs...")
                val jobs = repository.getJobs()
                Log.d(TAG, "📊 Jobs result: ${if (jobs.isSuccess) "✅ Success" else "❌ Failed - ${jobs.exceptionOrNull()?.message}"}")

                Log.d(TAG, "📊 Fetching workers...")
                val workers = repository.getWorkers()
                Log.d(TAG, "📊 Workers result: ${if (workers.isSuccess) "✅ Success" else "❌ Failed - ${workers.exceptionOrNull()?.message}"}")

                Log.d(TAG, "📊 Fetching websocket stats...")
                val websocketStats = repository.getWebsocketStats()
                Log.d(TAG, "📊 WebSocket stats result: ${if (websocketStats.isSuccess) "✅ Success" else "❌ Failed - ${websocketStats.exceptionOrNull()?.message}"}")

                // Core data requirement: stats, jobs, and workers must succeed
                val success = stats.isSuccess && jobs.isSuccess && workers.isSuccess

                if (success) {
                    val wsConnected = repository.isWebSocketConnected()
                    val successState = MainState.Success(
                        stats = stats.getOrNull(),
                        jobs = jobs.getOrNull() ?: emptyList(),
                        workers = workers.getOrNull() ?: emptyList(),
                        websocketStats = websocketStats.getOrNull(),
                        isWebSocketConnected = wsConnected
                    )
                    _state.value = successState

                    Log.d(TAG, "✅ Data loaded successfully:")
                    Log.d(TAG, "   - Jobs: ${successState.jobs.size}")
                    Log.d(TAG, "   - Workers: ${successState.workers.size}")
                    Log.d(TAG, "   - WebSocket connected: $wsConnected")
                } else {
                    val errorMsg = buildString {
                        if (!stats.isSuccess) append("Stats: ${stats.exceptionOrNull()?.message} ")
                        if (!jobs.isSuccess) append("Jobs: ${jobs.exceptionOrNull()?.message} ")
                        if (!workers.isSuccess) append("Workers: ${workers.exceptionOrNull()?.message} ")
                    }
                    val finalErrorMsg = errorMsg.trim().ifEmpty { "Unknown error occurred" }
                    _state.value = MainState.Error(finalErrorMsg)
                    Log.e(TAG, "❌ Data load failed: $finalErrorMsg")
                }

                val totalTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "📊 loadData() completed in ${totalTime}ms")

            } catch (e: Exception) {
                Log.e(TAG, "💥 loadData() exception", e)
                _state.value = MainState.Error("Failed to load data: ${e.message}")
            }
        }
    }

    private fun refreshData() {
        Log.d(TAG, "🔄 refreshData() called")
        loadData()
    }

    private fun connectWebSocket() {
        Log.d(TAG, "🔌 connectWebSocket() called")
        val wsUrl = "ws://192.168.8.120:9000" // TODO: make configurable
        Log.d(TAG, "🔌 Connecting to: $wsUrl")
        repository.connectWebSocket(wsUrl)
    }

    private fun disconnectWebSocket() {
        Log.d(TAG, "🔌 disconnectWebSocket() called")
        repository.disconnectWebSocket()
    }

    private fun loadJobDetails(jobId: String) {
        Log.d(TAG, "💼 loadJobDetails() for jobId: $jobId")
        viewModelScope.launch {
            try {
                val result = repository.getJob(jobId)
                if (result.isSuccess) {
                    val job = result.getOrNull()
                    Log.d(TAG, "💼 Job details loaded: ${job?.id}")
                    // TODO: Handle job details (navigate to detail screen, show dialog, etc.)
                } else {
                    Log.e(TAG, "💼 Failed to load job: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "💼 Exception loading job details", e)
            }
        }
    }

    private fun loadWorkerDetails(workerId: String) {
        Log.d(TAG, "👷 loadWorkerDetails() for workerId: $workerId")
        viewModelScope.launch {
            try {
                val workersResult = repository.getWorkers()
                if (workersResult.isSuccess) {
                    val worker = workersResult.getOrNull()?.find { it.id == workerId }
                    if (worker != null) {
                        Log.d(TAG, "👷 Worker found: ${worker.id} - ${worker.status}")
                        // TODO: Handle worker details (navigate to detail screen, show dialog, etc.)
                    } else {
                        Log.w(TAG, "👷 Worker not found: $workerId")
                    }
                } else {
                    Log.e(TAG, "👷 Failed to load workers: ${workersResult.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "👷 Exception loading worker details", e)
            }
        }
    }

    // ✅ FIXED: Thread-safe WebSocket status updates
    private fun updateWebSocketStatus(isConnected: Boolean) {
        Log.d(TAG, "🔌 updateWebSocketStatus() called with isConnected: $isConnected")

        // Ensure we're on the main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateStateOnMainThread(isConnected)
        } else {
            mainHandler.post {
                updateStateOnMainThread(isConnected)
            }
        }
    }

    private fun updateStateOnMainThread(isConnected: Boolean) {
        val currentState = _state.value
        Log.d(TAG, "🔌 Current state: ${currentState?.javaClass?.simpleName}")

        if (currentState is MainState.Success) {
            _state.value = currentState.copy(isWebSocketConnected = isConnected)
            Log.d(TAG, "🔌 WebSocket status updated: $isConnected")
        } else if (isConnected && currentState !is MainState.Loading) {
            Log.d(TAG, "🔌 WebSocket connected but no success state yet - triggering loadData()")
            loadData()
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "🧹 onCleared() - cleaning up")
        try {
            repository.removeWebSocketListener(webSocketListener)
            repository.disconnectWebSocket()
            Log.d(TAG, "🧹 Cleanup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "🧹 Error during cleanup", e)
        }
    }
}