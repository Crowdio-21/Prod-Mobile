package com.crowdio.mcc_phase3.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.crowdio.mcc_phase3.databinding.FragmentWorkersBinding
import com.crowdio.mcc_phase3.ui.adapters.WorkersAdapter
import com.crowdio.mcc_phase3.ui.mvi.MainEvent
import com.crowdio.mcc_phase3.ui.mvi.MainState
import com.crowdio.mcc_phase3.ui.mvi.MainViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class WorkersFragment : Fragment() {
    private var _binding: FragmentWorkersBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var workersAdapter: WorkersAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorkersBinding.inflate(inflater, container, false)
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
        workersAdapter = WorkersAdapter()
        binding.workersRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = workersAdapter
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
                        // Show only this device's worker information
                        val currentWorker = viewModel.getCurrentWorker()
                        workersAdapter.submitList(currentWorker)
                        
                        // Update UI to show filtering info
                        if (currentWorker.isEmpty()) {
                            // Show message that this device is not registered as a worker
                            showError("This device is not registered as a worker. Make sure the mobile worker is running and connected.")
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
