package com.crowdio.mcc_phase3.data

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
        private const val KEY_CUSTOM_WORKER_NAME = "custom_worker_name"
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
     * Checks for custom worker name first, then existing ID, then generates new
     */
    fun getOrGenerateWorkerId(): String {
        // Check for custom worker name first
        val customName = prefs.getString(KEY_CUSTOM_WORKER_NAME, null)
        if (customName != null && customName.isNotBlank()) {
            Log.d(TAG, "Using custom worker name: $customName")
            return customName
        }
        
        val existingWorkerId = prefs.getString(KEY_WORKER_ID, null)
        
        return if (existingWorkerId != null) {
            Log.d(TAG, "Retrieved existing worker ID: $existingWorkerId")
            existingWorkerId
        } else {
            val newWorkerId = generateUniqueWorkerId()
            saveWorkerId(newWorkerId)
            Log.d(TAG, "🆕 Generated new worker ID: $newWorkerId")
            newWorkerId
        }
    }
    
    /**
     * Generate a simple, readable worker ID
     * Format: android_<device_model>_<short_id>
     * Example: android_pixel7_a3f2c9
     */
    private fun generateUniqueWorkerId(): String {
        val deviceModel = getDeviceModel()
        val shortId = UUID.randomUUID().toString().substring(0, 6)
        
        // Create a simple, readable worker ID
        val workerId = "android_${deviceModel}_${shortId}"
        
        Log.d(TAG, "🔧 Generated simple worker ID: $workerId")
        
        return workerId
    }
    
    /**
     * Get a simplified device model name
     * Removes spaces and special characters, converts to lowercase
     */
    private fun getDeviceModel(): String {
        val model = android.os.Build.MODEL
            .replace(" ", "")
            .replace("-", "")
            .lowercase()
            .take(12) // Limit to 12 chars
        
        return if (model.isBlank()) "device" else model
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
            Log.w(TAG, "Could not retrieve Android ID, using fallback", e)
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
        Log.d(TAG, "Resetting worker ID")
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
     * Set a custom worker name
     * This will override the auto-generated worker ID
     */
    fun setCustomWorkerName(customName: String) {
        if (customName.isBlank()) {
            Log.w(TAG, "Cannot set empty custom worker name")
            return
        }
        
        prefs.edit().putString(KEY_CUSTOM_WORKER_NAME, customName).apply()
        Log.d(TAG, "Custom worker name set: $customName")
    }
    
    /**
     * Get the custom worker name if set
     */
    fun getCustomWorkerName(): String? {
        return prefs.getString(KEY_CUSTOM_WORKER_NAME, null)
    }
    
    /**
     * Clear the custom worker name (will use auto-generated ID)
     */
    fun clearCustomWorkerName() {
        prefs.edit().remove(KEY_CUSTOM_WORKER_NAME).apply()
        Log.d(TAG, "🗑️ Custom worker name cleared")
    }
    
    /**
     * Get worker ID information for debugging
     */
    fun getWorkerIdInfo(): WorkerIdInfo {
        val workerId = getCurrentWorkerId()
        val generatedAt = getWorkerIdGeneratedAt()
        val deviceId = getDeviceId()
        val customName = getCustomWorkerName()
        
        return WorkerIdInfo(
            workerId = workerId,
            customName = customName,
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
    val customName: String?,
    val deviceId: String,
    val generatedAt: Long,
    val hasWorkerId: Boolean
)
