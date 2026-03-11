package com.example.mcc_phase3.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.mcc_phase3.R
import com.example.mcc_phase3.data.ConfigManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

class PreferencesActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager
    private lateinit var workingDirText: TextView

    private val dirPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read/write access across reboots
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)

            configManager.setWorkingDir(uri.toString())
            updateWorkingDirDisplay(uri.toString())
            Snackbar.make(
                findViewById(android.R.id.content),
                "Working directory updated",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preferences)

        configManager = ConfigManager.getInstance(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Preferences"

        toolbar.setNavigationOnClickListener {
            @Suppress("DEPRECATION")
            onBackPressed()
        }

        workingDirText = findViewById(R.id.working_dir_text)
        updateWorkingDirDisplay(configManager.getWorkingDir())

        findViewById<MaterialButton>(R.id.btn_choose_working_dir).setOnClickListener {
            dirPickerLauncher.launch(null)
        }

        findViewById<MaterialButton>(R.id.btn_clear_working_dir).setOnClickListener {
            configManager.setWorkingDir("")
            updateWorkingDirDisplay("")
            Snackbar.make(
                findViewById(android.R.id.content),
                "Working directory cleared",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateWorkingDirDisplay(uriString: String) {
        if (uriString.isBlank()) {
            workingDirText.text = "Not set – tap to choose"
        } else {
            // Convert content URI to a readable path label
            val decoded = Uri.decode(uriString)
                .removePrefix("content://com.android.externalstorage.documents/tree/")
                .removePrefix("primary:")
                .replace("%3A", "/")
            workingDirText.text = if (decoded.isNotBlank()) "/$decoded" else uriString
        }
    }
}
