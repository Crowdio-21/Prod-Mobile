package com.example.mcc_phase3.execution

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.mcc_phase3.services.MobileWorkerService
import kotlinx.coroutines.*

/**
 * ViewModel that manages the lifecycle of the current worker task.
 *
 * Responsibilities:
 * - Expose observable [taskState] so the UI can enable/disable Pause, Resume, Kill buttons.
 * - Delegate pause/resume/kill commands to [MobileWorkerService] → TaskProcessor → PythonExecutor.
 * - Ensure only one task runs at a time and that state transitions are valid.
 * - Poll the service for live task status and keep the UI in sync.
 */
class WorkerJobController(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "WorkerJobController"
    }

    /** Possible states of the current worker task. */
    enum class TaskState {
        /** No task is running – worker may or may not be connected. */
        IDLE,
        /** A task is actively executing on Dispatchers.IO. */
        RUNNING,
        /** The task has been paused via the cooperative flag. */
        PAUSED
    }

    private val _taskState = MutableLiveData(TaskState.IDLE)

    /** Observable task state for the UI layer. */
    val taskState: LiveData<TaskState> get() = _taskState

    /** Reference to the bound service – set by the Fragment/Activity via [bindService]. */
    private var service: MobileWorkerService? = null

    /** Background polling job. */
    private var pollingJob: Job? = null

    // ── Service binding ─────────────────────────────────────────────

    /** Called by the Fragment when the service becomes available. */
    fun bindService(svc: MobileWorkerService) {
        service = svc
        startPolling()
    }

    /** Called by the Fragment when the service disconnects. */
    fun unbindService() {
        pollingJob?.cancel()
        pollingJob = null
        service = null
        _taskState.postValue(TaskState.IDLE)
    }

    // ── Control actions ─────────────────────────────────────────────

    /**
     * Pause the running task.
     * Only valid when [taskState] == [TaskState.RUNNING].
     */
    fun pauseTask() {
        if (_taskState.value != TaskState.RUNNING) {
            Log.w(TAG, "pauseTask() ignored – current state: ${_taskState.value}")
            return
        }
        service?.pauseCurrentTask()
        _taskState.value = TaskState.PAUSED
    }

    /**
     * Resume a paused task.
     * Only valid when [taskState] == [TaskState.PAUSED].
     */
    fun resumeTask() {
        if (_taskState.value != TaskState.PAUSED) {
            Log.w(TAG, "resumeTask() ignored – current state: ${_taskState.value}")
            return
        }
        service?.resumeCurrentTask()
        _taskState.value = TaskState.RUNNING
    }

    /**
     * Kill (cancel) the current task, whether it is running or paused.
     * Transitions state to [TaskState.IDLE].
     */
    fun killTask() {
        val current = _taskState.value
        if (current != TaskState.RUNNING && current != TaskState.PAUSED) {
            Log.w(TAG, "killTask() ignored – current state: $current")
            return
        }
        service?.killCurrentTask()
        _taskState.value = TaskState.IDLE
    }

    // ── Polling loop ────────────────────────────────────────────────

    /**
     * Polls the service every second to keep [_taskState] in sync with the
     * actual execution status reported by TaskProcessor.
     */
    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val svc = service ?: break
                    val status = svc.getWorkerStatus()

                    if (status != null) {
                        val taskStatus = status["task_processor"] as? Map<*, *>
                        val isBusy = taskStatus?.get("is_busy") as? Boolean ?: false
                        val isPaused = taskStatus?.get("is_paused") as? Boolean ?: false

                        val newState = when {
                            isBusy && isPaused -> TaskState.PAUSED
                            isBusy -> TaskState.RUNNING
                            else -> TaskState.IDLE
                        }
                        _taskState.postValue(newState)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Polling error: ${e.message}")
                }

                delay(1000)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
