package com.example.mcc_phase3.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.mcc_phase3.databinding.FragmentJobsBinding
import com.example.mcc_phase3.ui.adapters.JobsAdapter
import com.example.mcc_phase3.ui.mvi.MainEvent
import com.example.mcc_phase3.ui.mvi.MainState
import com.example.mcc_phase3.ui.mvi.MainViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class JobsFragment : Fragment() {
    private var _binding: FragmentJobsBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var jobsAdapter: JobsAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentJobsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()
        viewModel.handleEvent(MainEvent.LoadData)
    }
    
    private fun setupRecyclerView() {
        jobsAdapter = JobsAdapter()
        binding.jobsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = jobsAdapter
        }
    }
    
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.handleEvent(MainEvent.RefreshData)
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
                        // Show only jobs assigned to this device
                        val filteredJobs = viewModel.getJobsForCurrentWorker()
                        jobsAdapter.submitList(filteredJobs)
                        
                        // Update UI to show filtering info
                        if (filteredJobs.isEmpty()) {
                            // Show message that no jobs are assigned to this device
                            showError("No jobs assigned to this device. Make sure the mobile worker is running and connected.")
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
        _binding = null
    }
}
