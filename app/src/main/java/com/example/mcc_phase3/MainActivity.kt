package com.example.mcc_phase3

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.mcc_phase3.databinding.ActivityMainBinding
import com.example.mcc_phase3.data.ConfigManager
import com.example.mcc_phase3.data.api.ApiClient

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate: Starting MainActivity")
        super.onCreate(savedInstanceState)
        
        try {
            Log.d(TAG, "onCreate: Inflating binding")
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Log.d(TAG, "onCreate: Binding inflated successfully")
            
            setupNavigation()
            
            // Initialize API client
            ApiClient.initialize(this)
            
            // Check if foreman IP is configured, if not show dialog
            checkForemanConfiguration()
            
            Log.d(TAG, "onCreate: MainActivity setup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Error setting up MainActivity", e)
            throw e
        }
    }
    
    private fun setupNavigation() {
        try {
            Log.d(TAG, "setupNavigation: Setting up navigation")
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            Log.d(TAG, "setupNavigation: NavHostFragment found: $navHostFragment")
            
            val navController = navHostFragment.navController
            Log.d(TAG, "setupNavigation: NavController obtained: $navController")
            
            binding.bottomNavigation.setupWithNavController(navController)
            Log.d(TAG, "setupNavigation: BottomNavigation setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "setupNavigation: Error setting up navigation", e)
            throw e
        }
    }
    
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: MainActivity started")
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: MainActivity resumed")
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: MainActivity paused")
    }
    
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: MainActivity stopped")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: MainActivity destroyed")
    }
    
    private fun checkForemanConfiguration() {
        val configManager = ConfigManager.getInstance(this)
        
        if (!configManager.isForemanConfigured()) {
            Log.d(TAG, "Foreman not configured, showing IP input dialog")
            showForemanIPDialog()
        } else {
            Log.d(TAG, "Foreman already configured: ${configManager.getForemanIP()}")
        }
    }
    
    private fun showForemanIPDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_foreman_config, null)
        val ipInput = dialogView.findViewById<EditText>(R.id.ip_input)
        val portInput = dialogView.findViewById<EditText>(R.id.port_input)
        
        // Set default values
        ipInput.setText(ConfigManager.DEFAULT_FOREMAN_IP)
        portInput.setText(ConfigManager.DEFAULT_FOREMAN_PORT.toString())
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Connect") { _, _ ->
                val ip = ipInput.text.toString().trim()
                val portStr = portInput.text.toString().trim()
                if (validateAndSaveConfiguration(ip, portStr)) {
                    Log.d(TAG, "Foreman configuration saved: $ip:$portStr")
                } else {
                    // Show error and ask again
                    showForemanIPDialog()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                Log.d(TAG, "User cancelled foreman configuration")
                // You might want to show a message that the app won't work without configuration
            }
            .setCancelable(false)
            .create()
        
        dialog.show()
    }
    
    private fun validateAndSaveConfiguration(ip: String, portStr: String): Boolean {
        val configManager = ConfigManager.getInstance(this)
        
        if (ip.isBlank()) {
            showError("IP address cannot be empty")
            return false
        }
        
        if (!configManager.isValidIPAddress(ip)) {
            showError("Invalid IP address format. Please enter a valid IPv4 address (e.g., 192.168.1.100)")
            return false
        }
        
        val port = portStr.toIntOrNull()
        if (port == null || !configManager.isValidPort(port)) {
            showError("Invalid port number. Please enter a number between 1 and 65535")
            return false
        }
        
        // Save the configuration
        configManager.setForemanIP(ip)
        configManager.setForemanPort(port)
        Log.d(TAG, "Foreman configuration saved: $ip:$port")
        
        // Show success message
        showSuccess("Foreman configured successfully!\nIP: $ip\nPort: $port")
        
        return true
    }
    
    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Configuration Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showSuccess(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Configuration Success")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}