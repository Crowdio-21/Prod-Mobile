package com.crowdio.mcc_phase3.ui.activities

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.crowdio.mcc_phase3.execution.ImagePickerManager
import java.io.File
import java.io.FileOutputStream

// Transparent, single-purpose Activity that presents the system image picker.
//
// Strategy by API level:
//  - API 33+  : Android Photo Picker (PickMultipleVisualMedia / PickVisualMedia).
//               NO runtime permission required – the system handles access.
//  - API < 33 : GetMultipleContents with MIME type "image/*" after requesting READ_EXTERNAL_STORAGE.
//
// The cache dir is wiped at the start of every pick so stale images from previous
// tasks are never reused.
//
// Result is delivered back to the waiting coroutine via ImagePickerManager.deliverImages.
class ImagePickerActivity : AppCompatActivity() {

companion object {
private const val TAG = "ImagePickerActivity"
const val EXTRA_MAX_IMAGES = "extra_max_images"
}

private var maxImages: Int = 0

// ── API 33+ Photo Picker launchers (no permission prompt) ───────────────

private val photoPickerMultiple =
registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
handlePickedUris(uris)
}

private val photoPickerSingle =
registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
handlePickedUris(if (uri != null) listOf(uri) else emptyList())
}

// ── Legacy launchers for API < 33 ───────────────────────────────────────

private val legacyPickerMultiple =
registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
handlePickedUris(uris)
}

private val legacyPickerSingle =
registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
handlePickedUris(if (uri != null) listOf(uri) else emptyList())
}

private val requestPermissionLauncher =
registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
if (granted) {
launchLegacyPicker()
} else {
Log.w(TAG, "Storage permission denied")
Toast.makeText(this, "Permission denied – cannot access photos", Toast.LENGTH_SHORT).show()
deliverAndFinish(emptyList())
}
}

// ── Lifecycle ────────────────────────────────────────────────────────────

override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
maxImages = intent.getIntExtra(EXTRA_MAX_IMAGES, 0)
launchPicker()
}

// ── Picker launch ────────────────────────────────────────────────────────

private fun launchPicker() {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
// Photo Picker – no permission needed, never asks twice
Log.d(TAG, "Launching Photo Picker (API 33+, maxImages=$maxImages)")
val request = PickVisualMediaRequest(PickVisualMedia.ImageOnly)
if (maxImages == 1) {
photoPickerSingle.launch(request)
} else {
photoPickerMultiple.launch(request)
}
} else {
// Legacy – need READ_EXTERNAL_STORAGE
val perm = Manifest.permission.READ_EXTERNAL_STORAGE
if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
launchLegacyPicker()
} else {
requestPermissionLauncher.launch(perm)
}
}
}

private fun launchLegacyPicker() {
Log.d(TAG, "Launching legacy picker (API < 33, maxImages=$maxImages)")
if (maxImages == 1) {
legacyPickerSingle.launch("image/*")
} else {
legacyPickerMultiple.launch("image/*")
}
}

// ── Result handling ──────────────────────────────────────────────────────

private fun handlePickedUris(uris: List<Uri>) {
if (uris.isEmpty()) {
Log.d(TAG, "User cancelled image selection")
deliverAndFinish(emptyList())
return
}

// Wipe the cache directory first so previous task's images are never reused
val cacheDir = File(cacheDir, "selected_images")
cacheDir.deleteRecursively()
cacheDir.mkdirs()

Log.d(TAG, "User selected ${uris.size} image(s) – copying to fresh cache…")

val paths = mutableListOf<String>()
uris.forEach { uri ->
try {
val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
val ext = mimeType.substringAfterLast('/').let { ".$it" }
val destFile = File(cacheDir, "img_${System.currentTimeMillis()}_${paths.size}$ext")

contentResolver.openInputStream(uri)?.use { input ->
FileOutputStream(destFile).use { output -> input.copyTo(output) }
}

paths.add(destFile.absolutePath)
Log.d(TAG, "Copied → ${destFile.name}")
} catch (e: Exception) {
Log.e(TAG, "Failed to copy $uri: ${e.message}")
}
}

Log.d(TAG, "Delivering ${paths.size} fresh path(s)")
deliverAndFinish(paths)
}

private fun deliverAndFinish(paths: List<String>) {
ImagePickerManager.getInstance().deliverImages(paths)
finish()
}
}