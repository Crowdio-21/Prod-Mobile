package com.crowdio.mcc_phase3.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class DownloadedModelArtifact(
    val tempFile: File,
    val checksumSha256Hex: String,
    val bytesDownloaded: Long
)

/**
 * Downloads model artifacts from foreman-provided URLs.
 */
class ModelDownloadClient(
    private val okHttpClient: OkHttpClient = OkHttpClient()
) {

    suspend fun downloadToTempFile(
        modelUri: String,
        tempDir: File
    ): DownloadedModelArtifact = withContext(Dispatchers.IO) {
        tempDir.mkdirs()
        var tempFile: File? = null

        val request = Request.Builder().url(modelUri).get().build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Failed to download model artifact: HTTP ${response.code}")
                }

                val body = response.body
                    ?: throw IllegalStateException("Model artifact response body was empty")

                tempFile = File.createTempFile("model_dl_", ".tmp", tempDir)
                val digest = MessageDigest.getInstance("SHA-256")
                var totalBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile!!).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            totalBytes += read.toLong()
                        }
                        output.flush()
                    }
                }

                val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                return@withContext DownloadedModelArtifact(
                    tempFile = tempFile!!,
                    checksumSha256Hex = checksum,
                    bytesDownloaded = totalBytes
                )
            }
        } catch (t: Throwable) {
            tempFile?.let {
                try {
                    if (it.exists()) {
                        it.delete()
                    }
                } catch (_: Exception) {
                    // Best-effort cleanup.
                }
            }
            throw t
        }
    }
}
