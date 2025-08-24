package com.example.mcc_phase3.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.mcc_phase3.databinding.FragmentWorkerControlBinding
import com.example.mcc_phase3.worker.WorkerService
import com.example.mcc_phase3.worker.WorkerConfig
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
            // For now, we'll show basic status
            // In a real implementation, you'd get this from the service via binding
            binding.workerIdText.text = "Worker ID: ${WorkerConfig.WORKER_ID}"
            binding.foremanUrlText.text = "Foreman: ${WorkerConfig.FOREMAN_URL}"
            
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
}
