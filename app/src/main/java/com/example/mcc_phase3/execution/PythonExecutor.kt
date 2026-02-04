package com.example.mcc_phase3.execution

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.checkpoint.CheckpointHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * PythonExecutor handles only Python code execution via Chaquopy
 * This class is responsible for:
 * - Initializing Python environment
 * - Executing Python code sent from backend
 * - Managing Python execution context
 * - Supporting checkpoint state updates during execution
 * - NOT handling API calls or WebSocket communication
 */
class PythonExecutor(private val context: Context) {
    
    companion object {
        private const val TAG = "PythonExecutor"
    }
    
    private val isInitialized = AtomicBoolean(false)
    private val workerIdManager = WorkerIdManager.getInstance(context)
    
    // Checkpoint handler reference for progress updates during execution
    private val checkpointHandlerRef = AtomicReference<CheckpointHandler?>(null)
    
    // Python modules
    private var pythonInstance: Python? = null
    private var builtinsModule: PyObject? = null
    private var jsonModule: PyObject? = null
    private var base64Module: PyObject? = null
    
    /**
     * Set the checkpoint handler for progress updates during Python execution
     */
    fun setCheckpointHandler(handler: CheckpointHandler?) {
        checkpointHandlerRef.set(handler)
        Log.d(TAG, "Checkpoint handler ${if (handler != null) "set" else "cleared"}")
    }
    
    /**
     * Update checkpoint state (can be called from Python via callback)
     */
    fun updateCheckpointState(
        progressPercent: Float,
        estimatedE: Double,
        trialsCompleted: Int,
        totalCount: Long,
        numTrials: Int = 0
    ) {
        checkpointHandlerRef.get()?.updateState(
            CheckpointHandler.CheckpointState(
                trialsCompleted = trialsCompleted,
                totalCount = totalCount,
                numTrials = numTrials,
                progressPercent = progressPercent,
                estimatedE = estimatedE
            )
        )
    }
    
    /**
     * Configure checkpoint capture for a specific function.
     * On Android, this sets up the state variables to capture but does NOT use sys.settrace()
     * since it's not supported in Chaquopy. Instead, we rely on code instrumentation.
     * 
     * @param funcName The name of the function to trace
     * @param checkpointStateVars List of variable names to capture (empty = capture common vars)
     */
    fun configureCheckpointTrace(funcName: String, checkpointStateVars: List<String> = emptyList()) {
        try {
            val stateVarsList = checkpointStateVars.joinToString(",") { "'$it'" }
            val configCode = """
import builtins

# Configure the function name (for reference)
builtins._checkpoint_func_name = '${funcName}'

# Configure which variables to capture
builtins._checkpoint_state_vars = [${stateVarsList}]

# NOTE: On Android/Chaquopy, sys.settrace() is NOT reliable
# The checkpoint state must be updated via explicit callback calls
# Either manually in user code or via code instrumentation

print("[Android Worker] Checkpoint configured for function: ${funcName}, vars: [${stateVarsList}]")
print("[Android Worker] State will be captured via explicit builtins._checkpoint_callback() calls")
""".trimIndent()
            
            // IMPORTANT: exec() on Chaquopy requires explicit globals dict to avoid "frame does not exist" error
            val globalDict = pythonInstance?.getModule("builtins")?.callAttr("dict")
            builtinsModule?.callAttr("exec", configCode, globalDict)
            Log.d(TAG, "Checkpoint configured for function: $funcName, vars: $checkpointStateVars (no trace - Android mode)")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure checkpoint: ${e.message}")
        }
    }
    
    /**
     * Disable checkpoint capture after task execution.
     * On Android, this just clears the function name since we don't use sys.settrace()
     */
    fun disableCheckpointTrace() {
        try {
            val disableCode = """
import builtins

# Clear the function name
builtins._checkpoint_func_name = None
print("[Android Worker] Checkpoint capture disabled")
""".trimIndent()
            
            // IMPORTANT: exec() on Chaquopy requires explicit globals dict to avoid "frame does not exist" error
            val globalDict = pythonInstance?.getModule("builtins")?.callAttr("dict")
            builtinsModule?.callAttr("exec", disableCode, globalDict)
            Log.d(TAG, "Checkpoint capture disabled")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable checkpoint capture: ${e.message}")
        }
    }
    
