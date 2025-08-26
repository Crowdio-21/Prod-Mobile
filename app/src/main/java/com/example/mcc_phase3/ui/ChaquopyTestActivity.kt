package com.example.mcc_phase3.ui

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.mcc_phase3.R
import com.example.mcc_phase3.worker.PythonExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Test activity for Chaquopy Python execution
 * This activity allows testing Python code execution on the device
 */
class ChaquopyTestActivity : AppCompatActivity() {

    private lateinit var pythonExecutor: PythonExecutor
    private lateinit var resultTextView: TextView
    private lateinit var statusTextView: TextView

    companion object {
        private const val TAG = "ChaquopyTestActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chaquopy_test)

        // Initialize UI components
        resultTextView = findViewById(R.id.resultTextView)
        statusTextView = findViewById(R.id.statusTextView)

        // Initialize Python executor
        pythonExecutor = PythonExecutor(this)

        // Set up test buttons
        setupTestButtons()

        // Check Python availability
        checkPythonAvailability()
    }

    private fun setupTestButtons() {
        findViewById<Button>(R.id.btnTestBasic).setOnClickListener {
            testBasicPythonFunction()
        }

        findViewById<Button>(R.id.btnTestNumpy).setOnClickListener {
            testNumpyFunction()
        }

        findViewById<Button>(R.id.btnTestPandas).setOnClickListener {
            testPandasFunction()
        }

        findViewById<Button>(R.id.btnTestSerialized).setOnClickListener {
            testSerializedCode()
        }

        findViewById<Button>(R.id.btnCheckModules).setOnClickListener {
            checkAvailableModules()
        }
    }

    private fun checkPythonAvailability() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val isAvailable = withContext(Dispatchers.IO) {
                    pythonExecutor.isAvailable()
                }
                
                val version = pythonExecutor.getPythonVersion()
                
                statusTextView.text = if (isAvailable) {
                    "✅ Python Available: $version"
                } else {
                    "❌ Python Not Available"
                }
                
                Log.d(TAG, "Python availability: $isAvailable, Version: $version")
            } catch (e: Exception) {
                statusTextView.text = "❌ Error checking Python: ${e.message}"
                Log.e(TAG, "Error checking Python availability", e)
            }
        }
    }

    private fun testBasicPythonFunction() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                resultTextView.text = "Testing basic Python function..."
                
                val result = withContext(Dispatchers.IO) {
                    pythonExecutor.executeSimpleFunction(
                        """
                        def add_numbers(a, b):
                            return a + b
                        """,
                        listOf(5, 3)
                    )
                }
                
                resultTextView.text = "Basic function result: $result"
                Log.d(TAG, "Basic function test result: $result")
                
            } catch (e: Exception) {
                resultTextView.text = "❌ Basic function test failed: ${e.message}"
                Log.e(TAG, "Basic function test failed", e)
            }
        }
    }

    private fun testNumpyFunction() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                resultTextView.text = "Testing NumPy function..."
                
                val result = withContext(Dispatchers.IO) {
                    pythonExecutor.executeSimpleFunction(
                        """
                        import numpy as np
                        
                        def numpy_test(data):
                            arr = np.array(data)
                            return {
                                'mean': float(np.mean(arr)),
                                'std': float(np.std(arr)),
                                'sum': float(np.sum(arr))
                            }
                        """,
                        listOf(listOf(1, 2, 3, 4, 5))
                    )
                }
                
                resultTextView.text = "NumPy function result: $result"
                Log.d(TAG, "NumPy function test result: $result")
                
            } catch (e: Exception) {
                resultTextView.text = "❌ NumPy function test failed: ${e.message}"
                Log.e(TAG, "NumPy function test failed", e)
            }
        }
    }

    private fun testPandasFunction() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                resultTextView.text = "Testing Pandas function..."
                
                val result = withContext(Dispatchers.IO) {
                    pythonExecutor.executeSimpleFunction(
                        """
                        import pandas as pd
                        
                        def pandas_test(data):
                            df = pd.DataFrame(data)
                            return {
                                'shape': df.shape,
                                'columns': df.columns.tolist(),
                                'sum': df.sum().to_dict()
                            }
                        """,
                        listOf(mapOf("a" to listOf(1, 2, 3), "b" to listOf(4, 5, 6)))
                    )
                }
                
                resultTextView.text = "Pandas function result: $result"
                Log.d(TAG, "Pandas function test result: $result")
                
            } catch (e: Exception) {
                resultTextView.text = "❌ Pandas function test failed: ${e.message}"
                Log.e(TAG, "Pandas function test failed", e)
            }
        }
    }

    private fun testSerializedCode() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                resultTextView.text = "Testing serialized code execution..."
                
                // Simulate serialized code from backend
                val serializedCode = "ZGVmIG11bHRpcGx5KGEsIGIpOgogICAgcmV0dXJuIGEgKiBi"
                val serializedArgs = "gAJLAUsC"
                
                val result = withContext(Dispatchers.IO) {
                    pythonExecutor.executePythonCode(serializedCode, serializedArgs)
                }
                
                resultTextView.text = "Serialized code result: $result"
                Log.d(TAG, "Serialized code test result: $result")
                
            } catch (e: Exception) {
                resultTextView.text = "❌ Serialized code test failed: ${e.message}"
                Log.e(TAG, "Serialized code test failed", e)
            }
        }
    }

    private fun checkAvailableModules() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                resultTextView.text = "Checking available modules..."
                
                val modules = withContext(Dispatchers.IO) {
                    pythonExecutor.getAvailableModules()
                }
                
                val moduleList = modules.take(20).joinToString(", ")
                resultTextView.text = "Available modules (first 20): $moduleList"
                Log.d(TAG, "Available modules: ${modules.size} total")
                
            } catch (e: Exception) {
                resultTextView.text = "❌ Module check failed: ${e.message}"
                Log.e(TAG, "Module check failed", e)
            }
        }
    }
}
