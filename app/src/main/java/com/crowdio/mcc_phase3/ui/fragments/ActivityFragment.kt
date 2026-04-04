package com.crowdio.mcc_phase3.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.crowdio.mcc_phase3.databinding.FragmentActivityBinding
import com.crowdio.mcc_phase3.ui.adapters.ActivityAdapter
import com.crowdio.mcc_phase3.ui.mvi.MainEvent
import com.crowdio.mcc_phase3.ui.mvi.MainState
import com.crowdio.mcc_phase3.ui.mvi.MainViewModel
import com.crowdio.mcc_phase3.utils.TaskProgressSimulator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ActivityFragment : Fragment() {
    private var _binding: FragmentActivityBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var activityAdapter: ActivityAdapter
    private lateinit var taskProgressSimulator: TaskProgressSimulator
    
    companion object {
        private const val TAG = "ActivityFragment"
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentActivityBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        setupTaskProgressSimulator()
        observeViewModel()
        observeTaskProgress()
        viewModel.handleEvent(MainEvent.LoadData)
    }
    
    private fun setupRecyclerView() {
        activityAdapter = ActivityAdapter()
        binding.activityRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = activityAdapter
        }
    }
    
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.handleEvent(MainEvent.RefreshData)
        }
    }
    
    private fun setupTaskProgressSimulator() {
        taskProgressSimulator = viewModel.getTaskProgressSimulator()
        
        // Start a demo task simulation for demonstration
        lifecycleScope.launch {
            delay(1000) // Wait 1 second after fragment is created
            Log.d(TAG, "Starting demo task simulation")
            taskProgressSimulator.startTaskSimulation("demo_task_001", shouldSucceed = true)
            
            // Start another demo task after a delay
            delay(2000)
            taskProgressSimulator.startTaskSimulation("demo_task_002", shouldSucceed = false)
        }
    }
    
    private fun observeTaskProgress() {
        lifecycleScope.launch {
            taskProgressSimulator.taskProgressFlow.collect { taskProgressMap ->
                Log.d(TAG, "Task progress updated: ${taskProgressMap.size} active tasks")
                
                // Update the activity list with current progress
                val currentState = viewModel.state.value
                if (currentState is MainState.Success) {
                    val filteredActivity = viewModel.getActivityForCurrentWorker()
                    activityAdapter.submitList(filteredActivity)
                }
            }
        }
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.observe(viewLifecycleOwner) { state ->
                when (state) {
                    is MainState.Loading -> {
                        binding.swipeRefresh.isRefreshing = true
                    }
                    is MainState.Success -> {
                        binding.swipeRefresh.isRefreshing = false
                        // Show only activity for this device
                        val filteredActivity = viewModel.getActivityForCurrentWorker()
                        activityAdapter.submitList(filteredActivity)
                        
                        // Update UI to show filtering info
                        if (filteredActivity.isEmpty()) {
                            // Show message that no activity for this device
                            showError("No activity found for this device. Make sure the mobile worker is running and connected.")
                        }
                    }
                    is MainState.Error -> {
                        binding.swipeRefresh.isRefreshing = false
                        showError(state.message)
                    }
                }
            }
        }
    }
    
    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction("Retry") {
                viewModel.handleEvent(MainEvent.LoadData)
            }
            .show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        taskProgressSimulator.cleanup()
        _binding = null
    }
}
