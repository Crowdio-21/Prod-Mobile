package com.example.mcc_phase3.ui.fragments

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mcc_phase3.R
import com.example.mcc_phase3.data.ConfigManager
import com.example.mcc_phase3.services.MobileWorkerService
import com.example.mcc_phase3.ui.adapters.TaskAdapter
import com.example.mcc_phase3.ui.adapters.TaskItem
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.*

class TasksFragment : Fragment() {
    
    companion object {
        private const val TAG = "TasksFragment"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var activateButton: MaterialButton
    private lateinit var pauseButton: MaterialButton
    private lateinit var workerStatusText: MaterialTextView
    private lateinit var statusIndicator: View
    private lateinit var emptyStateCard: View
    private lateinit var debugInfoText: MaterialTextView
    private lateinit var taskAdapter: TaskAdapter
    
    private var isWorkerActive = false
    private val progressUpdateScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var mobileWorkerService:  MobileWorkerService? = null
    private var isBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MobileWorkerService.LocalBinder
            mobileWorkerService = binder.getService()
            isBound = true
            Log.d(TAG, "Service connected")
            
            // Check if fragment is still attached before accessing context
            if (isAdded && context != null) {
                Toast.makeText(context, "Connected to worker service", Toast.LENGTH_SHORT).show()
                startRealProgressUpdates()
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            mobileWorkerService = null
            isBound = false
            Log.d(TAG, "Service disconnected")
        }
    }
    
    // Permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startWorkerService()
        } else {
            Toast.makeText(
                requireContext(),
                "Notification permission is required for worker service",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tasks, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.tasks_recycler_view)
        activateButton = view.findViewById(R.id.activate_worker_button)
        pauseButton = view.findViewById(R.id.pause_worker_button)
        workerStatusText = view.findViewById(R.id.worker_status_text)
        statusIndicator = view.findViewById(R.id.status_indicator)
        emptyStateCard = view.findViewById(R.id.empty_state_card)
        debugInfoText = view.findViewById(R.id.debug_info_text)
        
        setupRecyclerView()
        setupWorkerControls()
        bindToService()
    }
    
    private fun bindToService() {
        val intent = Intent(requireContext(), MobileWorkerService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        taskAdapter = TaskAdapter(mutableListOf())
        recyclerView.adapter = taskAdapter
    }
    
    private fun startRealProgressUpdates() {
        Log.d(TAG, "Starting real progress updates")
        progressUpdateScope.launch {
            while (isActive && isBound && isAdded) {
                try {
                    val service = mobileWorkerService
                    if (service != null && service.isWorkerRunning()) {
                        // Get current task status from service
                        val status = service.getWorkerStatus()
                        
                        if (status != null && isAdded) {
                            val taskStatus = status["task_processor"] as? Map<String, Any>
                            val currentTaskId = taskStatus?.get("current_task_id") as? String
                            val progressPercent = (taskStatus?.get("progress_percent") as? Number)?.toFloat() ?: 0f
                            val isBusy = taskStatus?.get("is_busy") as? Boolean ?: false
                            
                            // Update debug info (only if fragment is still attached)
                            if (isAdded && view != null) {
                                val connectionStatus = status["connection"] as? Map<String, Any>
                                val isConnected = connectionStatus?.get("is_connected") as? Boolean ?: false
                                
                                debugInfoText.text = """
                                    Service Bound: $isBound
                                    Worker Running: ${service.isWorkerRunning()}
                                    WebSocket: ${if (isConnected) "Connected" else "Disconnected"}
                                    Task ID: ${currentTaskId ?: "none"}
                                    Progress: $progressPercent%
                                    Is Busy: $isBusy
                                """.trimIndent()
                            }
                            
                            Log.d(TAG, "Task status - ID: $currentTaskId, Progress: $progressPercent%, Busy: $isBusy")
                            
                            if (!currentTaskId.isNullOrEmpty() && isBusy) {
                                // Show current task with real progress from checkpoint handler
                                val progress = progressPercent.toInt().coerceIn(0, 100)
                                val executionTime = "${progress}%"
                                
                                val currentTasks = listOf(
                                    TaskItem(
                                        id = currentTaskId,
                                        name = "Task $currentTaskId",
                                        status = "Running",
                                        progress = progress,
                                        executionTime = executionTime
                                    )
                                )
                                
                                if (isAdded && view != null) {
                                    taskAdapter.updateTasks(currentTasks)
                                    emptyStateCard.visibility = View.GONE
                                    recyclerView.visibility = View.VISIBLE
                                    Log.d(TAG, "Displaying task with ${progress}% progress")
                                    
                                    // Update worker status
                                    if (!isWorkerActive) {
                                        isWorkerActive = true
                                        updateWorkerStatus()
                                    }
                                }
                            } else {
                                // No current task
                                if (isAdded && view != null) {
                                    taskAdapter.updateTasks(emptyList())
                                    emptyStateCard.visibility = if (service.isWorkerRunning()) View.VISIBLE else View.GONE
                                    recyclerView.visibility = View.GONE
                                    Log.d(TAG, "No active tasks")
                                }
                            }
                        }
                    } else {
                        // Worker not running, show empty
                        if (isAdded && view != null) {
                            taskAdapter.updateTasks(emptyList())
                            emptyStateCard.visibility = View.GONE
                            recyclerView.visibility = View.GONE
                            debugInfoText.text = """
                                Service Bound: $isBound
                                Worker Running: false
                                Status: Worker service not active
                            """.trimIndent()
                            if (isWorkerActive) {
                                isWorkerActive = false
                                updateWorkerStatus()
                            }
                        }
                        Log.d(TAG, "Worker not running")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating task progress", e)
                    if (isAdded && view != null) {
                        debugInfoText.text = "Error: ${e.message}"
                    }
                }
                
                delay(1000) // Update every second
            }
        }
    }
    
    private fun setupWorkerControls() {
        activateButton.setOnClickListener {
            activateWorker()
        }
        
        pauseButton.setOnClickListener {
            pauseWorker()
        }
    }
    
    private fun activateWorker() {
        // Check for notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startWorkerService()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Toast.makeText(
                        requireContext(),
                        "Notification permission is needed to run worker service",
                        Toast.LENGTH_LONG
                    ).show()
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startWorkerService()
        }
    }
    
    private fun startWorkerService() {
        // Check if foreman is configured
        val configManager = ConfigManager.getInstance(requireContext())
        val foremanUrl = configManager.getForemanURL()
        
        if (foremanUrl == null) {
            Toast.makeText(
                requireContext(),
                "Please configure Foreman IP in Settings first",
                Toast.LENGTH_LONG
            ).show()
            Log.w(TAG, "Cannot start worker - Foreman not configured")
            return
        }
        
        Log.d(TAG, "Starting worker service with Foreman URL: $foremanUrl")
        
        isWorkerActive = true
        updateWorkerStatus()
        
        // Start the worker service with START_WORKER action
        val intent = Intent(requireContext(), MobileWorkerService::class.java).apply {
            action = "START_WORKER"
            putExtra("foreman_url", foremanUrl)
        }
        requireContext().startService(intent)
        
        Toast.makeText(requireContext(), "Worker activated. Connecting to $foremanUrl", Toast.LENGTH_SHORT).show()
    }
    
    private fun pauseWorker() {
        isWorkerActive = false
        updateWorkerStatus()
        
        // Stop the worker service with STOP_WORKER action
        val intent = Intent(requireContext(), MobileWorkerService::class.java).apply {
            action = "STOP_WORKER"
        }
        requireContext().startService(intent)
        
        Toast.makeText(requireContext(), "Worker paused", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateWorkerStatus() {
        if (isWorkerActive) {
            workerStatusText.text = "Active - Processing tasks"
            workerStatusText.setTextColor(resources.getColor(R.color.success, null))
            statusIndicator.backgroundTintList = 
                resources.getColorStateList(R.color.success, null)
            activateButton.isEnabled = false
            pauseButton.isEnabled = true
        } else {
            workerStatusText.text = "Inactive"
            workerStatusText.setTextColor(resources.getColor(R.color.text_secondary, null))
            statusIndicator.backgroundTintList = 
                resources.getColorStateList(R.color.text_secondary, null)
            activateButton.isEnabled = true
            pauseButton.isEnabled = false
        }
    }
    
    override fun onDestroyView() {
        // Cancel coroutines first to stop any ongoing UI updates
        progressUpdateScope.cancel()
        
        // Unbind service
        if (isBound) {
            try {
                context?.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
            isBound = false
        }
        
        super.onDestroyView()
    }
}
