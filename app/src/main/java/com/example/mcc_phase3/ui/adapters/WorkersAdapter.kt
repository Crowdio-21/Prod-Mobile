package com.example.mcc_phase3.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mcc_phase3.data.models.Worker
import com.example.mcc_phase3.databinding.ItemWorkerBinding
import java.text.SimpleDateFormat
import java.util.*

class WorkersAdapter : ListAdapter<Worker, WorkersAdapter.WorkerViewHolder>(WorkerDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkerViewHolder {
        val binding = ItemWorkerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return WorkerViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: WorkerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class WorkerViewHolder(private val binding: ItemWorkerBinding) : 
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(worker: Worker) {
            binding.workerId.text = worker.id
            binding.workerStatus.text = worker.status.capitalize()
            binding.currentTask.text = worker.currentTaskId ?: "Idle"
            binding.tasksCompleted.text = worker.totalTasksCompleted.toString()
            binding.tasksFailed.text = worker.totalTasksFailed.toString()
            binding.executionTime.text = "N/A" // Not available in API response
            binding.uptime.text = formatLastSeen(worker.lastSeen)
            
            // Set status badge color based on status
            updateStatusBadge(worker.status == "online")
        }
        
        private fun formatLastSeen(lastSeen: String): String {
            return try {
                // Parse ISO timestamp and format it
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.getDefault())
                val date = inputFormat.parse(lastSeen)
                val outputFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                outputFormat.format(date ?: Date())
            } catch (e: Exception) {
                "Unknown"
            }
        }
        
        private fun updateStatusBadge(isConnected: Boolean) {
            val colorRes = if (isConnected) {
                android.R.color.holo_green_light
            } else {
                android.R.color.holo_red_light
            }
            binding.workerStatus.setBackgroundColor(
                binding.root.context.getColor(colorRes)
            )
        }
    }
    
    private class WorkerDiffCallback : DiffUtil.ItemCallback<Worker>() {
        override fun areItemsTheSame(oldItem: Worker, newItem: Worker): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: Worker, newItem: Worker): Boolean {
            return oldItem == newItem
        }
    }
}
