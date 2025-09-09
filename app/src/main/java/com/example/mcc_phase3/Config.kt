package com.example.mcc_phase3

/**
 * Configuration class for the CrowdCompute mobile app
 * Centralizes all configurable settings
 */
object Config {
    
    // Foreman server configuration
    const val FOREMAN_IP = "10.10.2.17"
    const val FOREMAN_PORT = "9000"
    
    // WebSocket URL for foreman
    val FOREMAN_WS_URL: String
        get() = "ws://$FOREMAN_IP:$FOREMAN_PORT"
    
    // HTTP URL for foreman API
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


