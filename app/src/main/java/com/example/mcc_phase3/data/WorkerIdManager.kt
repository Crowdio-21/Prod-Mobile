package com.example.mcc_phase3.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import java.util.UUID

/**
 * Manager for generating and storing unique Android worker IDs
 * Provides persistent storage of worker IDs using SharedPreferences
 */
class WorkerIdManager private constructor(context: Context) {
    
    companion object {
        private const val TAG = "WorkerIdManager"
        private const val PREFS_NAME = "WorkerIdStorage"
        private const val KEY_WORKER_ID = "worker_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_WORKER_ID_GENERATED_AT = "worker_id_generated_at"
        
        @Volatile
        private var instance: WorkerIdManager? = null
        
        fun getInstance(context: Context): WorkerIdManager {
            return instance ?: synchronized(this) {
                instance ?: WorkerIdManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val applicationContext: Context = context.applicationContext
    
    /**
     * Get or generate a unique worker ID for this device
     * The worker ID is persistent across app restarts and device reboots
     */
    fun getOrGenerateWorkerId(): String {
        val existingWorkerId = prefs.getString(KEY_WORKER_ID, null)
        
        return if (existingWorkerId != null) {
            Log.d(TAG, "✅ Retrieved existing worker ID: $existingWorkerId")
            existingWorkerId
        } else {
            val newWorkerId = generateUniqueWorkerId()
            saveWorkerId(newWorkerId)
            Log.d(TAG, "🆕 Generated new worker ID: $newWorkerId")
            newWorkerId
        }
    }
    
    /**
     * Generate a unique worker ID based on device characteristics
     * Uses Android ID as the primary identifier with additional entropy
     */
    private fun generateUniqueWorkerId(): String {
        val deviceId = getDeviceId()
        val timestamp = System.currentTimeMillis()
        val randomSuffix = UUID.randomUUID().toString().substring(0, 8)
        
        // Create a unique worker ID combining device ID, timestamp, and random suffix
        val workerId = "android_worker_${deviceId}_${timestamp}_${randomSuffix}"
        
        Log.d(TAG, "🔧 Generated worker ID components:")
        Log.d(TAG, "   Device ID: $deviceId")
        Log.d(TAG, "   Timestamp: $timestamp")
        Log.d(TAG, "   Random suffix: $randomSuffix")
        Log.d(TAG, "   Final worker ID: $workerId")
        
        return workerId
    }
    
    /**
     * Get a unique device identifier
     * Uses Android ID as the primary identifier
     */
    private fun getDeviceId(): String {
        return try {
            val androidId = Settings.Secure.getString(
                applicationContext.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            
            if (androidId != null && androidId != "9774d56d682e549c") {
                // Valid Android ID (not the default emulator ID)
                Log.d(TAG, "📱 Using Android ID: $androidId")
                androidId
            } else {
                // Fallback to generated UUID stored in preferences
                getOrGenerateDeviceId()
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not retrieve Android ID, using fallback", e)
            getOrGenerateDeviceId()
        }
    }
    
    /**
     * Get or generate a fallback device ID stored in preferences
     */
    private fun getOrGenerateDeviceId(): String {
        val existingDeviceId = prefs.getString(KEY_DEVICE_ID, null)
        
        return if (existingDeviceId != null) {
            Log.d(TAG, "📱 Using stored device ID: $existingDeviceId")
            existingDeviceId
        } else {
            val newDeviceId = "device_${UUID.randomUUID().toString().replace("-", "")}"
            prefs.edit().putString(KEY_DEVICE_ID, newDeviceId).apply()
            Log.d(TAG, "🆕 Generated new device ID: $newDeviceId")
            newDeviceId
        }
    }
    
    /**
     * Save the worker ID to persistent storage
     */
    private fun saveWorkerId(workerId: String) {
        val editor = prefs.edit()
        editor.putString(KEY_WORKER_ID, workerId)
        editor.putLong(KEY_WORKER_ID_GENERATED_AT, System.currentTimeMillis())
        editor.apply()
        
        Log.d(TAG, "💾 Saved worker ID to storage: $workerId")
    }
    
    /**
     * Get the current worker ID without generating a new one
     * Returns null if no worker ID exists
     */
    fun getCurrentWorkerId(): String? {
        return prefs.getString(KEY_WORKER_ID, null)
    }
    
    /**
     * Get the timestamp when the worker ID was generated
     */
    fun getWorkerIdGeneratedAt(): Long {
        return prefs.getLong(KEY_WORKER_ID_GENERATED_AT, 0L)
    }
    
    /**
     * Reset the worker ID (generates a new one)
     * Useful for testing or if the worker ID needs to be regenerated
     */
    fun resetWorkerId(): String {
        Log.d(TAG, "🔄 Resetting worker ID")
        val newWorkerId = generateUniqueWorkerId()
        saveWorkerId(newWorkerId)
        return newWorkerId
    }
    
    /**
     * Check if a worker ID exists in storage
     */
    fun hasWorkerId(): Boolean {
        return prefs.contains(KEY_WORKER_ID)
    }
    
    /**
     * Get worker ID information for debugging
     */
    fun getWorkerIdInfo(): WorkerIdInfo {
        val workerId = getCurrentWorkerId()
        val generatedAt = getWorkerIdGeneratedAt()
        val deviceId = getDeviceId()
        
        return WorkerIdInfo(
            workerId = workerId,
            deviceId = deviceId,
            generatedAt = generatedAt,
            hasWorkerId = hasWorkerId()
        )
    }
}

/**
 * Data class containing worker ID information
 */
data class WorkerIdInfo(
    val workerId: String?,
    val deviceId: String,
    val generatedAt: Long,
    val hasWorkerId: Boolean
)
