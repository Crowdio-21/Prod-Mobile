package com.crowdio.mcc_phase3.execution

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Executes a single TFLite partition using payloads compatible with SDK tensor_transport.
 */
class TFLitePartitionExecutor {

    companion object {
        private const val TAG = "TFLitePartitionExecutor"
    }

    fun execute(modelPath: String, taskArgsJson: String): JSONObject {
        val modelFile = File(modelPath)
        require(modelFile.exists()) { "Model file not found: $modelPath" }

        val modelBuffer = loadModelBuffer(modelFile)
        val inputTransport = extractInputTensor(taskArgsJson)

        Interpreter(modelBuffer).use { interpreter ->
            val inputTensor = interpreter.getInputTensor(0)
            val preparedInputBuffer = convertInputToTensorType(inputTransport, inputTensor)
            val outputTensor = interpreter.getOutputTensor(0)
            val outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())

            interpreter.run(preparedInputBuffer, outputBuffer)

            outputBuffer.rewind()
            val outputBytes = ByteArray(outputBuffer.remaining())
            outputBuffer.get(outputBytes)

            val encodedOutput = encodeTensorPayload(
                rawBytes = outputBytes,
                shape = outputTensor.shape().toList(),
                dtype = outputTensor.dataType().toString().lowercase(),
                compression = "zlib"
            )

            return JSONObject().apply {
                put("transport", "tensor_transport")
                put("tensor_payload", encodedOutput)
                put("model_partition_id", inputTransport.modelPartitionId)
                put("intermediate_feature", JSONObject().apply {
                    put("target_task_id", inputTransport.targetTaskId)
                    put("payload", JSONObject().apply {
                        put("transport", "tensor_transport")
                        put("tensor_payload", encodedOutput)
                    })
                    put("payload_format", "json")
                })
            }
        }
    }

    private fun loadModelBuffer(modelFile: File): ByteBuffer {
        val bytes = modelFile.readBytes()
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }

    private fun extractInputTensor(taskArgsJson: String): InputTensor {
        val argsArray = try {
            JSONArray(taskArgsJson)
        } catch (_: Exception) {
            JSONArray().apply { put(taskArgsJson) }
        }

        val targetTaskId = findTargetTaskId(argsArray)
        val modelPartitionId = findModelPartitionId(argsArray)

        val tensorPayload = findTensorPayload(argsArray)
        if (tensorPayload != null) {
            val dtype = normalizeDtype(tensorPayload.optString("dtype", "float32"))
            val compression = tensorPayload.optString("compression", "none")
            val payloadB64 = tensorPayload.optString("payload_b64", "")
            require(payloadB64.isNotBlank()) { "tensor payload missing payload_b64" }

            val encodedBytes = Base64.decode(payloadB64, Base64.DEFAULT)
            val rawBytes = when (compression) {
                "zlib" -> inflateZlib(encodedBytes)
                "none", "" -> encodedBytes
                else -> throw IllegalArgumentException("Unsupported tensor compression: $compression")
            }

            Log.d(TAG, "Decoded input tensor dtype=$dtype bytes=${rawBytes.size}")
            return InputTensor(rawBytes, dtype, modelPartitionId, targetTaskId)
        }

        val fallback = FloatArray(1) { extractNumericFallback(argsArray) }
        val fallbackBytes = ByteBuffer.allocate(fallback.size * 4).order(ByteOrder.nativeOrder()).apply {
            asFloatBuffer().put(fallback)
        }.array()
        Log.w(TAG, "No tensor payload found in args; using numeric fallback input")
        return InputTensor(fallbackBytes, "float32", modelPartitionId, targetTaskId)
    }

    private fun findTensorPayload(node: Any?): JSONObject? {
        return when (node) {
            is JSONObject -> {
                if (isTensorPayloadObject(node)) {
                    node
                } else {
                    val transport = node.optString("transport", "")
                    if (transport == "tensor_transport") {
                        val candidates = listOf("tensor_payload", "payload", "tensor", "data")
                        for (key in candidates) {
                            val candidate = node.opt(key)
                            findTensorPayload(candidate)?.let { return it }
                        }
                    }

                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        findTensorPayload(node.opt(key))?.let { return it }
                    }
                    null
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findTensorPayload(node.opt(i))?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun isTensorPayloadObject(node: JSONObject): Boolean {
        return node.has("dtype") && node.has("payload_b64")
    }

    private fun extractNumericFallback(argsArray: JSONArray): Float {
        for (i in 0 until argsArray.length()) {
            val value = argsArray.opt(i)
            when (value) {
                is Number -> return value.toFloat()
                is JSONObject -> {
                    if (value.has("value")) {
                        val candidate = value.opt("value")
                        if (candidate is Number) return candidate.toFloat()
                    }
                }
            }
        }
        return 0f
    }

    private fun findTargetTaskId(node: Any?): String? {
        return when (node) {
            is JSONObject -> {
                val direct = node.optString("target_task_id", "")
                if (direct.isNotBlank()) {
                    direct
                } else {
                    val nestedTargets = node.optJSONArray("target_task_ids")
                    if (nestedTargets != null && nestedTargets.length() > 0) {
                        nestedTargets.optString(0, "").ifBlank { null }
                    } else {
                        val keys = node.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            findTargetTaskId(node.opt(key))?.let { return it }
                        }
                        null
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findTargetTaskId(node.opt(i))?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun findModelPartitionId(node: Any?): String? {
        return when (node) {
            is JSONObject -> {
                val direct = node.optString("model_partition_id", "")
                if (direct.isNotBlank()) {
                    direct
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        findModelPartitionId(node.opt(key))?.let { return it }
                    }
                    null
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findModelPartitionId(node.opt(i))?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun encodeTensorPayload(
        rawBytes: ByteArray,
        shape: List<Int>,
        dtype: String,
        compression: String
    ): JSONObject {
        val encodedBytes = if (compression == "zlib") deflateZlib(rawBytes) else rawBytes

        return JSONObject().apply {
            put("dtype", dtype)
            put("shape", JSONArray(shape))
            put("order", "C")
            put("compression", compression)
            put("payload_b64", Base64.encodeToString(encodedBytes, Base64.NO_WRAP))
        }
    }

    private fun convertInputToTensorType(input: InputTensor, inputTensor: org.tensorflow.lite.Tensor): ByteBuffer {
        val expectedBytes = inputTensor.numBytes()
        val targetType = inputTensor.dataType()
        val quant = inputTensor.quantizationParams()
        val scale = quant.scale
        val zeroPoint = quant.zeroPoint

        if (input.dtype == tfliteToTransportDtype(targetType) && input.rawBytes.size == expectedBytes) {
            return ByteBuffer.allocateDirect(expectedBytes).order(ByteOrder.nativeOrder()).apply {
                put(input.rawBytes)
                rewind()
            }
        }

        val inputFloats = decodeAsFloatArray(input.rawBytes, input.dtype)
        val converted = encodeFromFloatArray(inputFloats, targetType, scale, zeroPoint)
        return adaptInputBuffer(converted, expectedBytes)
    }

    private fun adaptInputBuffer(sourceBytes: ByteArray, expectedBytes: Int): ByteBuffer {
        val actualBytes = sourceBytes.size
        val source = ByteBuffer.wrap(sourceBytes).order(ByteOrder.nativeOrder())
        if (actualBytes == expectedBytes) {
            return ByteBuffer.allocateDirect(expectedBytes).order(ByteOrder.nativeOrder()).apply {
                put(sourceBytes)
                rewind()
            }
        }

        val adapted = ByteBuffer.allocateDirect(expectedBytes).order(ByteOrder.nativeOrder())
        if (actualBytes > expectedBytes) {
            val truncated = ByteArray(expectedBytes)
            source.get(truncated)
            adapted.put(truncated)
            Log.w(TAG, "Input tensor larger than expected ($actualBytes > $expectedBytes); truncating")
        } else {
            val original = ByteArray(actualBytes)
            source.get(original)
            adapted.put(original)
            if (actualBytes < expectedBytes) {
                val padding = ByteArray(expectedBytes - actualBytes)
                adapted.put(padding)
                Log.w(TAG, "Input tensor smaller than expected ($actualBytes < $expectedBytes); zero-padding")
            }
        }

        adapted.rewind()
        return adapted
    }

    private fun decodeAsFloatArray(rawBytes: ByteArray, dtype: String): FloatArray {
        val normalized = normalizeDtype(dtype)
        val byteBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.nativeOrder())

        return when (normalized) {
            "float32" -> {
                val fb: FloatBuffer = byteBuffer.asFloatBuffer()
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
            "float64" -> {
                val db: DoubleBuffer = byteBuffer.asDoubleBuffer()
                FloatArray(db.remaining()) { idx -> db.get(idx).toFloat() }
            }
            "int8" -> FloatArray(rawBytes.size) { idx -> rawBytes[idx].toFloat() }
            "uint8" -> FloatArray(rawBytes.size) { idx -> (rawBytes[idx].toInt() and 0xFF).toFloat() }
            "int16" -> {
                val sb: ShortBuffer = byteBuffer.asShortBuffer()
                FloatArray(sb.remaining()) { idx -> sb.get(idx).toFloat() }
            }
            "int32" -> {
                val ib: IntBuffer = byteBuffer.asIntBuffer()
                FloatArray(ib.remaining()) { idx -> ib.get(idx).toFloat() }
            }
            "int64" -> {
                val lb: LongBuffer = byteBuffer.asLongBuffer()
                FloatArray(lb.remaining()) { idx -> lb.get(idx).toFloat() }
            }
            else -> throw IllegalArgumentException("Unsupported input tensor dtype: $dtype")
        }
    }

    private fun encodeFromFloatArray(values: FloatArray, targetType: DataType, scale: Float, zeroPoint: Int): ByteArray {
        return when (targetType) {
            DataType.FLOAT32 -> {
                val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
                values.forEach { bb.putFloat(it) }
                bb.array()
            }
            DataType.INT32 -> {
                val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
                values.forEach { bb.putInt(it.toInt()) }
                bb.array()
            }
            DataType.INT64 -> {
                val bb = ByteBuffer.allocate(values.size * 8).order(ByteOrder.nativeOrder())
                values.forEach { bb.putLong(it.toLong()) }
                bb.array()
            }
            DataType.UINT8 -> {
                val bytes = ByteArray(values.size)
                for (i in values.indices) {
                    val q = quantize(values[i], scale, zeroPoint).coerceIn(0, 255)
                    bytes[i] = (q and 0xFF).toByte()
                }
                bytes
            }
            DataType.INT8 -> {
                val bytes = ByteArray(values.size)
                for (i in values.indices) {
                    val q = quantize(values[i], scale, zeroPoint).coerceIn(-128, 127)
                    bytes[i] = q.toByte()
                }
                bytes
            }
            DataType.BOOL -> {
                val bytes = ByteArray(values.size)
                for (i in values.indices) {
                    bytes[i] = if (values[i] != 0f) 1 else 0
                }
                bytes
            }
            else -> throw IllegalArgumentException("Unsupported TFLite input data type: $targetType")
        }
    }

    private fun quantize(value: Float, scale: Float, zeroPoint: Int): Int {
        if (scale == 0f) {
            return value.toInt()
        }
        return (value / scale + zeroPoint).toInt()
    }

    private fun normalizeDtype(dtype: String): String {
        return dtype.lowercase().replace(" ", "")
    }

    private fun tfliteToTransportDtype(type: DataType): String {
        return when (type) {
            DataType.FLOAT32 -> "float32"
            DataType.INT32 -> "int32"
            DataType.INT64 -> "int64"
            DataType.INT8 -> "int8"
            DataType.UINT8 -> "uint8"
            DataType.BOOL -> "bool"
            else -> "float32"
        }
    }

    private fun deflateZlib(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()

        val output = ByteArrayOutputStream(raw.size)
        val chunk = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(chunk)
            if (count <= 0) {
                break
            }
            output.write(chunk, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    private fun inflateZlib(encoded: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(encoded)
        val output = ByteArrayOutputStream(encoded.size * 2)
        val chunk = ByteArray(4096)
        while (!inflater.finished()) {
            val count = inflater.inflate(chunk)
            if (count == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            } else {
                output.write(chunk, 0, count)
            }
        }
        inflater.end()
        return output.toByteArray()
    }

    private data class InputTensor(
        val rawBytes: ByteArray,
        val dtype: String,
        val modelPartitionId: String?,
        val targetTaskId: String?
    )
}
