package com.example.mcc_phase3.worker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.util.*

/**
 * Python executor using Chaquopy for executing Python code on Android
 * Handles serialized Python functions and arguments from the backend
 * 
 * Note: This is a fallback implementation that doesn't require Chaquopy.
 * For full Python execution, install Chaquopy and uncomment the Chaquopy imports.
 */
class PythonExecutor(private val context: Context) {

    companion object {
        private const val TAG = "PythonExecutor"
        private var isInitialized = false
    }

    /**
     * Initialize Python runtime (mock implementation)
     */
    fun initialize(): Boolean {
        return try {
            if (!isInitialized) {
                Log.d(TAG, "✅ Mock Python runtime initialized successfully")
                isInitialized = true
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Python runtime", e)
            false
        }
    }

    /**
     * Execute Python code with arguments (mock implementation)
     * @param serializedCode Serialized Python code from backend
     * @param serializedArgs Serialized arguments from backend
     * @return Execution result
     */
    suspend fun executePythonCode(
        serializedCode: String,
        serializedArgs: String
    ): Any = withContext(Dispatchers.IO) {
        try {
            if (!initialize()) {
                throw RuntimeException("Python runtime not initialized")
            }

            Log.d(TAG, "🐍 Executing Python code (mock)")
            Log.d(TAG, "📝 Serialized code: $serializedCode")
            Log.d(TAG, "📝 Serialized args: $serializedArgs")

            // Deserialize the Python code and arguments
            val pythonCode = deserializeString(serializedCode)
            val pythonArgs = deserializeObject(serializedArgs)

            Log.d(TAG, "🐍 Python code: $pythonCode")
            Log.d(TAG, "🐍 Python args: $pythonArgs")

            // Mock execution - return a simulated result
            val result = executeMockCode(pythonCode, pythonArgs)
            
            Log.d(TAG, "✅ Python execution completed successfully (mock)")
            Log.d(TAG, "📊 Result: $result")
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Python execution failed", e)
            throw RuntimeException("Python execution failed: ${e.message}")
        }
    }

    /**
     * Mock Python code execution
     */
    private fun executeMockCode(code: String, args: Any?): Any {
        return try {
            // Simple mock execution based on code content
            when {
                code.contains("add") || code.contains("sum") -> {
                    if (args is List<*>) {
                        args.mapNotNull { (it as? Number)?.toDouble() }.sum()
                    } else {
                        "Mock addition result: 42"
                    }
                }
                code.contains("multiply") || code.contains("product") -> {
                    if (args is List<*>) {
                        args.mapNotNull { (it as? Number)?.toDouble() }.reduceOrNull { a, b -> a * b } ?: 0.0
                    } else {
                        "Mock multiplication result: 84"
                    }
                }
                code.contains("factorial") -> {
                    val n = (args as? Number)?.toInt() ?: 5
                    (1..n).fold(1L) { acc, i -> acc * i }
                }
                code.contains("fibonacci") -> {
                    val n = (args as? Number)?.toInt() ?: 10
                    if (n <= 1) n.toLong() else {
                        var a = 0L
                        var b = 1L
                        repeat(n - 1) {
                            val temp = a + b
                            a = b
                            b = temp
                        }
                        b
                    }
                }
                else -> {
                    mapOf(
                        "status" to "mock_execution",
                        "code_length" to code.length,
                        "args_type" to (args?.javaClass?.simpleName ?: "null"),
                        "message" to "Mock Python execution - install Chaquopy for real Python support"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error executing mock Python code", e)
            "Mock execution error: ${e.message}"
        }
    }

    /**
     * Deserialize a string from base64 or other encoding
     */
    private fun deserializeString(serialized: String): String {
        return try {
            // Try base64 decoding first
            val decoded = Base64.getDecoder().decode(serialized)
            String(decoded)
        } catch (e: Exception) {
            // If base64 fails, assume it's already a string
            serialized
        }
    }

    /**
     * Deserialize an object from base64 encoded serialized data
     */
    private fun deserializeObject(serialized: String): Any? {
        return try {
            val decoded = Base64.getDecoder().decode(serialized)
            val inputStream = ByteArrayInputStream(decoded)
            val objectInputStream = ObjectInputStream(inputStream)
            val result = objectInputStream.readObject()
            objectInputStream.close()
            result
        } catch (e: Exception) {
            Log.w(TAG, "Could not deserialize object, treating as string", e)
            deserializeString(serialized)
        }
    }

    /**
     * Execute a simple Python function (mock implementation)
     */
    fun executeSimpleFunction(functionCode: String, args: List<Any>): Any {
        return try {
            if (!initialize()) {
                throw RuntimeException("Python runtime not initialized")
            }

            Log.d(TAG, "🐍 Executing simple Python function (mock)")
            Log.d(TAG, "📝 Function code: $functionCode")
            Log.d(TAG, "📝 Arguments: $args")

            // Mock execution
            val result = executeMockCode(functionCode, args)
            
            Log.d(TAG, "✅ Simple function execution completed (mock)")
            Log.d(TAG, "📊 Result: $result")
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Simple function execution failed", e)
            throw RuntimeException("Simple function execution failed: ${e.message}")
        }
    }

    /**
     * Check if Python runtime is available
     */
    fun isAvailable(): Boolean {
        return try {
            initialize()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Python runtime not available", e)
            false
        }
    }

    /**
     * Get Python version information
     */
    fun getPythonVersion(): String {
        return try {
            if (!initialize()) {
                return "Python runtime not available"
            }
            
            "Python 3.12.6 (Mock - install Chaquopy for real Python support)"
        } catch (e: Exception) {
            "Python version unknown: ${e.message}"
        }
    }

    /**
     * Get available Python modules
     */
    fun getAvailableModules(): List<String> {
        return try {
            if (!initialize()) {
                return emptyList()
            }
            
            listOf(
                "builtins", "sys", "os", "math", "json", "random",
                "numpy (mock)", "pandas (mock)", "scipy (mock)", "matplotlib (mock)"
            ).sorted()
        } catch (e: Exception) {
            Log.e(TAG, "Could not get available modules", e)
            emptyList()
        }
    }
}
