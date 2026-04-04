package com.crowdio.mcc_phase3.model

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

private const val CACHE_DIR_NAME = "dnn_model_cache"
private const val INDEX_FILE_NAME = "index.json"

/**
 * Persists model partitions in app-scoped storage and tracks metadata by model_partition_id.
 */
class ModelArtifactCache(context: Context) {

    companion object {
        private const val TAG = "ModelArtifactCache"
    }

    private val cacheDir: File = File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }
    private val indexFile: File = File(cacheDir, INDEX_FILE_NAME)

    @Synchronized
    fun putArtifact(
        modelVersionId: String,
        modelPartitionId: String,
        checksumSha256Hex: String,
        bytes: ByteArray,
        modelRuntime: String = "unknown",
        fileExtension: String? = null
    ): ModelArtifactMetadata {
        val calculated = sha256Hex(bytes)
        if (!calculated.equals(checksumSha256Hex, ignoreCase = true)) {
            throw IllegalStateException(
                "Checksum mismatch for partition $modelPartitionId: expected=$checksumSha256Hex actual=$calculated"
            )
        }

        val normalizedRuntime = normalizeRuntime(modelRuntime)
        val normalizedExtension = normalizeFileExtension(fileExtension)
            ?: runtimeToDefaultExtension(normalizedRuntime)
        val fileName = buildArtifactFileName(
            modelVersionId = modelVersionId,
            modelPartitionId = modelPartitionId,
            extension = normalizedExtension
        )
        val outputFile = File(cacheDir, fileName)
        outputFile.writeBytes(bytes)

        val metadata = ModelArtifactMetadata(
            modelVersionId = modelVersionId,
            modelPartitionId = modelPartitionId,
            checksumSha256Hex = checksumSha256Hex.lowercase(),
            localPath = outputFile.absolutePath,
            loadedAtEpochMs = System.currentTimeMillis(),
            modelRuntime = normalizedRuntime
        )

        val index = readIndex()
        index[modelPartitionId] = metadata
        writeIndex(index)

        Log.i(TAG, "Stored model partition $modelPartitionId at ${outputFile.absolutePath}")
        return metadata
    }

    /**
     * Register a downloaded artifact already on disk.
     * Moves the file into the cache directory when possible to avoid extra copies.
     */
    @Synchronized
    fun putArtifactFromFile(
        modelVersionId: String,
        modelPartitionId: String,
        checksumSha256Hex: String,
        sourceFile: File,
        modelRuntime: String = "unknown",
        fileExtension: String? = null,
        actualChecksumSha256Hex: String? = null
    ): ModelArtifactMetadata {
        if (!sourceFile.exists()) {
            throw IllegalStateException("Downloaded model artifact file missing: ${sourceFile.absolutePath}")
        }

        val calculated = actualChecksumSha256Hex?.lowercase() ?: sha256Hex(sourceFile)
        if (!calculated.equals(checksumSha256Hex, ignoreCase = true)) {
            throw IllegalStateException(
                "Checksum mismatch for partition $modelPartitionId: expected=$checksumSha256Hex actual=$calculated"
            )
        }

        val normalizedRuntime = normalizeRuntime(modelRuntime)
        val normalizedExtension = normalizeFileExtension(fileExtension)
            ?: runtimeToDefaultExtension(normalizedRuntime)
        val fileName = buildArtifactFileName(
            modelVersionId = modelVersionId,
            modelPartitionId = modelPartitionId,
            extension = normalizedExtension
        )
        val outputFile = File(cacheDir, fileName)

        if (outputFile.exists()) {
            outputFile.delete()
        }

        val moved = sourceFile.renameTo(outputFile)
        if (!moved) {
            sourceFile.copyTo(outputFile, overwrite = true)
            sourceFile.delete()
        }

        val metadata = ModelArtifactMetadata(
            modelVersionId = modelVersionId,
            modelPartitionId = modelPartitionId,
            checksumSha256Hex = checksumSha256Hex.lowercase(),
            localPath = outputFile.absolutePath,
            loadedAtEpochMs = System.currentTimeMillis(),
            modelRuntime = normalizedRuntime
        )

        val index = readIndex()
        index[modelPartitionId] = metadata
        writeIndex(index)

        Log.i(TAG, "Stored model partition $modelPartitionId at ${outputFile.absolutePath}")
        return metadata
    }

    @Synchronized
    fun getArtifact(modelPartitionId: String): ModelArtifactMetadata? {
        val entry = readIndex()[modelPartitionId] ?: return null
        val file = File(entry.localPath)
        if (!file.exists()) {
            val index = readIndex()
            index.remove(modelPartitionId)
            writeIndex(index)
            Log.w(TAG, "Model file missing for partition $modelPartitionId, cleaned stale index entry")
            return null
        }
        return entry
    }

    @Synchronized
    fun allArtifacts(): List<ModelArtifactMetadata> = readIndex().values.toList()

    /**
     * Remove a single partition from cache and delete its file from disk.
     * Returns true when an index entry existed and was removed.
     */
    @Synchronized
    fun removeArtifact(modelPartitionId: String): Boolean {
        if (modelPartitionId.isBlank()) {
            return false
        }

        val index = readIndex()
        val entry = index.remove(modelPartitionId) ?: return false

        val file = File(entry.localPath)
        if (file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Deleted model file for $modelPartitionId: $deleted (${entry.localPath})")
        }

        writeIndex(index)
        Log.i(TAG, "Removed model partition $modelPartitionId from cache")
        return true
    }

    /**
     * Evict all cached model partitions.
     */
    @Synchronized
    fun evictAll() {
        val index = readIndex()
        index.values.forEach { entry ->
            val file = File(entry.localPath)
            if (file.exists()) {
                file.delete()
            }
        }
        writeIndex(emptyMap())
        Log.i(TAG, "Evicted all model partitions from cache")
    }

    private fun buildArtifactFileName(
        modelVersionId: String,
        modelPartitionId: String,
        extension: String
    ): String {
        val sanitizedVersion = modelVersionId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val sanitizedPartition = modelPartitionId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return "${sanitizedVersion}_${sanitizedPartition}$extension"
    }

    private fun normalizeRuntime(modelRuntime: String?): String {
        val runtime = modelRuntime?.trim()?.lowercase().orEmpty()
        return if (runtime.isBlank()) "unknown" else runtime
    }

    private fun runtimeToDefaultExtension(runtime: String): String {
        return when (runtime) {
            "onnx" -> ".onnx"
            "tflite" -> ".tflite"
            else -> ".bin"
        }
    }

    private fun normalizeFileExtension(fileExtension: String?): String? {
        val ext = fileExtension?.trim()?.lowercase().orEmpty()
        if (ext.isBlank()) {
            return null
        }

        return if (ext.startsWith(".")) ext else ".$ext"
    }

    private fun inferRuntimeFromPath(localPath: String): String {
        val ext = File(localPath).extension.lowercase()
        return when (ext) {
            "onnx" -> "onnx"
            "tflite" -> "tflite"
            else -> "unknown"
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readIndex(): MutableMap<String, ModelArtifactMetadata> {
        if (!indexFile.exists()) {
            return mutableMapOf()
        }

        return try {
            val root = JSONObject(indexFile.readText())
            val items = root.optJSONArray("items") ?: JSONArray()
            val output = mutableMapOf<String, ModelArtifactMetadata>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val metadata = ModelArtifactMetadata(
                    modelVersionId = item.optString("model_version_id", ""),
                    modelPartitionId = item.optString("model_partition_id", ""),
                    checksumSha256Hex = item.optString("checksum", ""),
                    localPath = item.optString("local_path", ""),
                    loadedAtEpochMs = item.optLong("loaded_at", 0L),
                    modelRuntime = item.optString("model_runtime", "")
                        .ifBlank { inferRuntimeFromPath(item.optString("local_path", "")) }
                )
                if (metadata.modelPartitionId.isNotBlank()) {
                    output[metadata.modelPartitionId] = metadata
                }
            }
            output
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read model cache index, starting fresh", e)
            mutableMapOf()
        }
    }

    private fun writeIndex(entries: Map<String, ModelArtifactMetadata>) {
        val root = JSONObject()
        val items = JSONArray()
        entries.values.forEach { entry ->
            items.put(
                JSONObject().apply {
                    put("model_version_id", entry.modelVersionId)
                    put("model_partition_id", entry.modelPartitionId)
                    put("checksum", entry.checksumSha256Hex)
                    put("local_path", entry.localPath)
                    put("loaded_at", entry.loadedAtEpochMs)
                    put("model_runtime", entry.modelRuntime)
                }
            )
        }
        root.put("items", items)
        indexFile.writeText(root.toString())
    }
}

data class ModelArtifactMetadata(
    val modelVersionId: String,
    val modelPartitionId: String,
    val checksumSha256Hex: String,
    val localPath: String,
    val loadedAtEpochMs: Long,
    val modelRuntime: String = "unknown"
)
