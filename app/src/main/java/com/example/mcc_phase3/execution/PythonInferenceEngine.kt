package com.example.mcc_phase3.execution

import com.chaquo.python.Python
import com.chaquo.python.PyObject

class PythonInferenceEngine {
    private val py = Python.getInstance()
    private val module: PyObject = py.getModule("dnn_inference")

    /**
     * Execute a single layer slice via Python/TFLite.
     * Input/output as NumPy-backed byte arrays for minimal copy overhead.
     */
    fun runLayerSlice(layerId: String, inputBytes: ByteArray, params: Map<String, Any>): ByteArray {
        val npModule = py.getModule("numpy")

        // Convert byte array → NumPy array (zero-copy via Chaquopy)
        val inputNp = npModule.callAttr("frombuffer", inputBytes, "float32")

        // Call Python: output = run_layer(layer_id, input_np, params)
        val result: PyObject = module.callAttr(
            "run_layer", layerId, inputNp,
            py.builtins.callAttr("dict", params)
        )

        // Convert NumPy output back to byte array
        return result.callAttr("tobytes").toJava(ByteArray::class.java)
    }
}
