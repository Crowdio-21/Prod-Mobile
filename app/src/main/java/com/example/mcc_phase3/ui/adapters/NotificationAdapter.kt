package com.example.mcc_phase3.ui.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mcc_phase3.R
import com.example.mcc_phase3.utils.NotificationStore
import com.example.mcc_phase3.utils.NotificationStore.Type

class NotificationAdapter :
    ListAdapter<NotificationStore.AppNotification, NotificationAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NotificationStore.AppNotification>() {
            override fun areItemsTheSame(
                a: NotificationStore.AppNotification,
                b: NotificationStore.AppNotification
            ) = a.id == b.id

            override fun areContentsTheSame(
                a: NotificationStore.AppNotification,
                b: NotificationStore.AppNotification
            ) = a == b
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val strip: View       = itemView.findViewById(R.id.type_strip)
        val icon: ImageView   = itemView.findViewById(R.id.notification_icon)
        val title: TextView   = itemView.findViewById(R.id.notification_title)
        val message: TextView = itemView.findViewById(R.id.notification_message)
        val time: TextView    = itemView.findViewById(R.id.notification_time)
        val dot: View         = itemView.findViewById(R.id.unread_dot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val ctx  = holder.itemView.context

        holder.title.text   = item.title
        holder.message.text = item.message
        holder.time.text    = item.getFormattedTime()
        holder.dot.visibility = if (item.isRead) View.GONE else View.VISIBLE

        // Color + icon per notification type
        val (colorRes, iconRes) = when (item.type) {
            Type.TASK_ASSIGNED   -> Pair(R.color.primary,  R.drawable.ic_worker)
            Type.TASK_COMPLETED  -> Pair(R.color.success,  R.drawable.ic_status_ok)
            Type.TASK_FAILED     -> Pair(R.color.error,    R.drawable.ic_disconnected)
            Type.CONNECTED       -> Pair(R.color.success,  R.drawable.ic_status_ok)
            Type.DISCONNECTED    -> Pair(R.color.pause_worker, R.drawable.ic_disconnected)
        }

        val color = ContextCompat.getColor(ctx, colorRes)
        holder.strip.setBackgroundColor(color)
        holder.icon.setImageResource(iconRes)
        holder.icon.imageTintList = ColorStateList.valueOf(color)

        // Dim read items slightly
        holder.itemView.alpha = if (item.isRead) 0.65f else 1f
    }
}
