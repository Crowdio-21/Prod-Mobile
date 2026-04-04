package com.crowdio.mcc_phase3.ui.mvi

import com.crowdio.mcc_phase3.data.models.*

sealed class MainState {
    object Loading : MainState()
    
    data class Success(
        val stats: Stats? = null,
        val jobs: List<Job> = emptyList(),
        val workers: List<Worker> = emptyList(),
        val websocketStats: WebsocketStats? = null,
        val activity: List<Activity> = emptyList(),
        val isWebSocketConnected: Boolean = false
    ) : MainState()
    
    data class Error(val message: String) : MainState()
}
