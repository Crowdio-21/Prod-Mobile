package com.example.mcc_phase3.ui.fragments

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.example.mcc_phase3.databinding.FragmentWorkerControlBinding
import com.example.mcc_phase3.worker.WorkerService
import com.example.mcc_phase3.worker.WorkerConfig
import com.example.mcc_phase3.data.ConfigManager
import com.example.mcc_phase3.R
import kotlinx.coroutines.*

class WorkerControlFragment : Fragment() {
    private var _binding: FragmentWorkerControlBinding? = null
    private val binding get() = _binding!!
    private val updateScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    companion object {
        private const val TAG = "WorkerControlFragment"
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d(TAG, "onCreateView: Creating worker control view")
        _binding = FragmentWorkerControlBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: Setting up worker control UI")
        
        setupUI()
        startStatusUpdates()
    }
    
    private fun setupUI() {
        Log.d(TAG, "setupUI: Configuring UI elements")
        
        binding.startWorkerButton.setOnClickListener {
            Log.d(TAG, "🚀 Start worker button clicked")
            startWorker()
        }
        
        binding.stopWorkerButton.setOnClickListener {
            Log.d(TAG, "🛑 Stop worker button clicked")
            stopWorker()
        }
        
        binding.refreshStatusButton.setOnClickListener {
            Log.d(TAG, "🔄 Refresh status button clicked")
            updateWorkerStatus()
        }
        
        binding.configureForemanButton.setOnClickListener {
            Log.d(TAG, "⚙️ Configure foreman button clicked")
            showForemanConfigurationDialog()
        }
    }
    
    private fun startWorker() {
        Log.d(TAG, "startWorker: Starting worker service")
        
        try {
            val intent = Intent(requireContext(), WorkerService::class.java).apply {
                action = "START_WORKER"
            }
            requireContext().startService(intent)
            
            Log.d(TAG, "✅ Worker service start intent sent")
            binding.workerStatusText.text = "Starting worker..."
            binding.startWorkerButton.isEnabled = false
            binding.stopWorkerButton.isEnabled = true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start worker service", e)
            binding.workerStatusText.text = "Failed to start worker: ${e.message}"
        }
    }
    
    private fun stopWorker() {
        Log.d(TAG, "stopWorker: Stopping worker service")
        
        try {
            val intent = Intent(requireContext(), WorkerService::class.java).apply {
                action = "STOP_WORKER"
            }
            requireContext().startService(intent)
            
            Log.d(TAG, "✅ Worker service stop intent sent")
            binding.workerStatusText.text = "Stopping worker..."
            binding.startWorkerButton.isEnabled = true
            binding.stopWorkerButton.isEnabled = false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to stop worker service", e)
            binding.workerStatusText.text = "Failed to stop worker: ${e.message}"
        }
    }
    
    private fun startStatusUpdates() {
        Log.d(TAG, "startStatusUpdates: Starting periodic status updates")
        
        updateScope.launch {
            while (isActive) {
                updateWorkerStatus()
                delay(2000) // Update every 2 seconds
            }
        }
    }
    
    private fun updateWorkerStatus() {
        Log.d(TAG, "updateWorkerStatus: Updating worker status display")
        
        try {
            val configManager = ConfigManager.getInstance(requireContext())
            
            // For now, we'll show basic status
            // In a real implementation, you'd get this from the service via binding
            binding.workerIdText.text = "Worker ID: ${WorkerConfig.WORKER_ID}"
            binding.foremanUrlText.text = "Foreman: ${configManager.getForemanURL()}"
            
            // Update connection status
            val isConnected = true // This would come from the service
            binding.connectionStatusText.text = if (isConnected) "🟢 Connected" else "🔴 Disconnected"
            binding.connectionStatusText.setTextColor(
                if (isConnected) 
                    requireContext().getColor(android.R.color.holo_green_dark)
                else 
                    requireContext().getColor(android.R.color.holo_red_dark)
            )
            
            // Update stats (these would come from the service)
            binding.tasksCompletedText.text = "Tasks Completed: 0"
            binding.tasksFailedText.text = "Tasks Failed: 0"
            binding.uptimeText.text = "Uptime: 0s"
            
            // Show execution mode
            binding.workerStatusText.text = "Execution Mode: ${WorkerConfig.getExecutionMode()}"
            
            Log.d(TAG, "✅ Worker status updated successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to update worker status", e)
            binding.workerStatusText.text = "Status update failed: ${e.message}"
        }
    }
    
    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView: Cleaning up worker control fragment")
        updateScope.cancel()
        _binding = null
        super.onDestroyView()
    }
    
    private fun showForemanConfigurationDialog() {
        val configManager = ConfigManager.getInstance(requireContext())
        val currentIP = configManager.getForemanIP()
        val currentPort = configManager.getForemanPort()
        
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_foreman_config, null)
        val ipInput = dialogView.findViewById(R.id.ip_input) as EditText
        val portInput = dialogView.findViewById(R.id.port_input) as EditText
        
        // Set current values
        ipInput.setText(currentIP)
        portInput.setText(currentPort.toString())
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newIP = ipInput.text.toString().trim()
                val newPortStr = portInput.text.toString().trim()
                
                if (validateAndSaveConfiguration(newIP, newPortStr)) {
                    Log.d(TAG, "Foreman configuration updated: $newIP:$newPortStr")
                    updateWorkerStatus() // Refresh the display
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        
        dialog.show()
    }
    
    private fun validateAndSaveConfiguration(ip: String, portStr: String): Boolean {
        val configManager = ConfigManager.getInstance(requireContext())
        
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
        showSuccess("Foreman configuration updated successfully!\nIP: $ip\nPort: $port")
        
        return true
    }
    
    private fun showError(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Configuration Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showSuccess(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Configuration Success")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
