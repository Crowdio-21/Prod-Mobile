package com.crowdio.mcc_phase3.ui.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.crowdio.mcc_phase3.R
import com.crowdio.mcc_phase3.utils.EventLogger

class EventLogAdapter : ListAdapter<EventLogger.Event, EventLogAdapter.EventViewHolder>(EventDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event_log, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class EventViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val levelIndicator: View = itemView.findViewById(R.id.level_indicator)
        private val timestampText: TextView = itemView.findViewById(R.id.timestamp_text)
        private val categoryText: TextView = itemView.findViewById(R.id.category_text)
        private val messageText: TextView = itemView.findViewById(R.id.message_text)

        fun bind(event: EventLogger.Event) {
            timestampText.text = event.getFormattedTime()
            categoryText.text = event.category
            messageText.text = event.message

            // Set color based on level
            val color = when (event.level) {
                EventLogger.Level.INFO -> Color.parseColor("#2196F3") // Blue
                EventLogger.Level.SUCCESS -> Color.parseColor("#4CAF50") // Green
                EventLogger.Level.WARNING -> Color.parseColor("#FF9800") // Orange
                EventLogger.Level.ERROR -> Color.parseColor("#F44336") // Red
                EventLogger.Level.DEBUG -> Color.parseColor("#9E9E9E") // Gray
            }
            levelIndicator.setBackgroundColor(color)
        }
    }

    class EventDiffCallback : DiffUtil.ItemCallback<EventLogger.Event>() {
        override fun areItemsTheSame(oldItem: EventLogger.Event, newItem: EventLogger.Event): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: EventLogger.Event, newItem: EventLogger.Event): Boolean {
            return oldItem == newItem
        }
    }
}
