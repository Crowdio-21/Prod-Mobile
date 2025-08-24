package com.example.mcc_phase3.worker

/**
 * Configuration for the Mobile Worker Service
 *
 * This file allows you to easily configure how the mobile worker handles tasks:
 * 1. SIMULATION_MODE: Simulates task execution (default, safe)
 * 2. PYTHON_FORWARDING: Forwards tasks to a Python worker service
 * 3. PYTHON_LOCAL: Executes Python tasks locally (requires Python runtime)
 */
object WorkerConfig {

    // ===== EXECUTION MODES =====

    /**
     * SIMULATION_MODE: Simulates task execution
     * - Safe: No Python code execution
     * - Fast: Immediate response
     * - Good for: Testing, development, safe environments
     */
    const val SIMULATION_MODE = true

    /**
     * PYTHON_FORWARDING: Forwards tasks to Python worker service
     * - Safe: Tasks executed on separate Python service
     * - Network: Requires Python worker running on same network
     * - Good for: Production with Python workers
     */
    const val PYTHON_FORWARDING = false

    /**
     * PYTHON_LOCAL: Executes Python tasks locally
     * - Advanced: Requires Python runtime (Chaquopy, BeeWare, etc.)
     * - Powerful: Full Python execution capability
     * - Good for: Advanced mobile computing
     */
    const val PYTHON_LOCAL = false

    // ===== NETWORK CONFIGURATION =====

    /**
     * Foreman WebSocket URL
     * Update this to match your foreman's IP address
     */
    // WebSocket URL for foreman connection
    const val FOREMAN_URL = "ws://192.168.8.101:9000"

    /**
     * Python Worker Service URL (for task forwarding)
     * This should point to a Python worker service running on your network
     */
    const val PYTHON_SERVICE_URL = "http://192.168.8.101:8001"

    // ===== WORKER IDENTITY =====

    /**
     * Unique worker ID for this mobile device
     * Change this if you have multiple mobile workers
     */
    // Mobile worker identifier
    const val WORKER_ID = "mobile_worker_001"

    /**
     * Worker capabilities
     * These are sent to the foreman when registering
     */
    val CAPABILITIES = listOf(
        "mobile_optimized",
        "task_simulation",
        "network_forwarding"
    ).apply {
        if (PYTHON_LOCAL) plus("python_execution")
        if (PYTHON_FORWARDING) plus("python_forwarding")
    }

    // ===== PERFORMANCE SETTINGS =====

    /**
     * Maximum concurrent tasks this worker can handle
     * Mobile devices typically handle 1-2 tasks at a time
     */
    const val MAX_CONCURRENT_TASKS = 1

    /**
     * Heartbeat interval in seconds
     * How often to send status updates to foreman
     */
    const val HEARTBEAT_INTERVAL = 30

    /**
     * Task timeout in milliseconds
     * Maximum time to wait for task completion
     */
    const val TASK_TIMEOUT = 30000L

    // ===== SIMULATION SETTINGS =====

    /**
     * Base execution time for simulated tasks (milliseconds)
     */
    const val SIMULATION_BASE_TIME = 1000L

    /**
     * Enable realistic task timing based on complexity
     */
    const val REALISTIC_TIMING = true

    /**
     * Enable meaningful simulated results
     */
    const val MEANINGFUL_RESULTS = true

    // ===== LOGGING SETTINGS =====

    /**
     * Enable detailed task logging
     */
    const val DETAILED_LOGGING = true

    /**
     * Log function pickle content (for debugging)
     */
    const val LOG_FUNCTION_CONTENT = false

    /**
     * Log task arguments (for debugging)
     */
    const val LOG_TASK_ARGS = true

    // ===== VALIDATION =====

    init {
        // Validate configuration
        require(!(PYTHON_LOCAL && PYTHON_FORWARDING)) {
            "Cannot enable both PYTHON_LOCAL and PYTHON_FORWARDING"
        }

        require(MAX_CONCURRENT_TASKS > 0) {
            "MAX_CONCURRENT_TASKS must be positive"
        }

        require(HEARTBEAT_INTERVAL > 0) {
            "HEARTBEAT_INTERVAL must be positive"
        }

        require(TASK_TIMEOUT > 0) {
            "TASK_TIMEOUT must be positive"
        }
    }

    /**
     * Get the current execution mode as a string
     */
    fun getExecutionMode(): String {
        return when {
            PYTHON_LOCAL -> "Python Local Execution"
            PYTHON_FORWARDING -> "Python Task Forwarding"
            SIMULATION_MODE -> "Task Simulation"
            else -> "Unknown Mode"
        }
    }

    /**
     * Check if Python execution is available
     */
    fun canExecutePython(): Boolean {
        return PYTHON_LOCAL || PYTHON_FORWARDING
    }

    /**
     * Get configuration summary for logging
     */
    fun getConfigSummary(): String {
        return """
            Mobile Worker Configuration:
            - Execution Mode: ${getExecutionMode()}
            - Worker ID: $WORKER_ID
            - Foreman URL: $FOREMAN_URL
            - Max Concurrent Tasks: $MAX_CONCURRENT_TASKS
            - Heartbeat Interval: ${HEARTBEAT_INTERVAL}s
            - Task Timeout: ${TASK_TIMEOUT}ms
            - Python Execution: ${canExecutePython()}
            - Capabilities: ${CAPABILITIES.joinToString(", ")}
        """.trimIndent()
    }
}
