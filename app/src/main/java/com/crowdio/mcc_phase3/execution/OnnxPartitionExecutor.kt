package com.crowdio.mcc_phase3.execution

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Executes a single ONNX partition using payloads compatible with SDK tensor_transport.
 *
 * Sessions are cached per model path so consecutive inferences on the same
 * partition skip the expensive session-creation step.
 */
class OnnxPartitionExecutor {

    companion object {
        private const val TAG = "OnnxPartitionExecutor"
    }

    private val sessionCache = ConcurrentHashMap<String, OrtSession>()
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /**
     * Get or create a cached [OrtSession] for the given [modelPath].
     */
    private fun getOrCreateSession(modelPath: String): OrtSession {
        return sessionCache.getOrPut(modelPath) {
            Log.d(TAG, "Creating new OrtSession for $modelPath")
            val sessionOptions = OrtSession.SessionOptions()
            env.createSession(modelPath, sessionOptions)
        }
    }

    /**
     * Close and remove the cached session for [modelPath].
     * Called when UNLOAD_MODEL is received.
     */
    fun closeSession(modelPath: String) {
        sessionCache.remove(modelPath)?.let { session ->
            Log.d(TAG, "Closing cached OrtSession for $modelPath")
            session.close()
        }
    }

    /**
     * Close all cached sessions. Call on worker shutdown.
     */
    fun closeAllSessions() {
        sessionCache.keys().toList().forEach { closeSession(it) }
    }

    fun execute(modelPath: String, taskArgsJson: String): JSONObject {
        val modelFile = File(modelPath)
        require(modelFile.exists()) { "Model file not found: $modelPath" }

        val inputTransport = extractInputTensor(taskArgsJson)
        val session = getOrCreateSession(modelPath)

        val inputName = session.inputNames.firstOrNull()
            ?: throw IllegalStateException("ONNX session has no input tensors")

        val inputTensor = createInputTensor(
            env = env,
            session = session,
            inputName = inputName,
            inputTransport = inputTransport
        )

        inputTensor.use { onnxInput ->
            val outputs = session.run(mapOf(inputName to onnxInput))
            try {
                val iterator = outputs.iterator()
                if (!iterator.hasNext()) {
                    throw IllegalStateException("ONNX session returned no outputs")
                }

                val firstOutput = iterator.next().value
                val outputTensor = extractOutputTensorData(firstOutput)

                val encodedOutput = encodeTensorPayload(
                    rawBytes = outputTensor.rawBytes,
                    shape = outputTensor.shape.map { it.toInt() },
                    dtype = outputTensor.dtype,
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
            } finally {
                outputs.close()
            }
        }
    }

    private fun createInputTensor(
        env: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        inputTransport: InputTensor
    ): OnnxTensor {
        val values = decodeAsFloatArray(
            rawBytes = inputTransport.rawBytes,
            dtype = inputTransport.dtype
        )

        val tensorInfo = session.inputInfo[inputName]?.info as? TensorInfo
        val targetShape = resolveInputShape(
            tensorInfo = tensorInfo,
            payloadShape = inputTransport.shape,
            valueCount = values.size
        )

        val targetType = tensorInfo?.type ?: OnnxJavaType.FLOAT

        return when (targetType) {
            OnnxJavaType.FLOAT -> {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(values), targetShape)
            }
            OnnxJavaType.DOUBLE -> {
                val asDouble = DoubleArray(values.size) { idx -> values[idx].toDouble() }
                OnnxTensor.createTensor(env, DoubleBuffer.wrap(asDouble), targetShape)
            }
            OnnxJavaType.INT32 -> {
                val asInt = IntArray(values.size) { idx -> values[idx].toInt() }
                OnnxTensor.createTensor(env, IntBuffer.wrap(asInt), targetShape)
            }
            OnnxJavaType.INT64 -> {
                val asLong = decodeAsLongArray(
                    rawBytes = inputTransport.rawBytes,
                    dtype = inputTransport.dtype
                )
                OnnxTensor.createTensor(env, LongBuffer.wrap(asLong), targetShape)
            }
            else -> {
                Log.w(TAG, "Unsupported ONNX input type $targetType, falling back to float32")
                OnnxTensor.createTensor(env, FloatBuffer.wrap(values), targetShape)
            }
        }
    }

