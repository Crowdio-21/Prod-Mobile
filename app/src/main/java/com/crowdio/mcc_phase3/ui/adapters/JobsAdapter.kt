package com.crowdio.mcc_phase3.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.crowdio.mcc_phase3.data.models.Job
import com.crowdio.mcc_phase3.databinding.ItemJobBinding
import java.text.SimpleDateFormat
import java.util.*

class JobsAdapter : ListAdapter<Job, JobsAdapter.JobViewHolder>(JobDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JobViewHolder {
        val binding = ItemJobBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return JobViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: JobViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class JobViewHolder(private val binding: ItemJobBinding) : 
        RecyclerView.ViewHolder(binding.root) {
        
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        
        fun bind(job: Job) {
            binding.jobId.text = job.id
            binding.jobStatus.text = job.status.capitalize()
            binding.totalTasks.text = job.totalTasks.toString()
            binding.completedTasks.text = job.completedTasks.toString()
            
            // Calculate progress percentage
            val progress = if (job.totalTasks > 0) {
                (job.completedTasks * 100) / job.totalTasks
            } else {
                0
            }
            binding.jobProgress.progress = progress
            
            // Format creation date
            val formattedDate = try {
                val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    .parse(job.createdAt)
                date?.let { "Created: ${dateFormat.format(it)}" } ?: job.createdAt
            } catch (e: Exception) {
                "Created: ${job.createdAt}"
            }
            binding.jobCreatedAt.text = formattedDate
            
            // Set status badge color based on status
            updateStatusBadge(job.status)
        }
        
        private fun updateStatusBadge(status: String) {
            val colorRes = when (status.lowercase()) {
                "running" -> android.R.color.holo_blue_light
                "completed" -> android.R.color.holo_green_light
                "failed" -> android.R.color.holo_red_light
                "pending" -> android.R.color.holo_orange_light
                else -> android.R.color.darker_gray
            }
            binding.jobStatus.setBackgroundColor(
                binding.root.context.getColor(colorRes)
            )
        }
        
        private fun String.capitalize(): String {
            return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }
    
    private class JobDiffCallback : DiffUtil.ItemCallback<Job>() {
        override fun areItemsTheSame(oldItem: Job, newItem: Job): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: Job, newItem: Job): Boolean {
            return oldItem == newItem
        }
    }
}
