package com.example.mcc_phase3.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mcc_phase3.data.models.Activity
import com.example.mcc_phase3.databinding.ItemActivityBinding
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
        
        fun bind(activity: Activity) {
            binding.activityAction.text = activity.action
            binding.activityDetails.text = activity.details
            binding.activityTimestamp.text = formatTimestamp(activity.timestamp)
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
