package com.example.mcc_phase3.execution

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool

class ModelRepository(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
    .build()
) {

    companion object {
        private const val TAG = "ModelRepository"
        private const val DEFAULT_CACHE_DIR = "worker_model_cache"
        private const val DEFAULT_MAX_CACHE_BYTES = 1536L * 1024L * 1024L // 1.5 GiB
    }

    enum class ModelSource {
        CACHE,
        SHARED,
        DOWNLOAD
    }

    data class ResolvedModel(
        val file: File,
        val source: ModelSource,
        val cacheHit: Boolean,
        val modelLoadMs: Long,
        val downloadMs: Long?
    )

    data class ResolveRequest(
        val modelId: String,
        val partitionIdx: Int,
        val modelManifest: JSONObject?,
        val modelStoreDir: String?,
        val modelBaseUrl: String?,
        val workerModelCacheDir: String?
    )

    private data class PartitionSpec(
        val fileName: String,
        val sha256: String?,
        val urlPath: String?,
        val sharedPath: String?
    )

    private data class DownloadOutcome(
        val file: File?,
        val attempts: List<String>
    )

    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun cleanupOnStartup() {
        withContext(Dispatchers.IO) {
            val root = cacheRoot(DEFAULT_CACHE_DIR)
            if (!root.exists()) return@withContext
            cleanupStaleTemps(root)
            enforceMaxCacheSize(root, DEFAULT_MAX_CACHE_BYTES)
        }
    }

    suspend fun cleanupForLowStorage() {
        withContext(Dispatchers.IO) {
            val root = cacheRoot(DEFAULT_CACHE_DIR)
            if (!root.exists()) return@withContext
            cleanupStaleTemps(root)
            // Aggressive cleanup under pressure: keep at most half of max.
            enforceMaxCacheSize(root, DEFAULT_MAX_CACHE_BYTES / 2)
        }
    }

    suspend fun resolvePartition(request: ResolveRequest): ResolvedModel {
        return withContext(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            val modelId = request.modelId.trim()
            if (modelId.isEmpty()) {
                throw IllegalArgumentException("model_id is required")
            }

            val effectiveManifest = request.modelManifest ?: fetchRemoteManifest(request.modelBaseUrl, modelId)
            val partition = resolvePartitionSpec(effectiveManifest, request.partitionIdx)
            val cacheDirName = request.workerModelCacheDir?.takeIf { it.isNotBlank() } ?: DEFAULT_CACHE_DIR
            val cacheDir = File(cacheRoot(cacheDirName), sanitizePathSegment(modelId)).apply { mkdirs() }
            val localFile = File(cacheDir, partition.fileName)
            val lockKey = "$modelId/${partition.fileName}"
            val lock = fileLocks.getOrPut(lockKey) { Mutex() }

            lock.withLock {
                // 1) Local cache
                if (localFile.exists()) {
                    if (looksLikeTfliteFlatbuffer(localFile) && verifySha256(localFile, partition.sha256)) {
                        localFile.setLastModified(System.currentTimeMillis())
                        return@withLock ResolvedModel(
                            file = localFile,
                            source = ModelSource.CACHE,
                            cacheHit = true,
                            modelLoadMs = SystemClock.elapsedRealtime() - startedAt,
                            downloadMs = null
                        )
                    }
                    localFile.delete()
                }

                // 2) Shared path if reachable on this device
                findSharedCandidate(request.modelStoreDir, modelId, partition)?.let { sharedFile ->
                    if (sharedFile.exists() && looksLikeTfliteFlatbuffer(sharedFile) && verifySha256(sharedFile, partition.sha256)) {
                        copyIntoCacheAtomic(sharedFile, localFile)
                        localFile.setLastModified(System.currentTimeMillis())
                        maybeTrimCache(cacheDirName)
                        return@withLock ResolvedModel(
                            file = localFile,
                            source = ModelSource.SHARED,
                            cacheHit = false,
                            modelLoadMs = SystemClock.elapsedRealtime() - startedAt,
                            downloadMs = null
                        )
                    }
                }

                // 3) HTTP download via model_base_url
                val downloadStart = SystemClock.elapsedRealtime()
                val outcome = downloadToCache(request.modelBaseUrl, modelId, partition, localFile)
                val downloaded = outcome.file
                if (downloaded != null) {
                    val downloadMs = SystemClock.elapsedRealtime() - downloadStart
                    if (!looksLikeTfliteFlatbuffer(downloaded)) {
                        downloaded.delete()
                        throw IOException("Downloaded file is not a valid TFLite flatbuffer: ${partition.fileName}")
                    }
                    if (!verifySha256(downloaded, partition.sha256)) {
                        downloaded.delete()
                        throw IOException("Checksum mismatch after download for ${partition.fileName}")
                    }

                    downloaded.setLastModified(System.currentTimeMillis())
                    maybeTrimCache(cacheDirName)
                    return@withLock ResolvedModel(
                        file = downloaded,
                        source = ModelSource.DOWNLOAD,
                        cacheHit = false,
                        modelLoadMs = SystemClock.elapsedRealtime() - startedAt,
                        downloadMs = downloadMs
                    )
                }

                throw IOException(
                    "Unable to resolve model partition ${partition.fileName} " +
                        "(model_id=$modelId, partition_idx=${request.partitionIdx}) from cache/shared/download; " +
                        "download_attempts=${outcome.attempts.joinToString(" || ")}"
                )
            }
        }
    }

    /**
     * Cheap corruption guard: verify that a candidate .tflite file looks like a FlatBuffer
     * with the standard identifier "TFL3" at byte offset 4.
     *
     * This prevents reusing partially-downloaded/corrupt cache entries when the manifest
     * doesn't provide a sha256.
     */
    private fun looksLikeTfliteFlatbuffer(file: File): Boolean {
        return try {
            if (!file.isFile) return false
            if (file.length() < 8) return false
            file.inputStream().use { input ->
                val header = ByteArray(8)
                val read = input.read(header)
                if (read < 8) return false
                // FlatBuffer file identifier is bytes 4..7.
                header[4].toInt().toChar() == 'T' &&
                    header[5].toInt().toChar() == 'F' &&
                    header[6].toInt().toChar() == 'L' &&
                    header[7].toInt().toChar() == '3'
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchRemoteManifest(modelBaseUrl: String?, modelId: String): JSONObject? {
        val base = modelBaseUrl?.trim().orEmpty()
        if (base.isEmpty()) return null

        val modelDirs = candidateModelDirs(modelId)
        val candidates = LinkedHashSet<String>()
        modelDirs.forEach { dir ->
            candidates.add(joinUrl(base, "$dir/manifest.json"))
        }
        candidates.add(joinUrl(base, "manifest.json"))

        for (url in candidates) {
            val request = Request.Builder().url(url).get().build()
            try {
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.d(TAG, "Remote manifest miss: $url (HTTP ${resp.code})")
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) {
                        Log.d(TAG, "Remote manifest empty: $url")
                        return@use
                    }
                    return JSONObject(body)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Remote manifest fetch failed: $url (${e.message})")
            }
        }
        return null
    }

    private fun cacheRoot(cacheDirName: String): File {
        return File(context.filesDir, sanitizeRelativeDir(cacheDirName)).apply { mkdirs() }
    }

    private fun maybeTrimCache(cacheDirName: String) {
        val root = cacheRoot(cacheDirName)
        enforceMaxCacheSize(root, DEFAULT_MAX_CACHE_BYTES)
    }

    private fun cleanupStaleTemps(root: File) {
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".tmp", ignoreCase = true) }
            .forEach { it.delete() }
    }

    private fun enforceMaxCacheSize(root: File, maxBytes: Long) {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return

        val byOldestAccess = files.sortedBy { it.lastModified() }
        for (file in byOldestAccess) {
            if (total <= maxBytes) break
            val len = file.length()
            if (file.delete()) {
                total -= len
            }
        }
    }

    private fun resolvePartitionSpec(manifest: JSONObject?, partitionIdx: Int): PartitionSpec {
        val fallbackFile = "partition_${partitionIdx}.tflite"
        if (manifest == null) {
            return PartitionSpec(fileName = fallbackFile, sha256 = null, urlPath = null, sharedPath = null)
        }

        val fromArray = manifest.optJSONArray("partitions")?.let { partitions ->
            extractFromPartitionsArray(partitions, partitionIdx)
        }
        if (fromArray != null) return fromArray

        val fromMap = manifest.optJSONObject("partitions")?.let { partitionsMap ->
            extractFromPartitionsMap(partitionsMap, partitionIdx)
        }
        if (fromMap != null) return fromMap

        return PartitionSpec(
            fileName = fallbackFile,
            sha256 = findGlobalSha(manifest, fallbackFile, partitionIdx),
            urlPath = null,
            sharedPath = null
        )
    }

    private fun extractFromPartitionsArray(partitions: JSONArray, partitionIdx: Int): PartitionSpec? {
        var selected: JSONObject? = null
        for (i in 0 until partitions.length()) {
            val obj = partitions.optJSONObject(i) ?: continue
            val idx = when {
                obj.has("partition_idx") -> obj.optInt("partition_idx", -1)
                obj.has("partition_index") -> obj.optInt("partition_index", -1)
                obj.has("index") -> obj.optInt("index", -1)
                else -> i
            }
            if (idx == partitionIdx) {
                selected = obj
                break
            }
        }

        if (selected == null && partitionIdx in 0 until partitions.length()) {
            selected = partitions.optJSONObject(partitionIdx)
        }
        selected ?: return null

        val fileName = selected.optString("filename")
            .ifBlank { selected.optString("file_name") }
            .ifBlank { selected.optString("name") }
            .ifBlank { "partition_${partitionIdx}.tflite" }

        val sha = selected.optString("sha256")
            .ifBlank { selected.optString("checksum") }
            .ifBlank { selected.optString("hash") }
            .ifBlank { null }

        val url = selected.optString("url")
            .ifBlank { selected.optString("download_url") }
            .ifBlank { null }

        val shared = selected.optString("shared_path")
            .ifBlank { selected.optString("relative_path") }
            .ifBlank { null }

        return PartitionSpec(fileName = fileName, sha256 = sha, urlPath = url, sharedPath = shared)
    }

    private fun extractFromPartitionsMap(partitionsMap: JSONObject, partitionIdx: Int): PartitionSpec? {
        val keys = listOf(
            partitionIdx.toString(),
            "partition_$partitionIdx",
            "partition_${partitionIdx}.tflite"
        )
        val selected = keys.asSequence().mapNotNull { partitionsMap.optJSONObject(it) }.firstOrNull() ?: return null

        val fileName = selected.optString("filename")
            .ifBlank { selected.optString("file_name") }
            .ifBlank { selected.optString("name") }
            .ifBlank { "partition_${partitionIdx}.tflite" }

        val sha = selected.optString("sha256")
            .ifBlank { selected.optString("checksum") }
            .ifBlank { selected.optString("hash") }
            .ifBlank { null }

        val url = selected.optString("url")
            .ifBlank { selected.optString("download_url") }
            .ifBlank { null }

        val shared = selected.optString("shared_path")
            .ifBlank { selected.optString("relative_path") }
            .ifBlank { null }

        return PartitionSpec(fileName = fileName, sha256 = sha, urlPath = url, sharedPath = shared)
    }

    private fun findGlobalSha(manifest: JSONObject, fileName: String, partitionIdx: Int): String? {
        val byName = manifest.optJSONObject("sha256")?.optString(fileName).orEmpty()
        if (byName.isNotBlank()) return byName
        val byIdx = manifest.optJSONObject("sha256")?.optString(partitionIdx.toString()).orEmpty()
        if (byIdx.isNotBlank()) return byIdx
        return null
    }

    private fun findSharedCandidate(modelStoreDir: String?, modelId: String, partition: PartitionSpec): File? {
        val base = modelStoreDir?.trim().orEmpty()
        if (base.isEmpty()) return null

        val explicit = partition.sharedPath?.trim().orEmpty()
        if (explicit.isNotEmpty()) {
            val explicitFile = File(explicit)
            if (explicitFile.isAbsolute) return explicitFile
            return File(base, explicit)
        }

        return File(File(base, modelId), partition.fileName)
    }

    private fun downloadToCache(
        modelBaseUrl: String?,
        modelId: String,
        partition: PartitionSpec,
        destination: File
    ): DownloadOutcome {
        val candidates = buildDownloadCandidates(modelBaseUrl, modelId, partition)
        if (candidates.isEmpty()) return DownloadOutcome(file = null, attempts = listOf("no download candidates"))

        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".tmp")
        if (temp.exists()) temp.delete()
        val attempts = mutableListOf<String>()

        for (url in candidates) {
            val request = Request.Builder().url(url).get().build()
            try {
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        attempts.add("$url -> HTTP ${resp.code}")
                        return@use
                    }
                    val body = resp.body ?: return@use
                    temp.outputStream().use { out ->
                        body.byteStream().copyTo(out)
                    }
                }

                if (!temp.exists() || temp.length() == 0L) {
                    attempts.add("$url -> empty body")
                    temp.delete()
                    continue
                }

                if (destination.exists()) destination.delete()
                val moved = temp.renameTo(destination)
                if (!moved) {
                    temp.copyTo(destination, overwrite = true)
                    temp.delete()
                }
                attempts.add("$url -> success (${destination.length()} bytes)")
                return DownloadOutcome(file = destination, attempts = attempts)
            } catch (e: Exception) {
                Log.w(TAG, "Download attempt failed: $url (${e.message})")
                attempts.add("$url -> error: ${e.message}")
                temp.delete()
            }
        }
        return DownloadOutcome(file = null, attempts = attempts)
    }

    private fun buildDownloadCandidates(modelBaseUrl: String?, modelId: String, partition: PartitionSpec): List<String> {
        val base = modelBaseUrl?.trim().orEmpty()
        val set = LinkedHashSet<String>()
        val modelDirs = candidateModelDirs(modelId)

        if (partition.urlPath != null) {
            if (partition.urlPath.startsWith("http://") || partition.urlPath.startsWith("https://")) {
                set.add(partition.urlPath)
            } else if (base.isNotEmpty()) {
                set.add(joinUrl(base, partition.urlPath))
            }
        }

        if (base.isNotEmpty()) {
            modelDirs.forEach { dir ->
                set.add(joinUrl(base, "$dir/${partition.fileName}"))
            }
        }
        return set.toList()
    }

    private fun candidateModelDirs(modelId: String): List<String> {
        val trimmed = modelId.trim()
        val variants = linkedSetOf<String>()
        if (trimmed.endsWith("_partitioned")) {
            variants.add(trimmed)
            variants.add(trimmed.removeSuffix("_partitioned"))
        } else {
            variants.add("${trimmed}_partitioned")
            variants.add(trimmed)
        }
        return variants.filter { it.isNotBlank() }
    }

    private fun joinUrl(base: String, suffix: String): String {
        val normalizedBase = if (base.endsWith("/")) base.dropLast(1) else base
        val normalizedSuffix = suffix.trimStart('/')
        return "$normalizedBase/$normalizedSuffix"
    }

    private fun copyIntoCacheAtomic(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".tmp")
        if (temp.exists()) temp.delete()
        source.inputStream().use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (destination.exists()) destination.delete()
        val moved = temp.renameTo(destination)
        if (!moved) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }

    private fun verifySha256(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        val normalizedExpected = expected.lowercase(Locale.US)
        val actual = sha256Hex(file)
        return actual.equals(normalizedExpected, ignoreCase = true)
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitizePathSegment(input: String): String {
        return input.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun sanitizeRelativeDir(path: String): String {
        val cleaned = path
            .replace('\\', '/')
            .trim('/' )
        return cleaned
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString(File.separator) { sanitizePathSegment(it) }
            .ifBlank { DEFAULT_CACHE_DIR }
    }
}