    private fun resolveInputShape(
        tensorInfo: TensorInfo?,
        payloadShape: LongArray?,
        valueCount: Int
    ): LongArray {
        if (payloadShape != null && payloadShape.isNotEmpty() && payloadShape.all { it > 0L }) {
            val payloadElements = payloadShape.fold(1L) { acc, dim -> acc * dim }
            if (payloadElements == valueCount.toLong()) {
                return payloadShape
            }
        }

        val modelShape = tensorInfo?.shape ?: longArrayOf()
        if (modelShape.isNotEmpty()) {
            val fixedShape = modelShape.map { if (it <= 0L) 1L else it }.toLongArray()
            val fixedElements = fixedShape.fold(1L) { acc, dim -> acc * dim }
            if (fixedElements == valueCount.toLong()) {
                return fixedShape
            }
        }

        return longArrayOf(valueCount.toLong())
    }

    private fun extractOutputTensorData(outputValue: Any?): OutputTensor {
        if (outputValue !is OnnxTensor) {
            throw IllegalStateException("ONNX output is not a tensor: ${outputValue?.javaClass?.simpleName}")
        }

        val flattened = mutableListOf<Float>()
        flattenValue(outputValue.value, flattened)
        if (flattened.isEmpty()) {
            flattened.add(0f)
        }

        val rawBytes = ByteBuffer.allocate(flattened.size * 4)
            .order(ByteOrder.nativeOrder())
            .apply {
                flattened.forEach { putFloat(it) }
            }
            .array()

        val valueShape = inferShapeFromValue(outputValue.value)

        val tensorInfo = outputValue.info as? TensorInfo
        val infoShape = tensorInfo?.shape?.takeIf { it.isNotEmpty() } ?: longArrayOf()
        val positiveInfoShape = infoShape.map { if (it <= 0L) 1L else it }.toLongArray()
        val infoElements = if (positiveInfoShape.isEmpty()) 0L else positiveInfoShape.fold(1L) { acc, dim -> acc * dim }

        val hasValueShape = valueShape != null && valueShape.isNotEmpty()
        val valueElements = if (!hasValueShape) 0L else valueShape!!.fold(1L) { acc, dim -> acc * dim }

        val resolvedShape = if (hasValueShape && valueElements == flattened.size.toLong()) {
            valueShape!!
        } else if (infoElements == flattened.size.toLong()) {
            positiveInfoShape
        } else {
            longArrayOf(flattened.size.toLong())
        }

        return OutputTensor(
            rawBytes = rawBytes,
            shape = resolvedShape,
            dtype = "float32"
        )
    }

    private fun flattenValue(value: Any?, output: MutableList<Float>) {
        when (value) {
            null -> Unit
            is Number -> output.add(value.toFloat())
            is FloatArray -> value.forEach { output.add(it) }
            is DoubleArray -> value.forEach { output.add(it.toFloat()) }
            is IntArray -> value.forEach { output.add(it.toFloat()) }
            is LongArray -> value.forEach { output.add(it.toFloat()) }
            is ShortArray -> value.forEach { output.add(it.toFloat()) }
            is ByteArray -> value.forEach { output.add(it.toFloat()) }
            is Array<*> -> value.forEach { flattenValue(it, output) }
            else -> {
                // Ignore non-numeric nodes.
            }
        }
    }

