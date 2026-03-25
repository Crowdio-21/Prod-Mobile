package com.example.mcc_phase3.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.regex.Pattern

/**
 * Configuration Manager for storing and retrieving app settings
 * Handles foreman IP address and other configuration data
 */
class ConfigManager private constructor(context: Context) {
    
    companion object {
        private const val TAG = "ConfigManager"
        private const val PREFS_NAME = "CrowdComputeConfig"
        private const val KEY_FOREMAN_IP = "foreman_ip"
        private const val KEY_FOREMAN_PORT = "foreman_port"
        private const val KEY_WEBSOCKET_PORT = "websocket_port"
        private const val KEY_STATISTICS_PORT = "statistics_port"
        private const val KEY_MODEL_STORE_BASE_URL = "model_store_base_url"
        private const val KEY_WORKING_DIR = "working_dir"
        
        // Default values - MUST be configured by user in Settings
        // Empty string forces user to configure their own Foreman IP
        const val DEFAULT_FOREMAN_IP = ""  // No default - user MUST configure in Settings
        const val DEFAULT_FOREMAN_PORT = 8000  // HTTP API port
        const val DEFAULT_WEBSOCKET_PORT = 9000  // WebSocket port
        const val DEFAULT_STATISTICS_PORT = 8000  // Same as foreman for now
        
        @Volatile
        private var instance: ConfigManager? = null
        
        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Get the stored foreman IP address
     */
    fun getForemanIP(): String {
        return prefs.getString(KEY_FOREMAN_IP, DEFAULT_FOREMAN_IP) ?: DEFAULT_FOREMAN_IP
    }
    
    /**
     * Set the foreman IP address
     */
    fun setForemanIP(ip: String) {
        Log.d(TAG, "Setting foreman IP to: $ip")
        prefs.edit().putString(KEY_FOREMAN_IP, ip).apply()
    }
    
    /**
     * Get the stored foreman port
     */
    fun getForemanPort(): Int {
        return prefs.getInt(KEY_FOREMAN_PORT, DEFAULT_FOREMAN_PORT)
    }
    
    /**
     * Set the foreman port
     */
    fun setForemanPort(port: Int) {
        Log.d(TAG, "Setting foreman port to: $port")
        prefs.edit().putInt(KEY_FOREMAN_PORT, port).apply()
    }
    
    /**
     * Get the stored WebSocket port
     */
    fun getWebSocketPort(): Int {
        return prefs.getInt(KEY_WEBSOCKET_PORT, DEFAULT_WEBSOCKET_PORT)
    }
    
    /**
     * Set the WebSocket port
     */
    fun setWebSocketPort(port: Int) {
        Log.d(TAG, "Setting WebSocket port to: $port")
        prefs.edit().putInt(KEY_WEBSOCKET_PORT, port).apply()
    }
    
    /**
     * Get the complete foreman WebSocket URL
     * @return WebSocket URL or null if Foreman IP is not configured
     */
    fun getForemanURL(): String? {
        val ip = getForemanIP()
        if (ip.isEmpty()) {
            return null
        }
        val port = getWebSocketPort()  // Use WebSocket port for WebSocket URL
        return "ws://$ip:$port"
    }
    
    /**
     * Get the complete foreman HTTP API URL
     * @return HTTP URL or null if Foreman IP is not configured
     */
    fun getForemanHttpURL(): String? {
        val ip = getForemanIP()
        if (ip.isEmpty()) {
            return null
        }
        val port = getForemanPort()  // Use HTTP port for HTTP API URL
        return "http://$ip:$port"
    }

    
    /**
     * Get the stored stat service port
     */
    fun getStatServicePort(): Int {
        return prefs.getInt(KEY_STATISTICS_PORT, DEFAULT_STATISTICS_PORT)
    }
    
    /**
     * Set the stat service port
     */
    fun setStatServicePort(port: Int) {
        Log.d(TAG, "Setting statistics service port to: $port")
        prefs.edit().putInt(KEY_STATISTICS_PORT, port).apply()
    }

    /**
     * Get the model store base URL (for downloading TFLite partitions).
     * If set, this is used instead of Foreman IP for model downloads (e.g. when model store
     * runs on a different host). Example: http://192.168.1.12:8001/.model_store
     */
    fun getModelStoreBaseUrl(): String {
        return prefs.getString(KEY_MODEL_STORE_BASE_URL, "")?.trim() ?: ""
    }

    /**
     * Set the model store base URL. Use empty string to fall back to Foreman IP.
     */
    fun setModelStoreBaseUrl(url: String) {
        Log.d(TAG, "Setting model store base URL to: ${if (url.isBlank()) "(use Foreman IP)" else url}")
        prefs.edit().putString(KEY_MODEL_STORE_BASE_URL, url.trim()).apply()
    }

    /**
     * Get the complete Stat service URL
     * @return Stat service URL or null if Foreman IP is not configured
     */
    fun getStatServiceURL(): String? {
        val ip = getForemanIP()
        if (ip.isEmpty()) {
            return null
        }
        val port = getStatServicePort()
        return "http://$ip:$port"
    }
    
    /**
     * Check if foreman IP is configured (not default)
     */
    fun isForemanConfigured(): Boolean {
        val currentIP = getForemanIP()
        val currentPort = getForemanPort()
        return currentIP.isNotEmpty() && 
               currentIP != DEFAULT_FOREMAN_IP && 
               isValidIPAddress(currentIP) && 
               isValidPort(currentPort)
    }
    
    /**
     * Validate IP address format
     */
    fun isValidIPAddress(ip: String): Boolean {
        if (ip.isBlank()) return false
        
        // IPv4 pattern
        val ipv4Pattern = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        )
        
        // IPv6 pattern (simplified)
        val ipv6Pattern = Pattern.compile(
            "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$"
        )
        
        return ipv4Pattern.matcher(ip).matches() || ipv6Pattern.matcher(ip).matches()
    }
    
    /**
     * Validate port number
     */
    fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }
    
    /**
     * Clear all stored configuration
     */
    fun clearConfiguration() {
        Log.d(TAG, "Clearing all configuration")
        prefs.edit().clear().apply()
    }
    
    /**
     * Get the stored working directory URI string for code execution
     */
    fun getWorkingDir(): String {
        return prefs.getString(KEY_WORKING_DIR, "") ?: ""
    }

    /**
     * Set the working directory URI string for code execution
     */
    fun setWorkingDir(uriString: String) {
        Log.d(TAG, "Setting working directory to: $uriString")
        prefs.edit().putString(KEY_WORKING_DIR, uriString).apply()
    }

    /**
     * Get configuration summary for logging
     */
    fun getConfigSummary(): String {
        return """
            Configuration Summary:
            - Foreman IP: ${getForemanIP()}
            - Foreman Port (HTTP): ${getForemanPort()}
            - WebSocket Port: ${getWebSocketPort()}
            - Foreman HTTP URL: ${getForemanHttpURL()}
            - Foreman WebSocket URL: ${getForemanURL()}
            - Stat Service IP: ${getForemanIP()}
            - Stat Service Port: ${getStatServicePort()}
            - Stat Service URL: ${getStatServiceURL()}
            - Model Store Base URL: ${getModelStoreBaseUrl().ifBlank { "(use Foreman IP)" }}
            - Working Directory: ${getWorkingDir().ifBlank { "(not set)" }}
            - Is Configured: ${isForemanConfigured()}
        """.trimIndent()
    }
    
}
