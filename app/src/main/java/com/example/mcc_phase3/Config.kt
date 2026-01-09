package com.example.mcc_phase3

/**
 * Configuration class for the CrowdCompute mobile app
 * Centralizes all configurable settings
 * 
 * NOTE: Foreman server configuration is now managed by ConfigManager
 * Use ConfigManager.getInstance(context) to get/set foreman IP and ports
 */
object Config {
    
    // Foreman server configuration - DEPRECATED: Use ConfigManager instead
    @Deprecated("Use ConfigManager.getInstance(context).getForemanIP() instead", ReplaceWith("ConfigManager.getInstance(context).getForemanIP()"))
    const val FOREMAN_IP = ""  // No default - configure in Settings
    
    @Deprecated("Use ConfigManager.getInstance(context).getWebSocketPort() instead", ReplaceWith("ConfigManager.getInstance(context).getWebSocketPort()"))
    const val FOREMAN_PORT = "9000"
    
    // WebSocket URL for foreman - DEPRECATED: Use ConfigManager.getForemanURL() instead
    @Deprecated("Use ConfigManager.getInstance(context).getForemanURL() instead")
    val FOREMAN_WS_URL: String
        get() = "ws://$FOREMAN_IP:$FOREMAN_PORT"
    
    // HTTP URL for foreman API - DEPRECATED: Use ConfigManager.getForemanHttpURL() instead
    @Deprecated("Use ConfigManager.getInstance(context).getForemanHttpURL() instead")
    val FOREMAN_HTTP_URL: String
        get() = "http://$FOREMAN_IP:$FOREMAN_PORT"
    
    // Worker configuration
    const val WORKER_ID_PREFIX = "android_worker"
    const val DEFAULT_BATTERY_THRESHOLD = 20
    const val HEARTBEAT_INTERVAL = 30
    
    // Logging configuration
    const val LOG_DIR = "CrowdCompute/logs"
    const val LOG_FILE_PREFIX = "worker_"
    
    // UI configuration
    const val DASHBOARD_REFRESH_INTERVAL = 10000L // 10 seconds
    const val MAX_LOG_LINES = 100
}


