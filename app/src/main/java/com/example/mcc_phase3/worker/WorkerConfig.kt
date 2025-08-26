package com.example.mcc_phase3.worker

import com.example.mcc_phase3.data.ConfigManager

/**
 * Configuration for the Mobile Worker Service
 *
 * This file allows you to easily configure how the mobile worker handles tasks:
 * 1. SIMULATION_MODE: Simulates task execution (safe, for testing)
 * 2. PYTHON_FORWARDING: Forwards tasks to a Python worker service
 * 3. PYTHON_LOCAL: Executes Python tasks locally using Chaquopy (default)
 */
object WorkerConfig {

    // ===== EXECUTION MODES =====

    /**
     * SIMULATION_MODE: Simulates task execution
     * - Safe: No Python code execution
     * - Fast: Immediate response
     * - Good for: Testing, development, safe environments
     */
    const val SIMULATION_MODE = false

    /**
     * PYTHON_FORWARDING: Forwards tasks to Python worker service
     * - Safe: Tasks executed on separate Python service
     * - Network: Requires Python worker running on same network
     * - Good for: Production with Python workers
     */
    const val PYTHON_FORWARDING = false

    /**
     * PYTHON_LOCAL: Executes Python tasks locally using Chaquopy
     * - Advanced: Requires Python runtime (Chaquopy)
     * - Powerful: Full Python execution capability
     * - Good for: Advanced mobile computing (default)
     */
    const val PYTHON_LOCAL = true

    // ===== NETWORK CONFIGURATION =====

    /**
     * Get Foreman WebSocket URL from ConfigManager
     * This is now dynamically configured by the user
     */
    fun getForemanURL(context: android.content.Context): String {
        return ConfigManager.getInstance(context).getForemanURL()
    }

    /**
     * Get Statistics Service URL from ConfigManager
     * This is now dynamically configured by the user
     */
    fun getStatServiceURL(context: android.content.Context): String {
        return ConfigManager.getInstance(context).getStatServiceURL()
    }

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
        "python_execution",
        "chaquopy_runtime",
        "numpy_support",
        "pandas_support",
        "scipy_support",
        "matplotlib_support"
    ).apply {
        if (PYTHON_LOCAL) plus("python_local_execution")
        if (PYTHON_FORWARDING) plus("python_forwarding")
        if (SIMULATION_MODE) plus("task_simulation")
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
            PYTHON_LOCAL -> "Python Local Execution (Chaquopy)"
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
    fun getConfigSummary(context: android.content.Context): String {
        return """
            Mobile Worker Configuration:
            - Execution Mode: ${getExecutionMode()}
            - Worker ID: $WORKER_ID
            - Foreman URL: ${getForemanURL(context)}
            - Statistics Service URL: ${getStatServiceURL(context)}
            - Max Concurrent Tasks: $MAX_CONCURRENT_TASKS
            - Heartbeat Interval: ${HEARTBEAT_INTERVAL}s
            - Task Timeout: ${TASK_TIMEOUT}ms
            - Python Execution: ${canExecutePython()}
            - Capabilities: ${CAPABILITIES.joinToString(", ")}
        """.trimIndent()
    }
}
