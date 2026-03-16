package com.example.mcc_phase3.execution

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mcc_phase3.ui.activities.ImagePickerActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton bridge that allows the background PythonExecutor coroutine to
 * suspend until the user finishes picking images in the foreground UI.
 *
 * Flow:
 *  1. PythonExecutor calls [awaitImageSelection] – suspends until images are ready.
 *  2. [awaitImageSelection] stores a CompletableDeferred and starts ImagePickerActivity.
 *  3. ImagePickerActivity lets the user pick images, copies them to the app-internal
 *     cache directory to obtain stable file-system paths, then calls [deliverImages].
 *  4. [deliverImages] completes the deferred, unblocking PythonExecutor.
 *  5. PythonExecutor injects the paths into Python builtins._selected_images.
 */
class ImagePickerManager private constructor() {

    companion object {
        private const val TAG = "ImagePickerManager"

        /** How long (ms) to wait for the user to pick images before timing out. */
        private const val IMAGE_PICKER_TIMEOUT_MS = 120_000L  // 2 minutes

        @Volatile
        private var instance: ImagePickerManager? = null

        fun getInstance(): ImagePickerManager =
            instance ?: synchronized(this) {
                instance ?: ImagePickerManager().also { instance = it }
            }
    }

    // Holds the active deferred while a pick is in progress; null otherwise.
    private val pendingDeferred = AtomicReference<CompletableDeferred<List<String>>?>(null)

    /**
     * Suspends the calling coroutine until the user selects images (or cancels).
     *
     * @param context Any context – used to start ImagePickerActivity.
     * @param maxImages Maximum number of images the user may select (0 = unlimited).
     * @return List of absolute file-system paths inside the app's cache directory.
     *         Returns an empty list if the user cancels or an error occurs.
     */
    suspend fun awaitImageSelection(context: Context, maxImages: Int = 0): List<String> {
        // Cancel any stale deferred from a previous (aborted) pick
        pendingDeferred.getAndSet(null)?.cancel()

        val deferred = CompletableDeferred<List<String>>()
        pendingDeferred.set(deferred)

        Log.d(TAG, "Starting ImagePickerActivity (maxImages=$maxImages)…")

        val intent = Intent(context, ImagePickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ImagePickerActivity.EXTRA_MAX_IMAGES, maxImages)
        }
        context.startActivity(intent)

        return try {
            val result = withTimeoutOrNull(IMAGE_PICKER_TIMEOUT_MS) { deferred.await() }
            if (result == null) {
                Log.w(TAG, "Image selection timed out after ${IMAGE_PICKER_TIMEOUT_MS / 1000}s – returning empty list")
                pendingDeferred.getAndSet(null)?.cancel()
                emptyList()
            } else {
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image selection cancelled or failed: ${e.message}")
            emptyList()
        } finally {
            pendingDeferred.compareAndSet(deferred, null)
        }
    }

    /**
     * Called by [ImagePickerActivity] once images have been selected and copied
     * to the cache directory.  Completes (or cancels) the pending deferred.
     *
     * @param paths Absolute paths to the copied images, or empty list on cancellation.
     */
    fun deliverImages(paths: List<String>) {
        val deferred = pendingDeferred.getAndSet(null)
        if (deferred == null) {
            Log.w(TAG, "deliverImages called but no pending pick – ignoring")
            return
        }
        Log.d(TAG, "Delivering ${paths.size} image path(s) to waiting coroutine")
        deferred.complete(paths)
    }

    /** True if a pick is currently in progress. */
    fun isPicking(): Boolean = pendingDeferred.get() != null
}
