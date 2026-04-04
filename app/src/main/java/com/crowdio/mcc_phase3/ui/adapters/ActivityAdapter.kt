package com.crowdio.mcc_phase3.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.crowdio.mcc_phase3.R
import com.crowdio.mcc_phase3.data.models.Activity
import com.crowdio.mcc_phase3.databinding.ItemActivityBinding
import java.text.SimpleDateFormat
import java.util.*

class ActivityAdapter : ListAdapter<Activity, ActivityAdapter.ActivityViewHolder>(ActivityDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActivityViewHolder {
        val binding = ItemActivityBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ActivityViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ActivityViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class ActivityViewHolder(private val binding: ItemActivityBinding) : 
        RecyclerView.ViewHolder(binding.root) {
        
        private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val context = binding.root.context
        
        fun bind(activity: Activity) {
            // Set basic information
            binding.activityAction.text = activity.action
            binding.activityDetails.text = activity.details
            binding.activityTimestamp.text = formatTimestamp(activity.timestamp)
            
            // Set status icon and colors
            setStatusIcon(activity.status)
            
            // Handle progress and result sections based on status
            when (activity.status) {
                "executing" -> {
                    showProgressSection(activity)
                    hideResultSection()
                }
                "completed", "failed" -> {
                    hideProgressSection()
                    showResultSection(activity)
                }
                else -> {
                    hideProgressSection()
                    hideResultSection()
                }
            }
        }
        
        private fun setStatusIcon(status: String) {
            val (icon, color) = when (status) {
                "executing" -> "[Executing]" to ContextCompat.getColor(context, R.color.primary_color)
                "completed" -> "[Completed]" to ContextCompat.getColor(context, R.color.success)
                "failed" -> "[Failed]" to ContextCompat.getColor(context, R.color.error)
                "pending" -> "⏳" to ContextCompat.getColor(context, R.color.warning)
                else -> "ℹ️" to ContextCompat.getColor(context, R.color.primary_color)
            }
            
            binding.statusIcon.text = icon
            binding.statusIcon.setTextColor(color)
        }
        
        private fun showProgressSection(activity: Activity) {
            binding.progressSection.visibility = ViewGroup.VISIBLE
            binding.taskProgressBar.progress = activity.progress
            
            val progressText = when {
                activity.progress < 25 -> "Initializing..."
                activity.progress < 50 -> "Processing..."
                activity.progress < 75 -> "Executing..."
                activity.progress < 100 -> "Finalizing..."
                else -> "Completing..."
            }
            
            binding.progressText.text = "$progressText ${activity.progress}%"
            
            // Show execution time if available
            activity.executionTime?.let { timeMs ->
                val timeSeconds = timeMs / 1000.0
                binding.executionTime.text = "${String.format("%.1f", timeSeconds)}s"
            } ?: run {
                binding.executionTime.text = "Running..."
            }
        }
        
        private fun hideProgressSection() {
            binding.progressSection.visibility = ViewGroup.GONE
        }
        
        private fun showResultSection(activity: Activity) {
            binding.resultSection.visibility = ViewGroup.VISIBLE
            
            // Extract result from details or show status
            val resultText = when (activity.status) {
                "completed" -> {
                    // Try to extract result from details
                    val details = activity.details
                    if (details.contains("Result:")) {
                        details.substringAfter("Result:").trim()
                    } else if (details.contains("completed successfully")) {
                        "Task completed successfully"
                    } else {
                        "Completed"
                    }
                }
                "failed" -> {
                    if (activity.details.contains("Error:")) {
                        activity.details.substringAfter("Error:").trim()
                    } else {
                        "Task failed"
                    }
                }
                else -> "Unknown status"
            }
            
            binding.resultText.text = resultText
            
            // Show execution time
            activity.executionTime?.let { timeMs ->
                val timeSeconds = timeMs / 1000.0
                val statusText = if (activity.status == "completed") "Completed in" else "Failed after"
                binding.executionTimeResult.text = "$statusText ${String.format("%.1f", timeSeconds)}s"
            } ?: run {
                binding.executionTimeResult.text = if (activity.status == "completed") "Completed" else "Failed"
            }
        }
        
        private fun hideResultSection() {
            binding.resultSection.visibility = ViewGroup.GONE
        }
        
        private fun formatTimestamp(timestamp: String): String {
            return try {
                val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    .parse(timestamp)
                date?.let { dateFormat.format(it) } ?: timestamp
            } catch (e: Exception) {
                timestamp
            }
        }
    }
    
    private class ActivityDiffCallback : DiffUtil.ItemCallback<Activity>() {
        override fun areItemsTheSame(oldItem: Activity, newItem: Activity): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.action == newItem.action
        }
        
        override fun areContentsTheSame(oldItem: Activity, newItem: Activity): Boolean {
            return oldItem == newItem
        }
    }
}
