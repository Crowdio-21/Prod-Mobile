package com.example.mcc_phase3.execution

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import com.example.mcc_phase3.data.WorkerIdManager
import com.example.mcc_phase3.checkpoint.CheckpointHandler
import com.example.mcc_phase3.utils.EventLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.example.mcc_phase3.execution.ImagePickerManager

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
    
    // The globals dictionary of the currently-executing task code.
    // pause/resume/kill MUST target this dict so the flags are visible to the running task.
    private val execGlobalsRef = AtomicReference<PyObject?>(null)
    
    // Progress callback for real-time progress updates (independent of checkpointing)
    private var progressCallback: ((Float) -> Unit)? = null
    
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
     * Set the progress callback for real-time progress updates
     * Python code can call builtins._progress_callback(50.0) to report 50% progress
     */
    fun setProgressCallback(callback: ((Float) -> Unit)?) {
        progressCallback = callback
        Log.d(TAG, "Progress callback ${if (callback != null) "set" else "cleared"}")
    }
    
    /**
     * Update checkpoint state with generic state data.
     * Can be called from Python via callback or from Kotlin code.
     * 
     * @param stateData Generic map of state variables to checkpoint
     */
    fun updateCheckpointState(stateData: Map<String, Any>) {
        checkpointHandlerRef.get()?.updateState(
            CheckpointHandler.CheckpointState.fromMap(stateData)
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
                Log.d(TAG, "Python environment initialized successfully")
                true
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Python environment", e)
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
                
                // Set up progress callback for real-time progress reporting
                setupProgressCallback()

                // If the function processes device images, prompt the user to select
                // images from the gallery and inject their paths before execution.
                if (requiresImageSelection(pythonCode)) {
                    Log.d(TAG, "Image-processing function detected – launching image picker")
                    val selectedPaths = ImagePickerManager.getInstance()
                        .awaitImageSelection(context)
                    injectSelectedImages(selectedPaths)
                    Log.d(TAG, "Injected ${selectedPaths.size} image path(s) into builtins._selected_images")
                }

                // Execute the Python code using the desktop worker approach
                val result = executeFunctionCode(pythonCode, args)

                // Clean up checkpoint callback
                cleanupCheckpointCallback()

                // Clean up progress callback
                cleanupProgressCallback()

                // Clean up injected image paths
                cleanupSelectedImages()

                Log.d(TAG, "Python code executed successfully")
                mapOf<String, Any?>(
                    "status" to "success",
                    "message" to "Code executed successfully",
                    "result" to result,
                    "worker_id" to (workerIdManager.getCurrentWorkerId() ?: "unknown")
                )

            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute Python code", e)
                EventLogger.error(EventLogger.Categories.PYTHON, "Python execution failed: ${e.message}")
                // Encode the error as a JSON string so the backend always receives a parseable result
                val errorJson = try {
                    org.json.JSONObject().apply {
                        put("error", e.message ?: "Unknown error")
                        put("error_type", e.javaClass.simpleName)
                        put("status", "error")
                    }.toString()
                } catch (_: Exception) {
                    """{"error":"execution_failed","status":"error"}"""
                }
                mapOf<String, Any?>(
                    "status" to "error",
                    "message" to "Execution failed: ${e.message ?: "Unknown error"}",
                    "result" to errorJson,
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
                
                Log.d(TAG, "Python execution test successful")
                mapOf<String, Any?>(
                    "status" to "success",
                    "message" to "Python execution test passed",
                    "result" to result
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Python execution test failed", e)
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
     * This provides a generic checkpoint callback that accepts any keyword arguments.
     * The checkpoint state variables to capture are defined in task_metadata.checkpoint_state
     * and handled dynamically - no hardcoded variable names.
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

def _checkpoint_callback(**kwargs):
    '''
    Generic checkpoint callback - updates builtins._checkpoint_state with any provided values.
    This is the primary mechanism for Android since sys.settrace() does not work.
    
    Usage: builtins._checkpoint_callback(progress_percent=50.0, my_var=123, other_var="abc")
    '''
    current = getattr(builtins, '_checkpoint_state', {})
    current.update(kwargs)
    builtins._checkpoint_state = current
    builtins._checkpoint_update_counter = getattr(builtins, '_checkpoint_update_counter', 0) + 1

def _update_checkpoint_state(**kwargs):
    '''
    Alternative simpler API - just pass the variables to checkpoint.
    Identical to _checkpoint_callback for compatibility.
    '''
    current = getattr(builtins, '_checkpoint_state', {})
    current.update(kwargs)
    builtins._checkpoint_state = current
    builtins._checkpoint_update_counter = getattr(builtins, '_checkpoint_update_counter', 0) + 1

builtins._checkpoint_callback = _checkpoint_callback
builtins._update_checkpoint_state = _update_checkpoint_state

print("[Android Worker] Checkpoint callback initialized (generic mode - accepts any variables)")
""".trimIndent()
                
                // IMPORTANT: exec() on Chaquopy requires explicit globals dict to avoid "frame does not exist" error
                val globalDict = pythonInstance?.getModule("builtins")?.callAttr("dict")
                builtinsModule?.callAttr("exec", setupCode, globalDict)
                Log.d(TAG, "Checkpoint callback set up in Python builtins (generic mode)")
                
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
     * Set up real-time progress callback in Python builtins
     * Python code can call: builtins._progress_callback(50.0) to report 50% progress
     * Works independently of checkpointing
     */
    private fun setupProgressCallback() {
        if (progressCallback != null) {
            try {
                val setupCode = """
import builtins

def _progress_callback(progress_percent):
    '''
    Report task progress in real-time (independent of checkpointing).
    
    Usage: builtins._progress_callback(50.0)  # Report 50% progress
    
    This works even when checkpointing is disabled.
    '''
    # Store it so Kotlin can read it
    builtins._current_progress = float(progress_percent)

builtins._progress_callback = _progress_callback
builtins._current_progress = 0.0

print("[Android Worker] Progress callback initialized (real-time progress reporting)")
""".trimIndent()
                
                val globalDict = pythonInstance?.getModule("builtins")?.callAttr("dict")
                builtinsModule?.callAttr("exec", setupCode, globalDict)
                
                Log.d(TAG, "Progress callback set up in Python builtins")
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set up progress callback: ${e.message}")
            }
        }
    }
    
    /**
     * Clean up progress callback from Python builtins
     */
    private fun cleanupProgressCallback() {
        try {
            val cleanupCode = """
import builtins

# Clean up progress callback attributes
for attr in ['_progress_callback', '_current_progress']:
    if hasattr(builtins, attr):
        delattr(builtins, attr)

print("[Android Worker] Progress callback cleaned up")
""".trimIndent()
            
            val globalDict = pythonInstance?.getModule("builtins")?.callAttr("dict")
            builtinsModule?.callAttr("exec", cleanupCode, globalDict)
            Log.d(TAG, "Progress callback cleaned up")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up progress callback: ${e.message}")
        }
    }
    
    /**
     * Poll real-time progress from Python  
     * Reads builtins._current_progress and calls the progress callback
     */
    fun pollProgressAndUpdate() {
        if (progressCallback == null) return
        
        try {
            val progressValue = builtinsModule?.callAttr("getattr",
                pythonInstance?.getModule("builtins"),
                "_current_progress",
                0.0f
            )
            
            if (progressValue != null) {
                val progress = progressValue.toFloat()
                if (progress > 0f) {
                    progressCallback?.invoke(progress)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not poll progress: ${e.message}")
        }
    }
    
    /**
     * Poll checkpoint state from Python and update handler.
     * This is called periodically by the checkpoint handler's monitoring loop.
     * 
     * Reads from builtins._checkpoint_state which is updated by:
     * - Explicitly calling builtins._checkpoint_callback(...) or builtins._update_checkpoint_state(...)
     * - Code instrumentation that auto-injects checkpoint calls at strategic points
     * 
     * FULLY GENERIC: Extracts all variables from the Python dict dynamically.
     * No hardcoded variable names - uses whatever is in the checkpoint state.
     */
    fun pollAndUpdateCheckpointState() {
        val handler = checkpointHandlerRef.get()
        if (handler == null) {
            Log.d(TAG, "Poll skipped: no checkpoint handler")
            return
        }
        
        try {
            Log.d(TAG, "Polling checkpoint state from Python...")
            
            // Use getattr to properly read builtins._checkpoint_state
            // builtinsModule.get() doesn't work for dynamically set attributes
            val stateObj = builtinsModule?.callAttr("getattr", 
                pythonInstance?.getModule("builtins"), 
                "_checkpoint_state", 
                null  // default value if not found
            )
            
            if (stateObj == null) {
                Log.d(TAG, "Poll result: stateObj is null")
                return
            }
            
            // Check if state is empty - do this safely to avoid blocking
            val stateStr = try { 
                stateObj.toString() 
            } catch (e: Exception) { 
                Log.w(TAG, "Poll: Failed to convert state to string: ${e.message}")
                return
            }
            
            if (stateStr == "None" || stateStr == "null" || stateStr == "{}") {
                Log.d(TAG, "Poll result: state is empty ($stateStr)")
                return
            }
            
            Log.d(TAG, "Poll: Found state, extracting keys...")
            
            // Build a generic state map from all keys in the Python dict
            val stateData = mutableMapOf<String, Any>()
            
            try {
                // Convert dict_keys to a Python list first, then iterate
                // dict.keys() returns a dict_keys view object which doesn't support indexing
                val builtins = pythonInstance?.getModule("builtins")
                val keysList = builtins?.callAttr("list", stateObj.callAttr("keys"))
                val numKeys = keysList?.callAttr("__len__")?.toJava(Int::class.java) ?: 0
                
                Log.d(TAG, "Poll: Found $numKeys keys in state")
                
                for (i in 0 until numKeys) {
                    val key = keysList?.callAttr("__getitem__", i)
                    val keyStr = key?.toString() ?: continue
                    // Skip internal resume flag
                    if (keyStr == "_is_resumed") continue
                    
                    val value = stateObj.callAttr("get", keyStr)
                    if (value != null && value.toString() != "None") {
                        stateData[keyStr] = convertPyObjectToJava(value)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting state keys: ${e.message}")
            }
            
            if (stateData.isNotEmpty()) {
                handler.updateState(CheckpointHandler.CheckpointState.fromMap(stateData))
                
                // Log progress if available
                val progress = stateData["progress_percent"]
                Log.d(TAG, "Checkpoint state polled: progress=$progress, keys=${stateData.keys}")
            } else {
                Log.d(TAG, "Poll: stateData is empty after extraction")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error polling checkpoint state: ${e.message}")
            e.printStackTrace()
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
                Log.d(TAG, "Loaded sentiment_worker module from Python sources")
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
     * Auto-detect checkpoint variables from Python code.
     * Looks for common patterns of variables that should be checkpointed.
     */
    private fun autoDetectCheckpointVars(funcCode: String): List<String> {
        val detectedVars = mutableSetOf<String>()
        
        // Common checkpoint variable patterns
        val commonPatterns = listOf(
            // Progress tracking
            Regex("\\b(progress_percent|progress|percent_complete)\\s*="),
            // Counter/iteration tracking
            Regex("\\b(processed|count|completed|iterations|i|idx|index)\\s*\\+="),
            Regex("\\b(processed|count|completed|iterations)\\s*=\\s*0"),
            // Results accumulation
            Regex("\\b(results|result|output|data)\\s*=\\s*\\[\\]"),
            Regex("\\b(results|result|output|data)\\.append\\("),
            // Total/target tracking
            Regex("\\b(total|total_count|num_trials|batch_size|n)\\s*="),
            // Running calculations
            Regex("\\b(sum|total_sum|running_total|estimated_\\w+)\\s*[+=]"),
        )
        
        for (pattern in commonPatterns) {
            val matches = pattern.findAll(funcCode)
            for (match in matches) {
                // Extract the variable name from the match
                val varMatch = Regex("\\b(\\w+)\\s*[+=]").find(match.value)
                varMatch?.groupValues?.getOrNull(1)?.let { varName ->
                    // Filter out common non-checkpoint variables
                    if (varName !in listOf("i", "j", "k", "x", "y", "n", "item", "value", "key", "idx")) {
                        detectedVars.add(varName)
                    }
                }
            }
        }
        
        // Always include progress_percent if any variable assignments look like progress calculation
        if (funcCode.contains("progress_percent") || 
            funcCode.contains("/ len(") || 
            funcCode.contains("* 100")) {
            detectedVars.add("progress_percent")
        }
        
        // For loop counters, include processed/count if found
        if (funcCode.contains("processed") && funcCode.contains("+=")) {
            detectedVars.add("processed")
        }
        if (funcCode.contains("results") && funcCode.contains(".append(")) {
            detectedVars.add("results")
        }
        
        Log.d(TAG, "Auto-detected checkpoint variables: $detectedVars")
        return detectedVars.toList()
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
        // If no vars configured, try basic instrumentation for loops
        val varsToCapture = if (checkpointStateVars.isEmpty()) {
            autoDetectCheckpointVars(funcCode)
        } else {
            checkpointStateVars
        }
        
        if (varsToCapture.isEmpty()) {
            Log.w(TAG, "No checkpoint variables to instrument")
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
                    // Only default to "    " inside a function body; module-level code stays at column 0
                    val injectIndent = functionBodyIndent
                    if (functionBodyIndent.isEmpty()) functionBodyIndent = "    "
                    
                    // Add import builtins before this line
                    if (!funcCode.contains("import builtins")) {
                        instrumentedLines.add("${injectIndent}import builtins  # Injected for checkpoint support")
                    }
                    addedImport = true
                    
                    // Add resume logic if this is a resume execution
                    if (isResume && !addedResumeLogic) {
                        addResumeLogic(instrumentedLines, injectIndent, varsToCapture)
                        addedResumeLogic = true
                    }
                }
                
                // If no docstring and first non-blank line, add import
                if (!addedImport && !passedDocstring && !inDocstring && !line.isBlank() && 
                    !trimmed.startsWith("\"\"\"") && !trimmed.startsWith("'''")) {
                    passedDocstring = true  // No docstring case
                    functionBodyIndent = line.takeWhile { it == ' ' || it == '\t' }
                    // Only default to "    " inside a function body; module-level code stays at column 0
                    val injectIndent = functionBodyIndent
                    if (functionBodyIndent.isEmpty()) functionBodyIndent = "    "
                    
                    if (!funcCode.contains("import builtins")) {
                        instrumentedLines.add("${injectIndent}import builtins  # Injected for checkpoint support")
                    }
                    addedImport = true
                    
                    // Add resume logic if this is a resume execution
                    if (isResume && !addedResumeLogic) {
                        addResumeLogic(instrumentedLines, injectIndent, varsToCapture)
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
                        if (varName in varsToCapture && isDefaultInitialization(value)) {
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
                // Handle: for i in range(n), for i in range(start, end), etc.
                if (isResume && trimmed.matches(Regex("for\\s+\\w+\\s+in\\s+range\\s*\\(.*\\)\\s*:"))) {
                    // Extract the loop variable and range arguments
                    val forMatch = Regex("for\\s+(\\w+)\\s+in\\s+range\\s*\\(\\s*(\\w+)\\s*\\)").find(trimmed)
                    if (forMatch != null) {
                        val loopVar = forMatch.groupValues[1]
                        val rangeArg = forMatch.groupValues[2]
                        val indent = line.takeWhile { it == ' ' || it == '\t' }
                        // Modify to use _resume_start_index as start
                        val modifiedLine = "${indent}for $loopVar in range(_resume_start_index, $rangeArg):"
                        instrumentedLines.add(modifiedLine)
                        Log.d(TAG, "Modified range loop to resume from _resume_start_index: $trimmed")
                        continue@lineLoop
                    } else {
                        instrumentedLines.add(line)
                    }
                // Handle: for i, item in enumerate(collection), for idx, val in enumerate(data), etc.
                } else if (isResume && trimmed.matches(Regex("for\\s+\\w+\\s*,\\s*\\w+\\s+in\\s+enumerate\\s*\\(.*\\)\\s*:"))) {
                    // Extract loop variables and collection
                    val enumMatch = Regex("for\\s+(\\w+)\\s*,\\s*(\\w+)\\s+in\\s+enumerate\\s*\\((.+)\\)\\s*:").find(trimmed)
                    if (enumMatch != null) {
                        val indexVar = enumMatch.groupValues[1]
                        val itemVar = enumMatch.groupValues[2]
                        val collection = enumMatch.groupValues[3].trim()
                        val indent = line.takeWhile { it == ' ' || it == '\t' }
                        // Modify to slice collection and add start offset to enumerate
                        // This allows resuming from _resume_start_index
                        val modifiedLine = "${indent}for $indexVar, $itemVar in enumerate($collection[_resume_start_index:], start=_resume_start_index):"
                        instrumentedLines.add(modifiedLine)
                        Log.d(TAG, "Modified enumerate loop to resume from _resume_start_index: $trimmed")
                        continue@lineLoop
                    } else {
                        instrumentedLines.add(line)
                    }
                } else {
                    instrumentedLines.add(line)
                }
                
                // Detect loop start to track when we're inside a loop
                // Handle: for i in range(...), for i, item in enumerate(...), for item in collection
                val isLoopStart = trimmed.startsWith("for ") && trimmed.endsWith(":")
                if (isLoopStart) {
                    // Remember the loop indentation so we can inject checkpoint at the end of loop body
                    val loopIndent = line.takeWhile { it == ' ' || it == '\t' }
                    val loopBodyIndent = loopIndent + "    "
                    
                    // Look ahead to find a good injection point - after assignments or at time.sleep
                    var lookaheadIdx = index + 1
                    var foundInjectionPoint = false
                    
                    while (lookaheadIdx < lines.size) {
                        val lookaheadLine = lines[lookaheadIdx]
                        val lookaheadTrimmed = lookaheadLine.trim()
                        val lookaheadIndent = lookaheadLine.takeWhile { it == ' ' || it == '\t' }
                        
                        // If we've exited the loop body (less indentation), stop
                        if (lookaheadTrimmed.isNotEmpty() && !lookaheadLine.startsWith(loopBodyIndent) && !lookaheadLine.startsWith(loopIndent + "\t")) {
                            break
                        }
                        
                        // Look for time.sleep or progress updates as injection points
                        if (lookaheadTrimmed.contains("time.sleep") || 
                            lookaheadTrimmed.contains("progress_percent") ||
                            lookaheadTrimmed.contains("processed +=") ||
                            lookaheadTrimmed.contains("count +=")) {
                            foundInjectionPoint = true
                            break
                        }
                        lookaheadIdx++
                    }
                    
                    // Mark that we found a loop - checkpoint injection will happen at progress/sleep lines
                    if (!foundInjectionPoint) {
                        Log.d(TAG, "Loop detected but no clear injection point found - checkpoint may be at progress_percent only")
                    }
                }
                
                // Inject checkpoint after time.sleep() calls (common pattern in iterative tasks)
                if (!trimmed.startsWith("#") && 
                    line.contains("time.sleep") && 
                    !line.contains("_update_checkpoint_state")) {
                    
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val callbackArgs = varsToCapture.joinToString(", ") { "$it=$it" }
                    val callbackLines = listOf(
                        "${indent}try:",
                        "${indent}    if hasattr(builtins, '_update_checkpoint_state'):",
                        "${indent}        builtins._update_checkpoint_state($callbackArgs)",
                        "${indent}except: pass"
                    )
                    
                    val nextLine = lines.getOrNull(index + 1)?.trim() ?: ""
                    if (!nextLine.contains("_update_checkpoint_state") && !nextLine.contains("_checkpoint_callback")) {
                        callbackLines.forEach { instrumentedLines.add(it) }
                        foundProgressUpdate = true
                        Log.d(TAG, "Injected checkpoint callback after time.sleep at line ${index + 1}")
                    }
                }
                
                // Inject checkpoint after += operations on tracked variables (processed, count, etc)
                if (!trimmed.startsWith("#") && 
                    (line.contains("processed +=") || line.contains("count +=") || line.contains("completed +=")) &&
                    !line.contains("_update_checkpoint_state")) {
                    
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val callbackArgs = varsToCapture.joinToString(", ") { "$it=$it" }
                    val callbackLines = listOf(
                        "${indent}try:",
                        "${indent}    if hasattr(builtins, '_update_checkpoint_state'):",
                        "${indent}        builtins._update_checkpoint_state($callbackArgs)",
                        "${indent}except: pass"
                    )
                    
                    val nextLine = lines.getOrNull(index + 1)?.trim() ?: ""
                    if (!nextLine.contains("_update_checkpoint_state") && !nextLine.contains("_checkpoint_callback")) {
                        callbackLines.forEach { instrumentedLines.add(it) }
                        foundProgressUpdate = true
                        Log.d(TAG, "Injected checkpoint callback after counter update at line ${index + 1}")
                    }
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
                    val callbackArgs = varsToCapture.joinToString(", ") { "$it=$it" }
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
                Log.d(TAG, "No progress_percent/counter updates found in code, checkpoint state may need manual updates")
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
        instrumentedLines.add("${indent}            print('[Android Worker] Resuming from checkpoint - restoring state...')")
        
        // Restore each checkpoint variable
        for (varName in checkpointStateVars) {
            instrumentedLines.add("${indent}            if '$varName' in _cp_state:")
            instrumentedLines.add("${indent}                $varName = _cp_state['$varName']")
            instrumentedLines.add("${indent}                print(f'[Android Worker] Restored $varName = {$varName}')")
        }
        
        // Set resume start index based on common iteration counter variables
        // Check multiple possible variable names for loop resumption
        instrumentedLines.add("${indent}            # Set loop start index for resumption (check multiple possible counter names)")
        instrumentedLines.add("${indent}            _resume_start_index = 0")
        instrumentedLines.add("${indent}            for _counter_name in ['trials_completed', 'processed', 'count', 'completed', 'iterations', 'i']:")
        instrumentedLines.add("${indent}                if _counter_name in _cp_state:")
        instrumentedLines.add("${indent}                    _resume_start_index = int(_cp_state[_counter_name])")
        instrumentedLines.add("${indent}                    print(f'[Android Worker] Will resume from iteration {_resume_start_index} (based on {_counter_name})')")
        instrumentedLines.add("${indent}                    break")
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
    
    // ── Image-selection helpers ──────────────────────────────────────────────

    /**
     * Returns true when the Python code looks like an image-processing function
     * that needs the user to supply images from the device gallery.
     *
     * Detection criteria (any one is sufficient):
     *  - Function name contains "process_images"
     *  - Function body imports PIL/Pillow (from PIL import …)
     *  - Function name contains "image" **and** uses PIL/glob/base64
     */
    private fun requiresImageSelection(code: String): Boolean {
        val funcName = extractFunctionName(code)?.lowercase() ?: ""
        val lowerCode = code.lowercase()

        // Strongest explicit signal: backend code already references the injected variable
        if (lowerCode.contains("_selected_images")) return true

        // Explicit image-processing function name
        if (funcName.contains("process_images") || funcName.contains("image_process")) return true

        // PIL is imported AND glob is used specifically to search for image files by extension
        val importsPil = lowerCode.contains("from pil import") ||
                         lowerCode.contains("import pil.")  ||
                         lowerCode.contains("import pil\n") ||
                         lowerCode.contains("import pil ")
        val imageGlobSearch = lowerCode.contains("glob.glob") &&
                              (lowerCode.contains(".jpg") || lowerCode.contains(".png") ||
                               lowerCode.contains(".jpeg") || lowerCode.contains(".bmp") ||
                               lowerCode.contains(".gif")  || lowerCode.contains(".webp"))

        return importsPil && imageGlobSearch && funcName.contains("image")
    }

    /**
     * Injects the selected image file paths into Python builtins so that any
     * executing Python function can access them as:
     *
     *   import builtins
     *   paths = builtins._selected_images   # list[str]
     *
     * @param paths Absolute file-system paths returned by [ImagePickerManager].
     */
    private fun injectSelectedImages(paths: List<String>) {
        try {
            val pyList = builtinsModule?.callAttr("list") ?: return
            paths.forEach { path -> pyList.callAttr("append", path) }
            builtinsModule?.put("_selected_images", pyList)
            Log.d(TAG, "builtins._selected_images set to $paths")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject selected images: ${e.message}")
        }
    }

    /**
     * Removes [_selected_images] from Python builtins after the task finishes.
     */
    private fun cleanupSelectedImages() {
        try {
            val hasAttr = builtinsModule?.callAttr("hasattr", builtinsModule, "_selected_images")
                ?.toJava(Boolean::class.java) == true
            if (hasAttr) {
                builtinsModule?.callAttr("delattr", builtinsModule, "_selected_images")
            }
        } catch (e: Exception) {
            Log.d(TAG, "cleanupSelectedImages: ${e.message}")
        }
    }

    // ── Function execution ───────────────────────────────────────────────────

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
            Log.d(TAG, "Executing task... | worker_runtime=android | func=$funcName | checkpoint=$hasCheckpointHandler | resume=$isResume")
            
            // Instrument code for checkpointing if handler is set
            val codeToExecute = if (hasCheckpointHandler && funcName != null) {
                var checkpointStateVars = handler?.getCheckpointStateVars() ?: emptyList()
                
                // If no checkpoint vars are configured, try to auto-detect common variables from the code
                if (checkpointStateVars.isEmpty()) {
                    checkpointStateVars = autoDetectCheckpointVars(funcCode)
                    if (checkpointStateVars.isNotEmpty()) {
                        Log.d(TAG, "Auto-detected checkpoint vars: $checkpointStateVars")
                    }
                }
                
                configureCheckpointTrace(funcName, checkpointStateVars)
                
                // Instrument the code to inject checkpoint callbacks (and resume logic if resuming)
                // Always instrument if we have a checkpoint handler, even without declared vars
                val instrumented = instrumentCodeForCheckpointing(funcCode, checkpointStateVars, isResume)
                Log.d(TAG, "Code instrumented for checkpointing with vars: $checkpointStateVars, resume=$isResume")
                // Log a portion of the instrumented code to verify injection
                val injectionMarker = "_update_checkpoint_state"
                if (instrumented.contains(injectionMarker)) {
                    Log.i(TAG, "Checkpoint callback injection successful")
                } else {
                    Log.w(TAG, "No checkpoint callback was injected - state capture may not work")
                }
                if (isResume && instrumented.contains("_resume_start_index")) {
                    Log.i(TAG, "Resume logic injection successful")
                }
                instrumented
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
            
            Log.d(TAG, "Task completed successfully")
            
            // result is already a Java object from executeFunctionWithArgs
            Log.d(TAG, "Function result: $result")
            result
            
        } catch (e: Exception) {
            // Disable trace on error
            if (hasCheckpointHandler) {
                disableCheckpointTrace()
            }
            val errorMsg = "Task execution failed: ${e.message}"
            Log.e(TAG, "Task failed: $errorMsg")
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
                Log.d(TAG, "Function returned Kotlin null – using empty result")
                return "{}"
            }

            // Check for Python None (pyResult is a PyObject wrapping None)
            val builtins = pythonInstance?.getModule("builtins")
            val noneType = builtins?.callAttr("type", builtins.get("None"))
            val isNone = builtins?.callAttr("isinstance", pyResult, noneType)?.toJava(Boolean::class.java) == true
            if (isNone) {
                Log.d(TAG, "Function returned Python None – using empty result")
                return "{}"
            }

            // Check if it's a Python dict and convert to JSON string
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
                if (jsonStr != null && jsonStr != "null") {
                    return jsonStr
                }
            } catch (e: Exception) {
                Log.d(TAG, "JSON conversion failed, using toString: ${e.message}")
            }

            // Fallback to string representation (exclude bare "null")
            val strRepr = pyResult.toString()
            if (strRepr == "null" || strRepr == "None") "{}" else strRepr
            
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
            
            // Use a SINGLE dict for both globals and locals so that module-level
            // variables (paused, killed) and function definitions (pause, resume,
            // kill, task function) all share the same namespace.  This is critical:
            // functions that use `global paused` will reference this dict, so the
            // flags MUST live here too.
            val execGlobals = pythonInstance?.getModule("builtins")?.callAttr("dict")
            
            // Execute the function code — single-dict exec makes globals == locals
            builtinsModule?.callAttr("exec", funcCode, execGlobals)
            
            // Save the reference so pause/resume/kill can target the same namespace
            execGlobalsRef.set(execGlobals)
            Log.d(TAG, "Saved execGlobals reference for task control")
            
            // Find the function object in execGlobals
            val typesModule = pythonInstance?.getModule("types")
            val functionType = typesModule?.get("FunctionType")
            
            var func: PyObject? = null
            
            // Try to find function by name first (more reliable)
            val functionName = extractFunctionName(funcCode)
            if (functionName != null) {
                val namedFunc = execGlobals?.get(functionName)
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
                
                val values = execGlobals?.callAttr("values")
                val valuesList = builtinsModule?.callAttr("list", values)
                val valuesJavaList = valuesList?.asList()
                
                valuesJavaList?.forEach { value ->
                    val pyValue = value as? PyObject
                    if (pyValue != null) {
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
                val keys = execGlobals?.callAttr("keys")
                val keysList = builtinsModule?.callAttr("list", keys)
                val keysJavaList = keysList?.asList()
                Log.e(TAG, "Available keys in execGlobals: $keysJavaList")
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
    
    // ── Task control (pause / resume / kill) ────────────────────────────────

    /**
     * Helper: read a value from the exec globals *dict* by key.
     * Uses dict.get(key) so it returns Python None (mapped to null) when absent.
     * Do NOT use PyObject.get() – that calls getattr(), which doesn't work on dicts.
     */
    private fun dictGet(dict: PyObject, key: String): PyObject? {
        val value = dict.callAttr("get", key)
        // Chaquopy wraps Python None as a non-null PyObject; detect it explicitly.
        val builtins = pythonInstance?.getModule("builtins") ?: return null
        val isNone = builtins.callAttr("isinstance", value,
            builtins.callAttr("type", builtins.get("None"))
        )?.toJava(Boolean::class.java) == true
        return if (isNone) null else value
    }

    /**
     * Helper: set a value in the exec globals *dict*.
     * Uses dict.__setitem__ instead of PyObject.put() (which calls setattr).
     */
    private fun dictSet(dict: PyObject, key: String, value: Any?) {
        dict.callAttr("__setitem__", key, value)
    }

    /**
     * Pause the currently running task by setting the `paused` flag in the
     * exec globals dict that the task code is actively reading.
     */
    fun pauseExecution() {
        val globals = execGlobalsRef.get()
        if (globals == null) {
            Log.w(TAG, "pauseExecution: no execGlobals – task may not be running")
            return
        }
        try {
            // Prefer calling the pause() function defined in the task code so
            // that any side-effects (e.g. checkpoint flush) are honoured.
            val pauseFn = dictGet(globals, "pause")
            if (pauseFn != null) {
                pauseFn.call()
                Log.d(TAG, "pauseExecution: called pause() in exec namespace")
            } else {
                // Fallback: set the flag directly in the dict
                dictSet(globals, "paused", true)
                Log.d(TAG, "pauseExecution: set paused=True directly in execGlobals")
            }
        } catch (e: Exception) {
            // Last-resort fallback
            try { dictSet(globals, "paused", true) } catch (_: Exception) {}
            Log.w(TAG, "pauseExecution error (flag set via fallback): ${e.message}")
        }
    }

    /**
     * Resume the currently paused task.
     */
    fun resumeExecution() {
        val globals = execGlobalsRef.get()
        if (globals == null) {
            Log.w(TAG, "resumeExecution: no execGlobals – task may not be running")
            return
        }
        try {
            val resumeFn = dictGet(globals, "resume")
            if (resumeFn != null) {
                resumeFn.call()
                Log.d(TAG, "resumeExecution: called resume() in exec namespace")
            } else {
                dictSet(globals, "paused", false)
                Log.d(TAG, "resumeExecution: set paused=False directly in execGlobals")
            }
        } catch (e: Exception) {
            try { dictSet(globals, "paused", false) } catch (_: Exception) {}
            Log.w(TAG, "resumeExecution error (flag set via fallback): ${e.message}")
        }
    }

    /**
     * Kill (cancel) the currently running task.
     */
    fun killExecution() {
        val globals = execGlobalsRef.get()
        if (globals == null) {
            Log.w(TAG, "killExecution: no execGlobals – task may not be running")
            return
        }
        try {
            val killFn = dictGet(globals, "kill")
            if (killFn != null) {
                killFn.call()
                Log.d(TAG, "killExecution: called kill() in exec namespace")
            } else {
                dictSet(globals, "killed", true)
                Log.d(TAG, "killExecution: set killed=True directly in execGlobals")
            }
        } catch (e: Exception) {
            try { dictSet(globals, "killed", true) } catch (_: Exception) {}
            Log.w(TAG, "killExecution error (flag set via fallback): ${e.message}")
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            Log.d(TAG, "Cleaning up Python executor...")
            execGlobalsRef.set(null)
            isInitialized.set(false)
            pythonInstance = null
            builtinsModule = null
            jsonModule = null
            base64Module = null
            Log.d(TAG, "Python executor cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
