package com.example.mcc_phase3.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.mcc_phase3.R
import com.example.mcc_phase3.databinding.FragmentDashboardBinding
import com.example.mcc_phase3.ui.adapters.ActivityAdapter
import com.example.mcc_phase3.ui.mvi.MainEvent
import com.example.mcc_phase3.ui.mvi.MainState
import com.example.mcc_phase3.ui.mvi.MainViewModel

class DashboardFragment : Fragment() {
    
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var activityAdapter: ActivityAdapter
    
    companion object {
        private const val TAG = "DashboardFragment"
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d(TAG, "🏗️  onCreateView() called")
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        Log.d(TAG, "🏗️  FragmentDashboardBinding inflated successfully")
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Log.d(TAG, "👀 onViewCreated() called")
        super.onViewCreated(view, savedInstanceState)
        
        try {
            Log.d(TAG, "👀 Setting up UI components...")
            setupRecyclerView()
            setupSwipeRefresh()
            observeViewModel()
            
            // Load initial data
            Log.d(TAG, "👀 Triggering initial data load")
            viewModel.handleEvent(MainEvent.LoadData)
            
            Log.d(TAG, "👀 onViewCreated() completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error in onViewCreated()", e)
        }
    }
    
    private fun setupRecyclerView() {
        Log.d(TAG, "♻️  setupRecyclerView() called")
        try {
            activityAdapter = ActivityAdapter()
            Log.d(TAG, "♻️  ActivityAdapter created")
            
            binding.activityRecyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = activityAdapter
                Log.d(TAG, "♻️  RecyclerView configured with LinearLayoutManager and adapter")
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error setting up RecyclerView", e)
        }
    }
    
    private fun setupSwipeRefresh() {
        Log.d(TAG, "🔄 setupSwipeRefresh() called")
        try {
            binding.swipeRefresh.setOnRefreshListener {
                Log.d(TAG, "🔄 SwipeRefresh triggered - loading data")
                viewModel.handleEvent(MainEvent.LoadData)
            }
            Log.d(TAG, "🔄 SwipeRefresh listener set successfully")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error setting up SwipeRefresh", e)
        }
    }
    
    private fun observeViewModel() {
        Log.d(TAG, "👁️  observeViewModel() called")
        try {
            viewModel.state.observe(viewLifecycleOwner) { state ->
                Log.d(TAG, "👁️  State changed: ${state.javaClass.simpleName}")
                when (state) {
                    is MainState.Loading -> {
                        Log.d(TAG, "⏳ Loading state - showing refresh indicator")
                        binding.swipeRefresh.isRefreshing = true
                    }
                    is MainState.Success -> {
                        Log.d(TAG, "✅ Success state - updating dashboard")
                        binding.swipeRefresh.isRefreshing = false
                        updateDashboard(state)
                    }
                    is MainState.Error -> {
                        Log.e(TAG, "❌ Error state: ${state.message}")
                        binding.swipeRefresh.isRefreshing = false
                        // Handle error state
                    }
                }
            }
            
            // Observe WebSocket connection status
            viewModel.state.observe(viewLifecycleOwner) { state ->
                if (state is MainState.Success) {
                    Log.d(TAG, "🔌 Updating WebSocket connection status: ${state.isWebSocketConnected}")
                    updateConnectionStatus(state.isWebSocketConnected)
                }
            }
            
            Log.d(TAG, "👁️  ViewModel observers set up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error setting up ViewModel observers", e)
        }
    }
    
    private fun updateDashboard(state: MainState.Success) {
        Log.d(TAG, "📊 updateDashboard() called")
        try {
            // Update stats
            state.stats?.let { stats ->
                Log.d(TAG, "📊 Updating stats: totalJobs=${stats.totalJobs}, totalTasks=${stats.totalTasks}, totalWorkers=${stats.totalWorkers}")
                binding.totalJobsValue.text = stats.totalJobs.toString()
                binding.totalTasksValue.text = stats.totalTasks.toString()
                binding.activeJobsValue.text = stats.activeJobs.toString()
                binding.completedJobsValue.text = stats.completedJobs.toString()
                Log.d(TAG, "📊 Stats updated successfully")
            } ?: Log.w(TAG, "📊 Stats data is null")
            
            // Update WebSocket stats
            state.websocketStats?.let { wsStats ->
                Log.d(TAG, "🔌 Updating WebSocket stats: connected=${wsStats.connectedWorkers}, available=${wsStats.availableWorkers}")
                binding.connectedWorkersValue.text = wsStats.connectedWorkers.toString()
                binding.availableWorkersValue.text = wsStats.availableWorkers.toString()
                Log.d(TAG, "🔌 WebSocket stats updated successfully")
            } ?: Log.w(TAG, "🔌 WebSocket stats data is null")
            
            // Update recent activities
            state.activity?.let { activities ->
                val displayActivities = activities.take(5)
                Log.d(TAG, "📈 Updating activities: showing ${displayActivities.size} out of ${activities.size} total activities")
                activityAdapter.submitList(displayActivities)
                Log.d(TAG, "📈 Activities updated successfully")
            } ?: Log.w(TAG, "📈 Activity data is null")
            
            Log.d(TAG, "📊 Dashboard update completed")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error updating dashboard", e)
        }
    }
    
    private fun updateConnectionStatus(isConnected: Boolean) {
        Log.d(TAG, "🔌 updateConnectionStatus() called with isConnected: $isConnected")
        try {
            binding.connectionStatusText.text = if (isConnected) "Connected" else "Disconnected"
            binding.connectionStatusText.setTextColor(
                requireContext().getColor(
                    if (isConnected) R.color.success 
                    else R.color.error
                )
            )
            Log.d(TAG, "🔌 Connection status updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error updating connection status", e)
        }
    }
    
    override fun onDestroyView() {
        Log.d(TAG, "🗑️  onDestroyView() called")
        super.onDestroyView()
        _binding = null
        Log.d(TAG, "🗑️  Binding cleared, fragment cleanup completed")
    }
}
