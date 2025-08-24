package com.example.mcc_phase3.ui.mvi

sealed class MainEvent {
    object LoadData : MainEvent()
    object RefreshData : MainEvent()
    object ConnectWebSocket : MainEvent()
    object DisconnectWebSocket : MainEvent()
    data class LoadJobDetails(val jobId: String) : MainEvent()
    data class LoadWorkerDetails(val workerId: String) : MainEvent()
}
