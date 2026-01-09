package com.example.mcc_phase3.data

import android.content.Context
import android.util.Log

/**
 * Bridge class to provide Python access to WorkerIdManager functionality
 * This class can be called from Chaquopy Python code
 */
class WorkerIdBridge(private val context: Context) {
    
    companion object {
        private const val TAG = "WorkerIdBridge"
    }
    
    private val workerIdManager: WorkerIdManager = WorkerIdManager.getInstance(context)
    
    /**
     * Get or generate a unique worker ID for this device
     * This method can be called from Python code
     */
    fun getOrGenerateWorkerId(): String {
        Log.d(TAG, "🐍 Python requesting worker ID")
        val workerId = workerIdManager.getOrGenerateWorkerId()
        Log.d(TAG, "🐍 Returning worker ID to Python: $workerId")
        return workerId
    }
    
    /**
     * Get the current worker ID without generating a new one
     * Returns null if no worker ID exists
     */
    fun getCurrentWorkerId(): String? {
        Log.d(TAG, "🐍 Python requesting current worker ID")
        val workerId = workerIdManager.getCurrentWorkerId()
        Log.d(TAG, "🐍 Returning current worker ID to Python: $workerId")
        return workerId
    }
    
    /**
     * Check if a worker ID exists in storage
     */
    fun hasWorkerId(): Boolean {
        Log.d(TAG, "🐍 Python checking if worker ID exists")
        val hasId = workerIdManager.hasWorkerId()
        Log.d(TAG, "🐍 Worker ID exists: $hasId")
        return hasId
    }
    
    /**
     * Reset the worker ID (generates a new one)
     */
    fun resetWorkerId(): String {
        Log.d(TAG, "🐍 Python requesting worker ID reset")
        val newWorkerId = workerIdManager.resetWorkerId()
        Log.d(TAG, "🐍 Generated new worker ID for Python: $newWorkerId")
        return newWorkerId
    }
    
    /**
     * Get worker ID information for debugging
     */
    fun getWorkerIdInfo(): Map<String, Any?> {
        Log.d(TAG, "🐍 Python requesting worker ID info")
        val info = workerIdManager.getWorkerIdInfo()
        val infoMap = mapOf(
            "workerId" to info.workerId,
            "deviceId" to info.deviceId,
            "generatedAt" to info.generatedAt,
            "hasWorkerId" to info.hasWorkerId
        )
        Log.d(TAG, "🐍 Returning worker ID info to Python: $infoMap")
        return infoMap
    }
}