    /**
     * Initialize Python environment
     */
    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isInitialized.get()) {
                    Log.d(TAG, "Python already initialized")
                    return@withContext true
                }
                
                Log.d(TAG, "Initializing Python environment...")
                
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context))
                }
                
                pythonInstance = Python.getInstance()
                builtinsModule = pythonInstance?.getModule("builtins")
                jsonModule = pythonInstance?.getModule("json")
                base64Module = pythonInstance?.getModule("base64")
                
                isInitialized.set(true)
                Log.d(TAG, "✅ Python environment initialized successfully")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize Python environment", e)
                false
            }
        }
    }
    
    /**
     * Execute Python code sent from backend
     * @param serializedCode Base64 encoded Python code
     * @param serializedArgs Base64 encoded arguments
     * @return Execution result as Map
     */
    suspend fun executeCode(serializedCode: String, serializedArgs: String? = null): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized.get()) {
                    val initialized = initialize()
                    if (!initialized) {
                        return@withContext mapOf<String, Any?>(
                            "status" to "error",
                            "message" to "Failed to initialize Python environment",
                            "result" to null
                        )
                    }
                }

                Log.d(TAG, "Executing Python code...")

                // Handle Python code - check if it's base64 encoded or plain text
                var pythonCode = if (isBase64Encoded(serializedCode)) {
                    decodeBase64(serializedCode)
                } else {
                    serializedCode // Use as plain text
                }
                
                // Replace PyTorch/Transformers sentiment functions with TextBlob version for mobile
                if (pythonCode.contains("import torch") && pythonCode.contains("sentiment_worker_pytorch")) {
                    Log.d(TAG, "⚠️ Detected PyTorch sentiment function, replacing with mobile-compatible version")
                    pythonCode = loadMobileSentimentWorker()
                } else if (pythonCode.contains("from transformers import") || pythonCode.contains("import transformers")) {
                    Log.d(TAG, "⚠️ Detected Transformers library usage, replacing with mobile-compatible version")
                    pythonCode = getMobileCompatibleSentimentFunction()
                }
                
                Log.d(TAG, "Python code (first 100 chars): ${pythonCode.take(100)}...")

                // Handle arguments - check if they're base64 encoded or plain text
                val args: PyObject? = if (serializedArgs != null && serializedArgs.isNotEmpty()) {
                    if (isBase64Encoded(serializedArgs)) {
                        val decodedArgs = decodeBase64(serializedArgs)
                        jsonModule?.callAttr("loads", decodedArgs)
                    } else {
                        // Try to parse as JSON directly
                        jsonModule?.callAttr("loads", serializedArgs)
                    }
                } else {
                    null
                }

                Log.d(TAG, "Decoded arguments: $args")
                
                // Set up checkpoint callback in Python builtins if handler is available
                setupCheckpointCallback()

                // Execute the Python code using the desktop worker approach
                val result = executeFunctionCode(pythonCode, args)
                
                // Clean up checkpoint callback
                cleanupCheckpointCallback()

                Log.d(TAG, "✅ Python code executed successfully")
                mapOf<String, Any?>(
                    "status" to "success",
                    "message" to "Code executed successfully",
                    "result" to result,
                    "worker_id" to (workerIdManager.getCurrentWorkerId() ?: "unknown")
                )

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to execute Python code", e)
                mapOf<String, Any?>(
                    "status" to "error",
                    "message" to "Execution failed: ${e.message ?: "Unknown error"}",
                    "result" to null,
                    "error" to e.toString()
                )
            }
        }
    }
    
    /**
     * Execute Python code with restored checkpoint state (for task resumption)
     * 
     * Sets up builtins._checkpoint_state with the checkpoint data and _is_resumed=True flag.
     * The Python function should check for this and resume from where it left off.
     * 
     * @param funcCode Python function code
     * @param checkpointStateJson JSON string of the checkpoint state (for setting up builtins._checkpoint_state)
     * @param taskArgsJson JSON string of the original task arguments (for calling the function)
     * @return Execution result as Map
     */
    suspend fun executeCodeWithRestoredState(
        funcCode: String, 
        checkpointStateJson: String,
        taskArgsJson: String
    ): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized.get()) {
                    val initialized = initialize()
                    if (!initialized) {
                        return@withContext mapOf<String, Any?>(
                            "status" to "error",
                            "message" to "Failed to initialize Python environment",
                            "result" to null
                        )
                    }
                }

                Log.d(TAG, "Executing Python code with restored state...")
                Log.d(TAG, "Checkpoint state (first 200 chars): ${checkpointStateJson.take(200)}...")
                Log.d(TAG, "Task args: $taskArgsJson")

                // Set up checkpoint state in Python builtins._checkpoint_state with _is_resumed flag
                // This is separate from task_args - it's only for the Python code to detect resumption
                setupRestoredState(checkpointStateJson)
                
                // Set up checkpoint callback for continued checkpointing
                setupCheckpointCallback()
                
                // Parse the task_args for function execution (NOT the checkpoint state)
                val taskArgs = jsonModule?.callAttr("loads", taskArgsJson)
                
                // Execute the function code with the original task args
                var pythonCode = funcCode
                
                // Replace PyTorch/Transformers sentiment functions with TextBlob version for mobile
                if (pythonCode.contains("import torch") && pythonCode.contains("sentiment_worker_pytorch")) {
                    Log.d(TAG, "⚠️ Detected PyTorch sentiment function, replacing with mobile-compatible version")
                    pythonCode = loadMobileSentimentWorker()
                } else if (pythonCode.contains("from transformers import") || pythonCode.contains("import transformers")) {
                    Log.d(TAG, "⚠️ Detected Transformers library usage, replacing with mobile-compatible version")
                    pythonCode = getMobileCompatibleSentimentFunction()
                }
                
                // Execute with isResume=true to inject resume logic
                val result = executeFunctionCode(pythonCode, taskArgs, isResume = true)
                
                // Clean up
                cleanupRestoredState()
                cleanupCheckpointCallback()

                Log.d(TAG, "Resumed Python code executed successfully")
                mapOf<String, Any?>(
                    "status" to "success",
                    "message" to "Resumed execution completed successfully",
                    "result" to result,
                    "resumed" to true,
                    "worker_id" to (workerIdManager.getCurrentWorkerId() ?: "unknown")
                )

            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute resumed Python code", e)
                cleanupRestoredState()
                cleanupCheckpointCallback()
                mapOf<String, Any?>(
                    "status" to "error",
                    "message" to "Resumed execution failed: ${e.message ?: "Unknown error"}",
                    "result" to null,
                    "resumed" to true,
                    "error" to e.toString()
                )
            }
        }
    }
    
    /**
     * Set up checkpoint state in Python builtins._checkpoint_state for task resumption
     * 
     * This sets the checkpoint state with _is_resumed = True flag so the Python
     * function can detect it's resuming and continue from the saved state.
     */
    private fun setupRestoredState(stateJson: String) {
        try {
            val stateObj = jsonModule?.callAttr("loads", stateJson)
            if (stateObj == null) {
                Log.w(TAG, "Failed to parse checkpoint state JSON; stateObj is null")
                return
            }

            // Set the _is_resumed flag so the Python function knows to resume
            try {
                stateObj.put("_is_resumed", true)
            } catch (e: Exception) {
                // Fallback for dict-like objects without put
                stateObj.callAttr("__setitem__", "_is_resumed", true)
            }

            // Store in builtins so Python code can read it
            builtinsModule?.put("_checkpoint_state", stateObj)

            val keys = stateObj.callAttr("keys")?.toString() ?: "unknown"
            Log.d(TAG, "Checkpoint state set up in Python builtins._checkpoint_state with _is_resumed=True. Keys: $keys")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set up restored state: ${e.message}")
        }
    }
    
    /**
     * Clean up restored state flag from Python builtins
     */
    private fun cleanupRestoredState() {
        try {
            val stateObj = builtinsModule?.get("_checkpoint_state")
            if (stateObj != null) {
                val builtins = pythonInstance?.getModule("builtins")
                val dictType = builtins?.get("dict")
                val isDict = builtins?.callAttr("isinstance", stateObj, dictType)?.toJava(Boolean::class.java) == true
                if (isDict) {
                    // Remove _is_resumed if present
                    try {
                        stateObj.callAttr("pop", "_is_resumed", null)
                    } catch (e: Exception) {
                        // Fallback if pop fails
                        try {
                            stateObj.callAttr("__delitem__", "_is_resumed")
                        } catch (_: Exception) {
                            // Ignore if missing
                        }
                    }
                }
            }
            Log.d(TAG, "Checkpoint state _is_resumed flag cleaned up from Python builtins")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up restored state: ${e.message}")
        }
    }

    /**
     * Test Python execution with a simple function
     */
    suspend fun testExecution(): Map<String, Any?> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized.get()) {
                    val initialized = initialize()
                    if (!initialized) {
                        return@withContext mapOf<String, Any?>(
                            "status" to "error",
                            "message" to "Failed to initialize Python environment"
                        )
                    }
                }
                
                Log.d(TAG, "Testing Python execution...")
                
                // Simple test function
                val testCode = """
                    def test_function(x, y):
                        return x + y
                    
                    result = test_function(5, 3)
                    print(f"Test result: {result}")
                    result
                """.trimIndent()
                
                val result = executePythonCode(testCode, null, createExecutionContext("test_worker"))
                
                Log.d(TAG, "✅ Python execution test successful")
                mapOf<String, Any?>(
                    "status" to "success",
                    "message" to "Python execution test passed",
                    "result" to result
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Python execution test failed", e)
                mapOf<String, Any?>(
                    "status" to "error",
                    "message" to "Python execution test failed: ${e.message ?: "Unknown error"}",
                    "error" to e.toString()
                )
            }
        }
    }
    
    /**
     * Check if Python executor is ready
     */
    fun isReady(): Boolean {
        return isInitialized.get()
    }
    
    /**
     * Set up checkpoint state capture in Python builtins.
     * 
     * This uses sys.settrace() to automatically capture local variables from the
     * executing function on each line, storing them in builtins._checkpoint_state.
     * This matches the PC worker's approach and requires NO changes to user code.
     * 
     * Also provides a fallback callback for explicit updates if needed.
     */
    private fun setupCheckpointCallback() {
        val handler = checkpointHandlerRef.get()
        if (handler != null) {
            try {
                // Set up explicit callback-based checkpoint capture for Android
                // NOTE: sys.settrace() doesn't work reliably on Chaquopy/Android
                // So we use explicit callback + code instrumentation approach
                val setupCode = """
import builtins

# Initialize checkpoint state storage ONLY if not already set (preserve resume state!)
# This is critical for task resumption - don't overwrite the restored checkpoint state
if not hasattr(builtins, '_checkpoint_state') or not builtins._checkpoint_state:
    builtins._checkpoint_state = {}
elif hasattr(builtins, '_checkpoint_state') and builtins._checkpoint_state.get('_is_resumed', False):
    print(f"[Android Worker] Preserving existing checkpoint state for resume: {list(builtins._checkpoint_state.keys())}")

builtins._checkpoint_func_name = None
builtins._checkpoint_state_vars = []  # Will be set by task metadata
builtins._checkpoint_update_counter = 0  # Track number of updates

def _checkpoint_callback(progress_percent=0.0, estimated_e=0.0, trials_completed=0, 
                         total_count=0, num_trials=0, **kwargs):
    '''
    Explicit checkpoint callback - updates builtins._checkpoint_state.
    This is the primary mechanism for Android since sys.settrace() does not work.
    '''
    state = {
        'progress_percent': float(progress_percent),
        'estimated_e': float(estimated_e),
        'trials_completed': int(trials_completed),
        'total_count': int(total_count),
        'num_trials': int(num_trials)
    }
    state.update(kwargs)  # Allow additional custom fields
    builtins._checkpoint_state = state
    builtins._checkpoint_update_counter += 1

def _update_checkpoint_state(**kwargs):
    '''
    Alternative simpler API - just pass the variables to checkpoint.
    '''
    current = getattr(builtins, '_checkpoint_state', {})
    current.update(kwargs)
    builtins._checkpoint_state = current
    builtins._checkpoint_update_counter = getattr(builtins, '_checkpoint_update_counter', 0) + 1

builtins._checkpoint_callback = _checkpoint_callback
builtins._update_checkpoint_state = _update_checkpoint_state

print("[Android Worker] Checkpoint callback initialized (explicit callback mode)")
""".trimIndent()
                
                // IMPORTANT: exec() on Chaquopy requires explicit globals dict to avoid "frame does not exist" error
                val globalDict = pythonInstance?.getModule("builtins")?.callAttr("dict")
                builtinsModule?.callAttr("exec", setupCode, globalDict)
                Log.d(TAG, "Checkpoint callback set up in Python builtins (explicit mode)")
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set up checkpoint callback: ${e.message}")
            }
        }
    }
    
    /**
     * Clean up checkpoint callback and trace from Python builtins
     */
    private fun cleanupCheckpointCallback() {
        try {
            // Read final checkpoint state before cleanup using getattr
            val finalState = builtinsModule?.callAttr("getattr",
                pythonInstance?.getModule("builtins"),
                "_checkpoint_state",
                null
            )
            if (finalState != null && finalState.toString() != "None" && finalState.toString() != "null") {
                Log.d(TAG, "Final checkpoint state: $finalState")
            }
            
            val cleanupCode = """
import builtins

# Clean up builtins attributes (no trace to remove since we don't use sys.settrace on Android)
for attr in ['_checkpoint_callback', '_checkpoint_state', '_update_checkpoint_state',
             '_checkpoint_func_name', '_checkpoint_state_vars', '_checkpoint_update_counter']:
    if hasattr(builtins, attr):
        delattr(builtins, attr)

print("[Android Worker] Checkpoint callback cleaned up")
""".trimIndent()
            
            // IMPORTANT: exec() on Chaquopy requires explicit globals dict to avoid "frame does not exist" error
            val globalDict = pythonInstance?.getModule("builtins")?.callAttr("dict")
            builtinsModule?.callAttr("exec", cleanupCode, globalDict)
            Log.d(TAG, "Checkpoint callback cleaned up from Python builtins")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up checkpoint callback: ${e.message}")
        }
    }
    
    /**
     * Poll checkpoint state from Python and update handler.
     * This is called periodically by the checkpoint handler's monitoring loop.
     * 
     * Reads from builtins._checkpoint_state which is updated by:
     * - Explicitly calling builtins._checkpoint_callback(...) or builtins._update_checkpoint_state(...)
     * - Code instrumentation that auto-injects checkpoint calls at strategic points
     */
    fun pollAndUpdateCheckpointState() {
        val handler = checkpointHandlerRef.get() ?: return
        
        try {
            // Use getattr to properly read builtins._checkpoint_state
            // builtinsModule.get() doesn't work for dynamically set attributes
            val stateObj = builtinsModule?.callAttr("getattr", 
                pythonInstance?.getModule("builtins"), 
                "_checkpoint_state", 
                null  // default value if not found
            )
            
            if (stateObj != null && stateObj.toString() != "None" && stateObj.toString() != "null" && stateObj.toString() != "{}") {
                // Extract values from Python dict using .get() method with safe defaults
                val progressPercent = safeGetFloat(stateObj, "progress_percent", 0f)
                val estimatedE = safeGetDouble(stateObj, "estimated_e", 0.0)
                val trialsCompleted = safeGetInt(stateObj, "trials_completed", 0)
                val totalCount = safeGetLong(stateObj, "total_count", 0L)
                val numTrials = safeGetInt(stateObj, "num_trials", 0)
                
                // Also try to get common alternative field names
                val count = safeGetInt(stateObj, "count", trialsCompleted)
                val total = safeGetLong(stateObj, "total", totalCount)
                
                // Build custom data map for any additional fields
                val customData = mutableMapOf<String, Any>()
                try {
                    val keys = stateObj.callAttr("keys")
                    val keysList = keys?.asList() ?: emptyList<PyObject>()
                    for (key in keysList) {
                        val keyStr = key.toString()
                        if (keyStr !in listOf("progress_percent", "estimated_e", "trials_completed", 
                                              "total_count", "num_trials", "count", "total")) {
                            val value = stateObj.callAttr("get", keyStr)
                            if (value != null && value.toString() != "None") {
                                customData[keyStr] = convertPyObjectToJava(value)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore errors when extracting custom data
                }
                
                handler.updateState(
                    CheckpointHandler.CheckpointState(
                        trialsCompleted = if (count > trialsCompleted) count else trialsCompleted,
                        totalCount = if (total > totalCount) total else totalCount,
                        numTrials = numTrials,
                        progressPercent = progressPercent,
                        estimatedE = estimatedE,
                        customData = if (customData.isNotEmpty()) customData else null
                    )
                )
                Log.d(TAG, "Checkpoint state polled: progress=$progressPercent%, trials=$trialsCompleted/$numTrials, custom=${customData.keys}")
            } else {
                Log.d(TAG, "No checkpoint state found in Python builtins (state is empty or null)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error polling checkpoint state: ${e.message}")
        }
    }
    
    /** Safely extract float from Python dict */
    private fun safeGetFloat(dict: PyObject, key: String, default: Float): Float {
        return try {
            dict.callAttr("get", key, default)?.toJava(Number::class.java)?.toFloat() ?: default
        } catch (e: Exception) { default }
    }
    
    /** Safely extract double from Python dict */
    private fun safeGetDouble(dict: PyObject, key: String, default: Double): Double {
        return try {
            dict.callAttr("get", key, default)?.toJava(Number::class.java)?.toDouble() ?: default
        } catch (e: Exception) { default }
    }
    
    /** Safely extract int from Python dict */
    private fun safeGetInt(dict: PyObject, key: String, default: Int): Int {
        return try {
            dict.callAttr("get", key, default)?.toJava(Number::class.java)?.toInt() ?: default
        } catch (e: Exception) { default }
    }
    
    /** Safely extract long from Python dict */
    private fun safeGetLong(dict: PyObject, key: String, default: Long): Long {
        return try {
            dict.callAttr("get", key, default)?.toJava(Number::class.java)?.toLong() ?: default
        } catch (e: Exception) { default }
    }
    
    /** Convert PyObject to Java object */
    private fun convertPyObjectToJava(obj: PyObject): Any {
        // Try numeric types first
        try {
            val numVal = obj.toJava(Number::class.java)
            if (numVal != null) return numVal
        } catch (_: Exception) { }
        
        // Try boolean
        try {
            val boolVal = obj.toJava(Boolean::class.java)
            if (boolVal != null) return boolVal
        } catch (_: Exception) { }
        
        // Fallback to string
        return obj.toString()
    }
    
    /**
     * Get Python environment info
     */
    fun getEnvironmentInfo(): Map<String, Any> {
        return try {
            val python = pythonInstance
            val sysModule = python?.getModule("sys")
            val versionPyObject = sysModule?.get("version")
            val pythonVersion = versionPyObject?.toString() ?: "unknown"
            
            mapOf<String, Any>(
                "initialized" to isInitialized.get(),
                "python_version" to pythonVersion,
                "platform" to "android",
                "worker_id" to (workerIdManager.getCurrentWorkerId() ?: "unknown")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting environment info", e)
            mapOf<String, Any>(
                "initialized" to false,
                "error" to (e.message ?: "Unknown error")
            )
        }
    }
    
    /**
     * Decode base64 string
     */
    private fun decodeBase64(encoded: String): String {
        return try {
            val decodedPyObject = base64Module?.callAttr("b64decode", encoded)
            val decodedBytes = decodedPyObject?.toJava(ByteArray::class.java) ?: byteArrayOf()
            String(decodedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64", e)
            encoded // Return original if decoding fails
        }
    }
    
    /**
     * Check if string is base64 encoded
     */
    private fun isBase64Encoded(str: String): Boolean {
        return try {
            // Base64 strings should only contain valid base64 characters
            val base64Pattern = Regex("^[A-Za-z0-9+/]*={0,2}$")
            base64Pattern.matches(str) && str.length % 4 == 0 && str.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Load mobile-compatible sentiment worker from Python file
     */
    private fun loadMobileSentimentWorker(): String {
        return try {
            // Load the sentiment_worker module from Python sources
            val sentimentModule = pythonInstance?.getModule("sentiment_worker")
            if (sentimentModule != null) {
                Log.d(TAG, "✅ Loaded sentiment_worker module from Python sources")
                // Read the module source code
                val inspect = pythonInstance?.getModule("inspect")
                val source = inspect?.callAttr("getsource", sentimentModule)?.toString()
                source ?: getDefaultMobileSentimentWorker()
            } else {
                Log.w(TAG, "sentiment_worker module not found, using default")
                getDefaultMobileSentimentWorker()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load sentiment_worker module: ${e.message}, using default")
            getDefaultMobileSentimentWorker()
        }
    }
    
    /**
     * Get default mobile sentiment worker code
     * Returns native Python dictionaries instead of JSON strings
     */
    private fun getDefaultMobileSentimentWorker(): String {
        return """
def sentiment_worker_pytorch(text):
    import time
    
    start = time.time()
    try:
        from textblob import TextBlob
        blob = TextBlob(text)
        polarity = blob.sentiment.polarity
        predicted_class = 1 if polarity > 0 else 0
        confidence = abs(polarity)
        latency_ms = int((time.time() - start) * 1000)
        
        result = {
            "text": text[:50] + "..." if len(text) > 50 else text,
            "sentiment": round(polarity, 3),
            "confidence": round(confidence, 3),
            "predicted_class": predicted_class,
            "class_name": "positive" if predicted_class == 1 else "negative",
            "neg_probability": round(max(0, -polarity), 3),
            "pos_probability": round(max(0, polarity), 3),
            "model": "TextBlob_Mobile",
            "latency_ms": latency_ms,
            "status": "success"
        }
        return result
    except Exception as e:
        latency_ms = int((time.time() - start) * 1000)
        return {
            "text": text[:50] + "..." if len(text) > 50 else text,
            "sentiment": 0.0,
            "confidence": 0.0,
            "latency_ms": latency_ms,
            "status": "error",
            "error": str(e),
            "model": "TextBlob_Mobile"
        }
        """.trimIndent()
    }
    
    /**
     * Get mobile-compatible sentiment analysis function
     * Replaces transformers-based functions with TextBlob
     * Returns native Python dictionaries for efficient communication
     */
    private fun getMobileCompatibleSentimentFunction(): String {
        return """
def sentiment_analysis_worker(message_data):
    '''
    Mobile-compatible sentiment analysis worker using TextBlob.
    Replaces transformers library which is not available on Android.
    
    Args:
        message_data: Dictionary containing 'text' and 'message_id'
    
    Returns:
        dict: Native Python dictionary with sentiment analysis results
    '''
    import time
    
    start_time = time.time()
    
    try:
        # Extract data
        text = message_data.get('text', '')
        message_id = message_data.get('message_id', 0)
        
        # Try TextBlob first
        try:
            from textblob import TextBlob
            
            blob = TextBlob(text)
            polarity = blob.sentiment.polarity  # Range: -1.0 (negative) to 1.0 (positive)
            subjectivity = blob.sentiment.subjectivity  # Range: 0.0 (objective) to 1.0 (subjective)
            model_used = "TextBlob_Mobile"
            
        except ImportError:
            # Fallback to VADER sentiment if TextBlob not available
            try:
                from vaderSentiment.vaderSentiment import SentimentIntensityAnalyzer
                
                analyzer = SentimentIntensityAnalyzer()
                scores = analyzer.polarity_scores(text)
                
                # VADER returns compound score (-1 to 1)
                polarity = scores['compound']
                subjectivity = 0.5  # VADER doesn't provide subjectivity
                model_used = "VADER_Mobile"
                
            except ImportError as e:
                # If both fail, return error
                raise ImportError("Neither TextBlob nor VADER sentiment libraries are available")
        
        # Normalize polarity to 0-1 range like transformers output
        sentiment_score = (polarity + 1.0) / 2.0  # Convert -1..1 to 0..1
        
        # Determine sentiment label
        if polarity > 0.1:
            sentiment_label = "POSITIVE"
        elif polarity < -0.1:
            sentiment_label = "NEGATIVE"
        else:
            sentiment_label = "NEUTRAL"
        
        # Calculate confidence based on absolute polarity
        confidence = abs(polarity)
        
        # Calculate positive and negative signals
        positive_signals = max(0.0, polarity)
        negative_signals = abs(min(0.0, polarity))
        
        # Generate advice based on sentiment
        advice = []
        if sentiment_label == "NEGATIVE":
            if negative_signals > 0.7:
                advice.append("High priority: Customer is very dissatisfied")
            advice.append("Recommend immediate follow-up")
            advice.append("Consider escalation to senior support")
        elif sentiment_label == "POSITIVE":
            advice.append("Customer is satisfied")
            advice.append("Standard response time acceptable")
        else:
            advice.append("Neutral sentiment detected")
            advice.append("Monitor for further interaction")
        
        # Calculate latency
        latency_ms = int((time.time() - start_time) * 1000)
        
        # Create text preview
        text_preview = text[:100] + "..." if len(text) > 100 else text
        
        result = {
            "message_id": message_id,
            "text_preview": text_preview,
            "sentiment_score": round(sentiment_score, 3),
            "sentiment_label": sentiment_label,
            "confidence": round(confidence, 3),
            "positive_signals": round(positive_signals, 3),
            "negative_signals": round(negative_signals, 3),
            "advice": advice,
            "latency_ms": latency_ms,
            "status": "success",
            "model": model_used
        }
        
        print(f"[Worker] Sentiment: {sentiment_label} | Score: {sentiment_score:.3f} | Model: {model_used}")
        return result
        
    except ImportError as e:
        latency_ms = int((time.time() - start_time) * 1000)
        print(f"[Worker Error] {str(e)}")
        
        return {
            "message_id": message_data.get('message_id', 0),
            "text_preview": message_data.get('text', '')[:100] + "...",
            "sentiment_score": 0.5,
            "sentiment_label": "ERROR",
            "confidence": 0.0,
            "positive_signals": 0.0,
            "negative_signals": 0.0,
            "advice": [f"Analysis failed: {str(e)}"],
            "latency_ms": latency_ms,
            "status": "failed",
            "error": str(e),
            "model": "None"
        }
        
    except Exception as e:
        import traceback
        traceback.print_exc()
        
        latency_ms = int((time.time() - start_time) * 1000)
        print(f"[Worker Error] {str(e)}")
        
        return {
            "message_id": message_data.get('message_id', 0),
            "text_preview": message_data.get('text', '')[:100] + "...",
            "sentiment_score": 0.5,
            "sentiment_label": "ERROR",
            "confidence": 0.0,
            "positive_signals": 0.0,
            "negative_signals": 0.0,
            "advice": [f"Analysis failed: {str(e)}"],
            "latency_ms": latency_ms,
            "status": "failed",
            "error": str(e),
            "model": "None"
        }
        """.trimIndent()
    }

    /**
     * Extract function name from Python code
     */
    private fun extractFunctionName(code: String): String? {
        return try {
            val defPattern = Regex("def\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(")
            val match = defPattern.find(code)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting function name", e)
            null
        }
    }
    
    /**
     * Create execution context for Python code
     */
    private fun createExecutionContext(workerId: String): Map<String, Any> {
        return mapOf(
            "worker_id" to workerId,
            "platform" to "android",
            "context" to "mobile_worker"
        )
    }
    
    /**
     * Instrument Python code to automatically update checkpoint state AND support resumption.
     * Since sys.settrace() doesn't work on Android/Chaquopy, we inject explicit
     * checkpoint callback calls at strategic points in the code (after variable updates
     * that update progress_percent or similar checkpoint variables).
     * 
     * For resumption support, we also inject code at the start of the function to:
     * 1. Check if builtins._checkpoint_state exists with _is_resumed=True
     * 2. If so, restore variables from checkpoint and adjust loop starting point
     * 
     * @param funcCode The original Python function code
     * @param checkpointStateVars Variables to capture for checkpointing
     * @param isResume Whether this is a resume execution (inject resume logic)
     * @return Instrumented Python code with checkpoint callbacks and optional resume logic
     */
    private fun instrumentCodeForCheckpointing(
        funcCode: String, 
        checkpointStateVars: List<String>,
        isResume: Boolean = false
    ): String {
        if (checkpointStateVars.isEmpty()) {
            return funcCode
        }
        
        try {
            val lines = funcCode.lines().toMutableList()
            val instrumentedLines = mutableListOf<String>()
            
            var foundProgressUpdate = false
            var addedImport = false
            var addedResumeLogic = false
            var inDocstring = false
            var docstringDelimiter = ""
            var passedDocstring = false
            var functionBodyIndent = "    "
            
            lineLoop@ for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                
                // Detect function definition
                if (trimmed.startsWith("def ") && trimmed.contains("(")) {
                    instrumentedLines.add(line)
                    inDocstring = false
                    passedDocstring = false
                    addedImport = false
                    addedResumeLogic = false
                    continue
                }
                
                // Handle multi-line docstrings
                if (!passedDocstring) {
                    // Check for docstring start
                    if (!inDocstring && (trimmed.startsWith("\"\"\"") || trimmed.startsWith("'''"))) {
                        docstringDelimiter = if (trimmed.startsWith("\"\"\"")) "\"\"\"" else "'''"
                        inDocstring = true
                        instrumentedLines.add(line)
                        
                        // Check if docstring ends on same line (single-line docstring)
                        val afterDelimiter = trimmed.substring(3)
                        if (afterDelimiter.contains(docstringDelimiter)) {
                            inDocstring = false
                            passedDocstring = true
                        }
                        continue
                    }
                    
                    // Check for docstring end in multi-line docstring
                    if (inDocstring && trimmed.contains(docstringDelimiter)) {
                        inDocstring = false
                        passedDocstring = true
                        instrumentedLines.add(line)
                        continue
                    }
                    
                    // Still in docstring
                    if (inDocstring) {
                        instrumentedLines.add(line)
                        continue
                    }
                }
                
                // Past docstring - add import and resume logic if needed
                if (!addedImport && passedDocstring && !line.isBlank()) {
                    functionBodyIndent = line.takeWhile { it == ' ' || it == '\t' }
                    if (functionBodyIndent.isEmpty()) functionBodyIndent = "    "
                    
                    // Add import builtins before this line
                    if (!funcCode.contains("import builtins")) {
                        instrumentedLines.add("${functionBodyIndent}import builtins  # Injected for checkpoint support")
                    }
                    addedImport = true
                    
                    // Add resume logic if this is a resume execution
                    if (isResume && !addedResumeLogic) {
                        addResumeLogic(instrumentedLines, functionBodyIndent, checkpointStateVars)
                        addedResumeLogic = true
                    }
                }
                
                // If no docstring and first non-blank line, add import
                if (!addedImport && !passedDocstring && !inDocstring && !line.isBlank() && 
                    !trimmed.startsWith("\"\"\"") && !trimmed.startsWith("'''")) {
                    passedDocstring = true  // No docstring case
                    functionBodyIndent = line.takeWhile { it == ' ' || it == '\t' }
                    if (functionBodyIndent.isEmpty()) functionBodyIndent = "    "
                    
                    if (!funcCode.contains("import builtins")) {
                        instrumentedLines.add("${functionBodyIndent}import builtins  # Injected for checkpoint support")
                    }
                    addedImport = true
                    
                    // Add resume logic if this is a resume execution
                    if (isResume && !addedResumeLogic) {
                        addResumeLogic(instrumentedLines, functionBodyIndent, checkpointStateVars)
                        addedResumeLogic = true
                    }
                }
                
                // When resuming, skip original initializations of checkpoint variables
                // These are restored from checkpoint state, so we comment them out
                if (isResume && addedResumeLogic) {
                    // Check if this line is initializing a checkpoint variable with a literal value
                    // Match patterns like: "varname = 0", "varname = 0.0", "varname = []", etc.
                    val initMatch = Regex("^(\\s*)(\\w+)\\s*=\\s*(.+)$").find(line)
                    if (initMatch != null) {
                        val varName = initMatch.groupValues[2]
                        val value = initMatch.groupValues[3].trim()
                        val lineIndent = initMatch.groupValues[1]
                        
                        // Check if this is a checkpoint variable being initialized to a default value
                        if (varName in checkpointStateVars && isDefaultInitialization(value)) {
                            // Comment out the original and add conditional initialization using _is_resuming flag
                            // This flag is set by our injected resume logic
                            instrumentedLines.add("${lineIndent}# Original: $varName = $value (skipped during resume)")
                            instrumentedLines.add("${lineIndent}if not _is_resuming:")
                            instrumentedLines.add("${lineIndent}    $varName = $value")
                            Log.d(TAG, "Made checkpoint var initialization conditional: $varName = $value")
                            // Don't add the original line, continue to next
                            continue@lineLoop
                        }
                    }
                }
                
                // Modify main loop to start from _resume_start_index when resuming
                // Look for patterns like "for i in range(num_trials)" or "for i in range(n)"
                if (isResume && trimmed.matches(Regex("for\\s+\\w+\\s+in\\s+range\\s*\\(\\s*\\w+\\s*\\)\\s*:"))) {
                    // Extract the loop variable and range argument
                    val forMatch = Regex("for\\s+(\\w+)\\s+in\\s+range\\s*\\(\\s*(\\w+)\\s*\\)").find(trimmed)
                    if (forMatch != null) {
                        val loopVar = forMatch.groupValues[1]
                        val rangeArg = forMatch.groupValues[2]
                        val indent = line.takeWhile { it == ' ' || it == '\t' }
                        // Modify to use _resume_start_index as start
                        val modifiedLine = "${indent}for $loopVar in range(_resume_start_index, $rangeArg):"
                        instrumentedLines.add(modifiedLine)
                        Log.d(TAG, "Modified loop to resume from _resume_start_index: $trimmed -> for $loopVar in range(_resume_start_index, $rangeArg)")
                        // Skip adding the original line since we added the modified one
                        continue@lineLoop
                    } else {
                        instrumentedLines.add(line)
                    }
                } else {
                    instrumentedLines.add(line)
                }
                
                // Check if this line updates progress_percent - that's the key variable to trigger checkpoint
                if (!trimmed.startsWith("#") && 
                    line.contains("progress_percent") && 
                    line.contains("=") && 
                    !line.contains("==") && 
                    !line.contains("_update_checkpoint_state") &&
                    !line.contains("_checkpoint_callback")) {
                    
                    foundProgressUpdate = true
                    
                    // Get the indentation of this line
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    
                    // Build checkpoint callback with all state vars
                    // Each line must have exactly the same base indentation
                    val callbackArgs = checkpointStateVars.joinToString(", ") { "$it=$it" }
                    val callbackLines = listOf(
                        "${indent}try:",
                        "${indent}    if hasattr(builtins, '_update_checkpoint_state'):",
                        "${indent}        builtins._update_checkpoint_state($callbackArgs)",
                        "${indent}except: pass"
                    )
                    
                    // Inject the callback after the progress update line
                    val nextLine = lines.getOrNull(index + 1)?.trim() ?: ""
                    if (!nextLine.contains("_update_checkpoint_state") && !nextLine.contains("_checkpoint_callback")) {
                        callbackLines.forEach { instrumentedLines.add(it) }
                        Log.d(TAG, "Injected checkpoint callback after line ${index + 1}: progress_percent update")
                    }
                }
            }
            
            if (!foundProgressUpdate) {
                Log.d(TAG, "No progress_percent updates found in code, checkpoint state will need manual updates")
            }
            
            return instrumentedLines.joinToString("\n")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to instrument code for checkpointing: ${e.message}")
            return funcCode // Return original if instrumentation fails
        }
    }
    
    /**
     * Add resume logic to instrumented code.
     * This injects Python code that:
     * 1. Checks if builtins._checkpoint_state exists with _is_resumed=True
     * 2. Restores checkpoint variables from the saved state
     * 3. Sets _resume_start_index for loops to continue from correct position
     */
    private fun addResumeLogic(
        instrumentedLines: MutableList<String>,
        indent: String,
        checkpointStateVars: List<String>
    ) {
        // Add resume detection and variable restoration code
        instrumentedLines.add("${indent}# === INJECTED RESUME LOGIC (Android Worker) ===")
        instrumentedLines.add("${indent}_resume_start_index = 0")
        instrumentedLines.add("${indent}_is_resuming = False")
        
        // Initialize checkpoint variables to their default values first
        // These will be overwritten if we're resuming
        for (varName in checkpointStateVars) {
            // Don't initialize here - let the original code do it (or our conditional)
        }
        
        // Check if we're resuming and restore state
        instrumentedLines.add("${indent}try:")
        instrumentedLines.add("${indent}    import builtins as _resume_builtins")
        instrumentedLines.add("${indent}    _cp_state = getattr(_resume_builtins, '_checkpoint_state', None)")
        instrumentedLines.add("${indent}    print(f'[Android Worker] Resume check: _cp_state exists = {_cp_state is not None}')")
        instrumentedLines.add("${indent}    if _cp_state is not None:")
        instrumentedLines.add("${indent}        print(f'[Android Worker] _cp_state type = {type(_cp_state)}, keys = {list(_cp_state.keys()) if hasattr(_cp_state, \"keys\") else \"N/A\"}')")
        instrumentedLines.add("${indent}        _is_resumed_flag = _cp_state.get('_is_resumed', False) if hasattr(_cp_state, 'get') else False")
        instrumentedLines.add("${indent}        print(f'[Android Worker] _is_resumed flag = {_is_resumed_flag} (type: {type(_is_resumed_flag)})')")
        instrumentedLines.add("${indent}        if _is_resumed_flag:")
        instrumentedLines.add("${indent}            _is_resuming = True")
        instrumentedLines.add("${indent}            print('[Android Worker] ✅ Resuming from checkpoint - restoring state...')")
        
        // Restore each checkpoint variable
        for (varName in checkpointStateVars) {
            instrumentedLines.add("${indent}            if '$varName' in _cp_state:")
            instrumentedLines.add("${indent}                $varName = _cp_state['$varName']")
            instrumentedLines.add("${indent}                print(f'[Android Worker] ✅ Restored $varName = {$varName}')")
        }
        
        // Set resume start index based on trials_completed
        instrumentedLines.add("${indent}            # Set loop start index for resumption")
        instrumentedLines.add("${indent}            if 'trials_completed' in _cp_state:")
        instrumentedLines.add("${indent}                _resume_start_index = int(_cp_state['trials_completed'])")
        instrumentedLines.add("${indent}                print(f'[Android Worker] ✅ Will resume from iteration {_resume_start_index}')")
        instrumentedLines.add("${indent}        else:")
        instrumentedLines.add("${indent}            print('[Android Worker] Not resuming - _is_resumed flag is False or missing')")
        instrumentedLines.add("${indent}    else:")
        instrumentedLines.add("${indent}        print('[Android Worker] Not resuming - no checkpoint state found')")
        instrumentedLines.add("${indent}except Exception as _resume_err:")
        instrumentedLines.add("${indent}    print(f'[Android Worker] Resume logic error: {_resume_err}')")
        instrumentedLines.add("${indent}# === END RESUME LOGIC ===")
        instrumentedLines.add("")
        
        Log.d(TAG, "Injected resume logic for vars: $checkpointStateVars")
    }
    
    /**
     * Check if a value expression represents a default initialization.
     * These are values that should be skipped during resume since they would
     * overwrite the restored checkpoint values.
     */
    private fun isDefaultInitialization(value: String): Boolean {
        // Remove whitespace for comparison
        val v = value.trim()
        
        // Check for common default values
        return when {
            // Numeric zero
            v == "0" || v == "0.0" || v == "0.00" -> true
            // Empty collections
            v == "[]" || v == "{}" || v == "()" -> true
            // Empty string
            v == "\"\"" || v == "''" -> true
            // None
            v == "None" -> true
            // Boolean False
            v == "False" -> true
            // Negative number patterns (less common but possible defaults)
            v.matches(Regex("^-?\\d+(\\.\\d+)?$")) && v.toDoubleOrNull()?.let { it == 0.0 } == true -> true
            // List/dict/set comprehensions or function calls are NOT defaults
            v.contains("(") || v.contains("[") && !v.startsWith("[") -> false
            // Otherwise not a default
            else -> false
        }
    }
    
    /**
     * Execute function code using desktop worker approach
     * Matches the desktop worker's _execute_task method
     * 
     * On Android, uses code instrumentation for checkpoint capture since sys.settrace() doesn't work.
     * 
     * @param funcCode The Python function code to execute
     * @param taskArgs The arguments to pass to the function
     * @param isResume Whether this is a resume execution (will inject resume logic)
     */
    private fun executeFunctionCode(funcCode: String, taskArgs: PyObject?, isResume: Boolean = false): Any? {
        // Extract function name for trace
        val funcName = extractFunctionName(funcCode)
        val handler = checkpointHandlerRef.get()
        val hasCheckpointHandler = handler != null
        
        return try {
            Log.d(TAG, "🔄 Executing task... | worker_runtime=android | func=$funcName | checkpoint=$hasCheckpointHandler | resume=$isResume")
            
            // Instrument code for checkpointing if handler is set
            val codeToExecute = if (hasCheckpointHandler && funcName != null) {
                val checkpointStateVars = handler?.getCheckpointStateVars() ?: emptyList()
                configureCheckpointTrace(funcName, checkpointStateVars)
                
                // Instrument the code to inject checkpoint callbacks (and resume logic if resuming)
                if (checkpointStateVars.isNotEmpty()) {
                    val instrumented = instrumentCodeForCheckpointing(funcCode, checkpointStateVars, isResume)
                    Log.d(TAG, "Code instrumented for checkpointing with vars: $checkpointStateVars, resume=$isResume")
                    // Log a portion of the instrumented code to verify injection
                    val injectionMarker = "_update_checkpoint_state"
                    if (instrumented.contains(injectionMarker)) {
                        Log.i(TAG, "✅ Checkpoint callback injection successful")
                    } else {
                        Log.w(TAG, "⚠️ No checkpoint callback was injected - state capture may not work")
                    }
                    if (isResume && instrumented.contains("_resume_start_index")) {
                        Log.i(TAG, "✅ Resume logic injection successful")
                    }
                    instrumented
                } else {
                    Log.w(TAG, "⚠️ No checkpoint state vars configured - using original code")
                    funcCode
                }
            } else {
                funcCode
            }
            
            // Deserialize the function (equivalent to deserialize_function in desktop worker)
            val func = deserializeFunction(codeToExecute)
            
            // Convert taskArgs to Java list for processing with proper error handling
            val argsList = convertPyObjectToList(taskArgs)
            
            Log.d(TAG, "Task args: $argsList")
            
            // Execute the function with the provided arguments (matching desktop worker logic)
            val result = executeFunctionWithArgs(func, argsList)
            
            // Disable checkpoint capture after execution
            if (hasCheckpointHandler) {
                disableCheckpointTrace()
            }
            
            Log.d(TAG, "✅ Task completed successfully")
            
            // result is already a Java object from executeFunctionWithArgs
            Log.d(TAG, "Function result: $result")
            result
            
        } catch (e: Exception) {
            // Disable trace on error
            if (hasCheckpointHandler) {
                disableCheckpointTrace()
            }
            val errorMsg = "Task execution failed: ${e.message}"
            Log.e(TAG, "❌ Task failed: $errorMsg")
            throw Exception(errorMsg)
        }
    }

    /**
     * Execute function with arguments and proper error handling
     */
    private fun executeFunctionWithArgs(func: PyObject, argsList: List<*>?): Any? {
        return try {
            val pyResult = when {
                argsList == null || argsList.isEmpty() -> {
                    // No arguments
                    Log.d(TAG, "Calling function with no arguments")
                    func.call()
                }
                argsList.size == 1 -> {
                    // Single argument
                    Log.d(TAG, "Calling function with single argument: ${argsList[0]}")
                    func.call(argsList[0])
                }
                argsList.size == 2 && argsList[1] is Map<*, *> -> {
                    // Function with args and kwargs
                    Log.d(TAG, "Calling function with args and kwargs")
                    val args = argsList[0] as? List<*>
                    val kwargs = argsList[1] as? Map<*, *>
                    if (args != null && kwargs != null) {
                        // Convert kwargs to Python dict and call with *args, **kwargs
                        val kwargsDict = pythonInstance?.getModule("builtins")?.callAttr("dict")
                        kwargs.forEach { (key, value) ->
                            kwargsDict?.put(key.toString(), value)
                        }
                        func.call(*args.toTypedArray(), kwargsDict)
                    } else {
                        Log.w(TAG, "Failed to parse args/kwargs, calling with all arguments")
                        func.call(*argsList.toTypedArray())
                    }
                }
                else -> {
                    // Multiple arguments
                    Log.d(TAG, "Calling function with ${argsList.size} arguments")
                    func.call(*argsList.toTypedArray())
                }
            }
            
            // Convert PyObject result to proper Java type
            if (pyResult == null) {
                return "null"
            }
            
            // Check if it's a Python dict and convert to JSON string
            val builtins = pythonInstance?.getModule("builtins")
            val dictType = builtins?.get("dict")
            val isDict = builtins?.callAttr("isinstance", pyResult, dictType)?.toJava(Boolean::class.java) == true
            
            if (isDict) {
                // Convert dict to JSON string using Python's json module
                val jsonStr = jsonModule?.callAttr("dumps", pyResult)?.toString()
                Log.d(TAG, "Converted dict result to JSON: $jsonStr")
                return jsonStr ?: "{}"
            }
            
            // Check if it's a string
            val strType = builtins?.get("str")
            val isStr = builtins?.callAttr("isinstance", pyResult, strType)?.toJava(Boolean::class.java) == true
            if (isStr) {
                return pyResult.toString()
            }
            
            // For other types, try to convert via JSON
            try {
                val jsonStr = jsonModule?.callAttr("dumps", pyResult)?.toString()
                if (jsonStr != null) {
                    return jsonStr
                }
            } catch (e: Exception) {
                Log.d(TAG, "JSON conversion failed, using toString: ${e.message}")
            }
            
            // Fallback to string representation
            pyResult.toString()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error executing function with arguments", e)
            // Try to provide more specific error information
            val errorDetails = when {
                e.message?.contains("TypeError") == true -> "Type error in function call - check argument types"
                e.message?.contains("AttributeError") == true -> "Attribute error - function may not exist or be callable"
                e.message?.contains("NameError") == true -> "Name error - function or variable not found"
                else -> "Unknown error in function execution"
            }
            throw Exception("$errorDetails: ${e.message}")
        }
    }
    
    /**
     * Convert PyObject to Java List with proper error handling
     * Handles the case where Python list cannot be directly converted to Java List
     */
    private fun convertPyObjectToList(taskArgs: PyObject?): List<*>? {
        return try {
            if (taskArgs == null) {
                return emptyList<Any>()
            }
            
            Log.d(TAG, "Converting PyObject to Java List: $taskArgs")

            // If the incoming object is a dict, convert it to a Java Map so values aren't dropped.
            try {
                val builtins = pythonInstance?.getModule("builtins")
                val dictType = builtins?.get("dict")
                val isDict = builtins?.callAttr("isinstance", taskArgs, dictType)?.toJava(Boolean::class.java) == true
                if (isDict) {
                    val asJavaMap = taskArgs.toJava(Map::class.java)
                    Log.d(TAG, "Detected dict; returning map inside list to preserve key/values")
                    return listOf(asJavaMap)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Dict detection failed, falling back to list handling: ${e.message}")
            }
            
            // First, try direct conversion using asList()
            try {
                val directConversion = taskArgs.asList()
                if (directConversion != null) {
                    Log.d(TAG, "Direct conversion successful: $directConversion")
                    return directConversion
                }
            } catch (e: Exception) {
                Log.d(TAG, "Direct conversion failed, trying alternative method: ${e.message}")
            }
            
            // Alternative method: Convert to Python list first, then to Java
            try {
                val pythonList = builtinsModule?.callAttr("list", taskArgs)
                if (pythonList != null) {
                    val javaList = pythonList.asList()
                    Log.d(TAG, "Alternative conversion successful: $javaList")
                    return javaList
                }
            } catch (e: Exception) {
                Log.d(TAG, "Alternative conversion failed: ${e.message}")
            }
            
            // Last resort: Manual conversion by iterating through the PyObject
            try {
                val resultList = mutableListOf<Any?>()
                val iter = taskArgs.callAttr("__iter__")
                var hasNext = true
                
                while (hasNext) {
                    try {
                        val nextItem = iter.callAttr("__next__")
                        val javaItem = nextItem.toJava(Any::class.java)
                        resultList.add(javaItem)
                    } catch (e: Exception) {
                        // StopIteration exception means we've reached the end
                        hasNext = false
                    }
                }
                
                Log.d(TAG, "Manual conversion successful: $resultList")
                return resultList
                
            } catch (e: Exception) {
                Log.e(TAG, "Manual conversion failed: ${e.message}")
            }
            
            // If all methods fail, wrap the PyObject in a list
            Log.w(TAG, "All conversion methods failed, wrapping PyObject in list")
            listOf(taskArgs.toJava(Any::class.java))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert PyObject to List: ${e.message}")
            // Return empty list as fallback
            emptyList<Any>()
        }
    }
    
    /**
     * Deserialize function from code (equivalent to deserialize_function in desktop worker)
     * Matches the exact desktop worker implementation with improved error handling
     */
    private fun deserializeFunction(funcCode: String): PyObject {
        return try {
            Log.d(TAG, "Deserializing function: ${funcCode.take(200)}...")
            
            // Create a local namespace for the exec (equivalent to local_vars = {})
            val localVars = pythonInstance?.getModule("builtins")?.callAttr("dict")
            
            // Create global namespace (empty dict)
            val globalVars = pythonInstance?.getModule("builtins")?.callAttr("dict")
            
            // Execute the function code in the local namespace (equivalent to exec(func_code, {}, local_vars))
            builtinsModule?.callAttr("exec", funcCode, globalVars, localVars)
            
            // Find the function object in local_vars (equivalent to finding types.FunctionType)
            val typesModule = pythonInstance?.getModule("types")
            val functionType = typesModule?.get("FunctionType")
            
            var func: PyObject? = null
            
            // Try to find function by name first (more reliable)
            val functionName = extractFunctionName(funcCode)
            if (functionName != null) {
                val namedFunc = localVars?.get(functionName)
                if (namedFunc != null) {
                    val isInstance = functionType?.callAttr("__instancecheck__", namedFunc)
                    if (isInstance?.toJava(Boolean::class.java) == true) {
                        func = namedFunc
                        Log.d(TAG, "Found function by name: $functionName")
                    }
                }
            }
            
            // If not found by name, iterate through all values
            if (func == null) {
                Log.d(TAG, "Function not found by name, searching through all values...")
                
                // Convert dict_values to list first (equivalent to list(local_vars.values()))
                val values = localVars?.callAttr("values")
                val valuesList = builtinsModule?.callAttr("list", values)
                val valuesJavaList = valuesList?.asList()
                
                valuesJavaList?.forEach { value ->
                    val pyValue = value as? PyObject
                    if (pyValue != null) {
                        // Check if the value is an instance of FunctionType
                        val isInstance = functionType?.callAttr("__instancecheck__", pyValue)
                        if (isInstance?.toJava(Boolean::class.java) == true) {
                            func = pyValue
                            Log.d(TAG, "Found function by type checking")
                            return@forEach
                        }
                    }
                }
            }
            
            if (func == null) {
                // Log all available keys for debugging
                val keys = localVars?.callAttr("keys")
                val keysList = builtinsModule?.callAttr("list", keys)
                val keysJavaList = keysList?.asList()
                Log.e(TAG, "Available keys in local_vars: $keysJavaList")
                throw Exception("No function could be deserialized from code string. Available keys: $keysJavaList")
            }
            
            Log.d(TAG, "Found function: $func")
            func
            
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing function", e)
            throw e
        }
    }
    
    /**
     * Execute Python code with given arguments and context
     */
    private fun executePythonCode(code: String, args: PyObject?, context: Map<String, Any>): Any? {
        return try {
            // Create a new Python scope for execution
            val mainModule = pythonInstance?.getModule("__main__")
            val scope = mainModule?.get("__dict__")
            
            // Create a Python dictionary for context
            val contextDict = pythonInstance?.getModule("builtins")?.callAttr("dict")
            
            // Add context variables to the Python dictionary
            context.forEach { (key, value) ->
                contextDict?.put(key, value)
            }
            
            // Add context dictionary to scope
            scope?.put("context", contextDict)
            
            // Add individual context variables to scope for easier access
            context.forEach { (key, value) ->
                scope?.put(key, value)
            }
            
            // Add arguments if provided
            if (args != null) {
                scope?.put("args", args)
            }
            
            // Execute the code
            builtinsModule?.callAttr("exec", code, scope)
            
            // Try to get the result from the scope
            val result = scope?.get("result")
            if (result != null) {
                result.toJava(Any::class.java)
            } else {
                val altResult = scope?.get("__result__")
                if (altResult != null) {
                    altResult.toJava(Any::class.java)
                } else {
                    "No result returned"
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Python code", e)
            throw e
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            Log.d(TAG, "Cleaning up Python executor...")
            isInitialized.set(false)
            pythonInstance = null
            builtinsModule = null
            jsonModule = null
            base64Module = null
            Log.d(TAG, "✅ Python executor cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
