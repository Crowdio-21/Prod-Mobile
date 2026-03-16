package com.example.mcc_phase3

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.mcc_phase3.data.ConfigManager
import com.example.mcc_phase3.data.repository.CrowdComputeRepository
import com.example.mcc_phase3.utils.ThemeManager
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Settings Activity for configuring foreman IP and other settings
 */
class SettingsActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "SettingsActivity"
    }
    
    private lateinit var foremanIpInput: EditText
    private lateinit var foremanPortInput: EditText
    private lateinit var websocketPortInput: EditText
    private lateinit var statsPortInput: EditText
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var statusTextView: TextView
    
    private lateinit var configManager: ConfigManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize theme manager and apply current theme
        ThemeManager.initialize(this)
        ThemeManager.applyCurrentTheme()
        
        setContentView(R.layout.activity_settings)
        
        configManager = ConfigManager.getInstance(this)
        initializeViews()
        loadCurrentSettings()
        setupClickListeners()
    }
    
    private fun initializeViews() {
        foremanIpInput = findViewById(R.id.foremanIpInput)
        foremanPortInput = findViewById(R.id.foremanPortInput)
        websocketPortInput = findViewById(R.id.websocketPortInput)
        statsPortInput = findViewById(R.id.statsPortInput)
        saveButton = findViewById(R.id.saveButton)
        resetButton = findViewById(R.id.resetButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        statusTextView = findViewById(R.id.statusTextView)
    }
    
    private fun loadCurrentSettings() {
        // Load current configuration
        foremanIpInput.setText(configManager.getForemanIP())
        foremanPortInput.setText(configManager.getForemanPort().toString())
        websocketPortInput.setText(configManager.getWebSocketPort().toString())
        statsPortInput.setText(configManager.getStatServicePort().toString())
        
        updateStatusDisplay()
    }
    
    private fun setupClickListeners() {
        saveButton.setOnClickListener {
            saveSettings()
        }
        
        resetButton.setOnClickListener {
            resetToDefaults()
        }
        
        testConnectionButton.setOnClickListener {
            testConnection()
        }
    }
    
    private fun saveSettings() {
        try {
            val foremanIp = foremanIpInput.text.toString().trim()
            val foremanPort = foremanPortInput.text.toString().trim().toIntOrNull()
            val websocketPort = websocketPortInput.text.toString().trim().toIntOrNull()
            val statsPort = statsPortInput.text.toString().trim().toIntOrNull()
            
            // Validate inputs
            if (foremanIp.isEmpty()) {
                showError("Foreman IP cannot be empty")
                return
            }
            
            if (!configManager.isValidIPAddress(foremanIp)) {
                showError("Invalid Foreman IP address format")
                return
            }
            
            if (foremanPort == null || !configManager.isValidPort(foremanPort)) {
                showError("Invalid Foreman Port (must be 1-65535)")
                return
            }
            
            if (websocketPort == null || !configManager.isValidPort(websocketPort)) {
                showError("Invalid WebSocket Port (must be 1-65535)")
                return
            }
            
            if (statsPort == null || !configManager.isValidPort(statsPort)) {
                showError("Invalid Statistics Port (must be 1-65535)")
                return
            }
            
            // Save settings
            configManager.setForemanIP(foremanIp)
            configManager.setForemanPort(foremanPort)
            configManager.setWebSocketPort(websocketPort)
            configManager.setStatServicePort(statsPort)
            
            updateStatusDisplay()
            showSuccess("Settings saved successfully!")
            
            Log.d(TAG, "Settings saved: IP=$foremanIp, ForemanPort=$foremanPort, WebSocketPort=$websocketPort, StatsPort=$statsPort")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings", e)
            showError("Error saving settings: ${e.message}")
        }
    }
    
    private fun resetToDefaults() {
        try {
            // Reset to default values
            configManager.setForemanIP(ConfigManager.DEFAULT_FOREMAN_IP)
            configManager.setForemanPort(ConfigManager.DEFAULT_FOREMAN_PORT)
            configManager.setWebSocketPort(ConfigManager.DEFAULT_WEBSOCKET_PORT)
            configManager.setStatServicePort(ConfigManager.DEFAULT_STATISTICS_PORT)
            
            // Reload UI
            loadCurrentSettings()
            showSuccess("Settings reset to defaults")
            
            Log.d(TAG, "Settings reset to defaults")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting settings", e)
            showError("Error resetting settings: ${e.message}")
        }
    }
    
    private fun testConnection() {
        try {
            val foremanUrl = configManager.getForemanURL()
            val statsUrl = configManager.getStatServiceURL()
            
            if (foremanUrl == null || statsUrl == null) {
                statusTextView.text = "Configuration incomplete\nPlease configure Foreman IP address first."
                return
            }
            
            statusTextView.text = "Testing connections...\n" +
                    "Foreman WebSocket: $foremanUrl\n" +
                    "Stats HTTP: $statsUrl"
            
            Log.d(TAG, "Testing connections: Foreman WebSocket=$foremanUrl, Stats HTTP=$statsUrl")
            
            // Test HTTP connection
            lifecycleScope.launch {
                try {
                    val repository = CrowdComputeRepository(this@SettingsActivity)
                    val jobsResult = repository.getJobs(0, 1)
                    
                    if (jobsResult.isSuccess) {
                        statusTextView.text = "Connection successful!\n" +
                                "Foreman WebSocket: $foremanUrl\n" +
                                "Stats HTTP: $statsUrl\n" +
                                "Retrieved ${jobsResult.getOrNull()?.size ?: 0} jobs"
                        showSuccess("Connection test successful!")
                    } else {
                        statusTextView.text = "Connection failed!\n" +
                                "Foreman WebSocket: $foremanUrl\n" +
                                "Stats HTTP: $statsUrl\n" +
                                "Error: ${jobsResult.exceptionOrNull()?.message}"
                        showError("Connection test failed: ${jobsResult.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    statusTextView.text = "Connection failed!\n" +
                            "Foreman WebSocket: $foremanUrl\n" +
                            "Stats HTTP: $statsUrl\n" +
                            "Error: ${e.message}"
                    showError("Connection test failed: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error testing connection", e)
            showError("Error testing connection: ${e.message}")
        }
    }
    
    private fun updateStatusDisplay() {
        val summary = configManager.getConfigSummary()
        statusTextView.text = "Current Configuration:\n$summary"
    }
    
    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun showError(message: String) {
        Toast.makeText(this, "Error: $message", Toast.LENGTH_LONG).show()
    }
}
