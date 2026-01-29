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
     * Set up checkpoint callback in Python builtins
     * This allows Python code to call back to Kotlin to update checkpoint state
     */
    private fun setupCheckpointCallback() {
        val handler = checkpointHandlerRef.get()
        if (handler != null) {
            try {
                // Create a Python function that calls back to Kotlin
                val callbackCode = """
def _create_checkpoint_callback(kotlin_update_func):
    def checkpoint_callback(progress_percent, estimated_e, trials_completed, total_count, num_trials=0):
        import builtins
        # Store state in builtins for the checkpoint handler to read
        builtins._checkpoint_state = {
            'progress_percent': progress_percent,
            'estimated_e': estimated_e,
            'trials_completed': trials_completed,
            'total_count': total_count,
            'num_trials': num_trials
        }
    return checkpoint_callback

import builtins
builtins._checkpoint_callback = _create_checkpoint_callback(None)
builtins._checkpoint_state = None
""".trimIndent()
                
                builtinsModule?.callAttr("exec", callbackCode)
                Log.d(TAG, "Checkpoint callback set up in Python builtins")
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set up checkpoint callback: ${e.message}")
            }
        }
    }
    
    /**
     * Clean up checkpoint callback from Python builtins
     */
    private fun cleanupCheckpointCallback() {
        try {
            // Read final checkpoint state before cleanup
            val finalState = builtinsModule?.get("_checkpoint_state")
            if (finalState != null && finalState.toString() != "None") {
                Log.d(TAG, "Final checkpoint state: $finalState")
            }
            
            val cleanupCode = """
import builtins
if hasattr(builtins, '_checkpoint_callback'):
    delattr(builtins, '_checkpoint_callback')
if hasattr(builtins, '_checkpoint_state'):
    delattr(builtins, '_checkpoint_state')
""".trimIndent()
            
            builtinsModule?.callAttr("exec", cleanupCode)
            Log.d(TAG, "Checkpoint callback cleaned up from Python builtins")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up checkpoint callback: ${e.message}")
        }
    }
    
    /**
     * Poll checkpoint state from Python and update handler
     * This is called periodically by the checkpoint handler's monitoring loop
     */
    fun pollAndUpdateCheckpointState() {
        val handler = checkpointHandlerRef.get() ?: return
        
        try {
            val stateObj = builtinsModule?.get("_checkpoint_state")
            if (stateObj != null && stateObj.toString() != "None") {
                // Extract values from Python dict using .get() method instead of toJava(Map)
                val progressPercent = stateObj.callAttr("get", "progress_percent", 0.0)?.toJava(Number::class.java)?.toFloat() ?: 0f
                val estimatedE = stateObj.callAttr("get", "estimated_e", 0.0)?.toJava(Number::class.java)?.toDouble() ?: 0.0
                val trialsCompleted = stateObj.callAttr("get", "trials_completed", 0)?.toJava(Number::class.java)?.toInt() ?: 0
                val totalCount = stateObj.callAttr("get", "total_count", 0)?.toJava(Number::class.java)?.toLong() ?: 0L
                val numTrials = stateObj.callAttr("get", "num_trials", 0)?.toJava(Number::class.java)?.toInt() ?: 0
                
                handler.updateState(
                    CheckpointHandler.CheckpointState(
                        trialsCompleted = trialsCompleted,
                        totalCount = totalCount,
                        numTrials = numTrials,
                        progressPercent = progressPercent,
                        estimatedE = estimatedE
                    )
                )
                Log.d(TAG, "Checkpoint state polled: progress=$progressPercent%, trials=$trialsCompleted/$numTrials")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error polling checkpoint state: ${e.message}")
        }
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
     * Execute function code using desktop worker approach
     * Matches the desktop worker's _execute_task method
     */
    private fun executeFunctionCode(funcCode: String, taskArgs: PyObject?): Any? {
        return try {
            Log.d(TAG, "🔄 Executing task... | worker_runtime=android")
            
            // Deserialize the function (equivalent to deserialize_function in desktop worker)
            val func = deserializeFunction(funcCode)
            
            // Convert taskArgs to Java list for processing with proper error handling
            val argsList = convertPyObjectToList(taskArgs)
            
            Log.d(TAG, "Task args: $argsList")
            
            // Execute the function with the provided arguments (matching desktop worker logic)
            val result = executeFunctionWithArgs(func, argsList)
            
            Log.d(TAG, "✅ Task completed successfully")
            
            // result is already a Java object from executeFunctionWithArgs
            Log.d(TAG, "Function result: $result")
            result
            
        } catch (e: Exception) {
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