    private fun inferShapeFromValue(value: Any?): LongArray? {
        return when (value) {
            null -> null
            is FloatArray -> longArrayOf(value.size.toLong())
            is DoubleArray -> longArrayOf(value.size.toLong())
            is IntArray -> longArrayOf(value.size.toLong())
            is LongArray -> longArrayOf(value.size.toLong())
            is ShortArray -> longArrayOf(value.size.toLong())
            is ByteArray -> longArrayOf(value.size.toLong())
            is Array<*> -> {
                val outer = value.size.toLong()
                if (value.isEmpty()) {
                    longArrayOf(outer)
                } else {
                    val innerShape = inferShapeFromValue(value[0])
                    if (innerShape == null) {
                        longArrayOf(outer)
                    } else {
                        LongArray(innerShape.size + 1).apply {
                            this[0] = outer
                            for (idx in innerShape.indices) {
                                this[idx + 1] = innerShape[idx]
                            }
                        }
                    }
                }
            }
            else -> null
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

            val shape = parseTensorShape(tensorPayload.optJSONArray("shape"))

            return InputTensor(
                rawBytes = rawBytes,
                dtype = dtype,
                shape = shape,
                modelPartitionId = modelPartitionId,
                targetTaskId = targetTaskId
            )
        }

        val fallback = floatArrayOf(extractNumericFallback(argsArray))
        val fallbackBytes = ByteBuffer.allocate(fallback.size * 4)
            .order(ByteOrder.nativeOrder())
            .apply { asFloatBuffer().put(fallback) }
            .array()

        Log.w(TAG, "No tensor payload found in args; using numeric fallback input")
        return InputTensor(
            rawBytes = fallbackBytes,
            dtype = "float32",
            shape = longArrayOf(fallback.size.toLong()),
            modelPartitionId = modelPartitionId,
            targetTaskId = targetTaskId
        )
    }

    private fun parseTensorShape(shapeArray: JSONArray?): LongArray? {
        if (shapeArray == null || shapeArray.length() == 0) {
            return null
        }

        val dims = LongArray(shapeArray.length())
        for (i in 0 until shapeArray.length()) {
            dims[i] = shapeArray.optLong(i, -1L)
        }
        return dims
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
                        if (candidate is Number) {
                            return candidate.toFloat()
                        }
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

    private fun decodeAsLongArray(rawBytes: ByteArray, dtype: String): LongArray {
        val normalized = normalizeDtype(dtype)
        val byteBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.nativeOrder())

        return when (normalized) {
            "int64" -> {
                val lb: LongBuffer = byteBuffer.asLongBuffer()
                LongArray(lb.remaining()).also { lb.get(it) }
            }
            "int32" -> {
                val ib: IntBuffer = byteBuffer.asIntBuffer()
                LongArray(ib.remaining()) { idx -> ib.get(idx).toLong() }
            }
            "int16" -> {
                val sb: ShortBuffer = byteBuffer.asShortBuffer()
                LongArray(sb.remaining()) { idx -> sb.get(idx).toLong() }
            }
            "int8" -> LongArray(rawBytes.size) { idx -> rawBytes[idx].toLong() }
            "uint8" -> LongArray(rawBytes.size) { idx -> (rawBytes[idx].toInt() and 0xFF).toLong() }
            "float32" -> {
                val fb: FloatBuffer = byteBuffer.asFloatBuffer()
                LongArray(fb.remaining()) { idx -> fb.get(idx).toLong() }
            }
            "float64" -> {
                val db: DoubleBuffer = byteBuffer.asDoubleBuffer()
                LongArray(db.remaining()) { idx -> db.get(idx).toLong() }
            }
            else -> throw IllegalArgumentException("Unsupported input tensor dtype for int64 conversion: $dtype")
        }
    }

    private fun normalizeDtype(dtype: String): String {
        return dtype.trim().lowercase().replace(" ", "")
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

    private fun deflateZlib(rawBytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        return try {
            deflater.setInput(rawBytes)
            deflater.finish()
            val buffer = ByteArray(8192)
            ByteArrayOutputStream().use { out ->
                while (!deflater.finished()) {
                    val read = deflater.deflate(buffer)
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } finally {
            deflater.end()
        }
    }

    private fun inflateZlib(compressed: ByteArray): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val buffer = ByteArray(8192)
            ByteArrayOutputStream().use { out ->
                while (!inflater.finished()) {
                    val read = inflater.inflate(buffer)
                    if (read == 0 && inflater.needsInput()) {
                        break
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } finally {
            inflater.end()
        }
    }

    private data class InputTensor(
        val rawBytes: ByteArray,
        val dtype: String,
        val shape: LongArray?,
        val modelPartitionId: String?,
        val targetTaskId: String?
    )

    private data class OutputTensor(
        val rawBytes: ByteArray,
        val shape: LongArray,
        val dtype: String
    )
}
