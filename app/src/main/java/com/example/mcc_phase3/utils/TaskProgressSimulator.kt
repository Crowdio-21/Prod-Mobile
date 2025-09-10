package com.example.mcc_phase3.utils

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Simulates task execution progress for demonstration purposes
 * This can be used to show horizontal loading lines in the activity section
 */
class TaskProgressSimulator {
    
    companion object {
        private const val TAG = "TaskProgressSimulator"
        private const val SIMULATION_DURATION_MS = 5000L // 5 seconds total
        private const val PROGRESS_UPDATE_INTERVAL_MS = 200L // Update every 200ms
    }
    
    private val activeTasks = ConcurrentHashMap<String, TaskProgress>()
    private val _taskProgressFlow = MutableStateFlow<Map<String, TaskProgress>>(emptyMap())
    val taskProgressFlow: StateFlow<Map<String, TaskProgress>> = _taskProgressFlow.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class TaskProgress(
        val taskId: String,
        val status: String, // "pending", "executing", "completed", "failed"
        val progress: Int, // 0-100
        val startTime: Long,
        val executionTime: Long? = null,
        val result: String? = null,
        val error: String? = null
    )
    
    /**
     * Start simulating a task execution
     */
    fun startTaskSimulation(taskId: String, shouldSucceed: Boolean = true) {
        Log.d(TAG, "🚀 Starting task simulation for: $taskId")
        
        val startTime = System.currentTimeMillis()
        val taskProgress = TaskProgress(
            taskId = taskId,
            status = "executing",
            progress = 0,
            startTime = startTime
        )
        
        activeTasks[taskId] = taskProgress
        updateProgressFlow()
        
        // Start the simulation coroutine
        scope.launch {
            simulateTaskExecution(taskId, shouldSucceed)
        }
    }
    
    private suspend fun simulateTaskExecution(taskId: String, shouldSucceed: Boolean) {
        val startTime = System.currentTimeMillis()
        val totalDuration = SIMULATION_DURATION_MS
        
        try {
            // Simulate task execution with progress updates
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = ((elapsed.toFloat() / totalDuration) * 100).toInt().coerceIn(0, 100)
                
                val currentTask = activeTasks[taskId] ?: break
                val updatedTask = currentTask.copy(
                    progress = progress,
                    executionTime = elapsed
                )
                
                activeTasks[taskId] = updatedTask
                updateProgressFlow()
                
                if (progress >= 100) {
                    // Task completed
                    val finalTask = updatedTask.copy(
                        status = if (shouldSucceed) "completed" else "failed",
                        executionTime = elapsed,
                        result = if (shouldSucceed) "Simulated result: ${(Math.random() * 100).toInt()}" else null,
                        error = if (!shouldSucceed) "Simulated error: Task failed" else null
                    )
                    
                    activeTasks[taskId] = finalTask
                    updateProgressFlow()
                    
                    Log.d(TAG, "✅ Task simulation completed: $taskId (${elapsed}ms)")
                    break
                }
                
                delay(PROGRESS_UPDATE_INTERVAL_MS)
            }
            
            // Remove completed task after a delay
            delay(2000)
            activeTasks.remove(taskId)
            updateProgressFlow()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Task simulation failed: $taskId", e)
            val failedTask = activeTasks[taskId]?.copy(
                status = "failed",
                error = "Simulation error: ${e.message}"
            )
            if (failedTask != null) {
                activeTasks[taskId] = failedTask
                updateProgressFlow()
            }
        }
    }
    
    private fun updateProgressFlow() {
        _taskProgressFlow.value = activeTasks.toMap()
    }
    
    /**
     * Get current progress for a specific task
     */
    fun getTaskProgress(taskId: String): TaskProgress? {
        return activeTasks[taskId]
    }
    
    /**
     * Get all active tasks
     */
    fun getAllActiveTasks(): Map<String, TaskProgress> {
        return activeTasks.toMap()
    }
    
    /**
     * Stop a specific task simulation
     */
    fun stopTaskSimulation(taskId: String) {
        Log.d(TAG, "⏹️ Stopping task simulation: $taskId")
        activeTasks.remove(taskId)
        updateProgressFlow()
    }
    
    /**
     * Stop all task simulations
     */
    fun stopAllSimulations() {
        Log.d(TAG, "⏹️ Stopping all task simulations")
        activeTasks.clear()
        updateProgressFlow()
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
        activeTasks.clear()
        updateProgressFlow()
    }
}
