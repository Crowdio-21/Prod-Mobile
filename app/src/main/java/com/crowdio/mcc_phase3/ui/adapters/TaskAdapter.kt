package com.crowdio.mcc_phase3.ui.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.crowdio.mcc_phase3.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

data class TaskItem(
    val id: String,
    val name: String,
    val status: String,
    val progress: Int,
    val executionTime: String,
    val workType: String = "Other",
    val isPaused: Boolean = false
)

class TaskAdapter(
    private val tasks: MutableList<TaskItem>,
    private var onPauseResumeClick: ((taskId: String, currentlyPaused: Boolean) -> Unit)? = null
) : RecyclerView.Adapter<TaskAdapter.TaskViewHolder>() {

    class TaskViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val taskName: TextView = view.findViewById(R.id.task_name)
        val taskId: TextView = view.findViewById(R.id.task_id)
        val taskStatus: TextView = view.findViewById(R.id.task_status)
        val progressBar: LinearProgressIndicator = view.findViewById(R.id.progress_bar)
        val progressText: TextView = view.findViewById(R.id.progress_text)
        val executionTime: TextView = view.findViewById(R.id.execution_time)
        val taskIcon: ImageView = view.findViewById(R.id.task_icon)
        val workTypeChip: TextView = view.findViewById(R.id.work_type_chip)
        val pauseResumeButton: MaterialButton = view.findViewById(R.id.pause_resume_button)
    }

    fun setOnPauseResumeClickListener(listener: (taskId: String, currentlyPaused: Boolean) -> Unit) {
        onPauseResumeClick = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val task = tasks[position]
        
        holder.taskName.text = task.name
        holder.taskId.text = "ID: ${task.id}"
        holder.progressBar.progress = task.progress
        holder.progressText.text = "${task.progress}%"
        holder.executionTime.text = task.executionTime

        // Work type chip: label + tint colour
        holder.workTypeChip.text = task.workType
        val context = holder.itemView.context
        val workTypeColor = when (task.workType) {
            "Image Processing"  -> context.getColor(R.color.warning)
            "Monte Carlo"       -> context.getColor(R.color.info)
            "Sentiment Analysis"-> context.getColor(R.color.primary)
            else                -> context.getColor(R.color.text_secondary)
        }
        holder.workTypeChip.setTextColor(workTypeColor)
        holder.workTypeChip.backgroundTintList = context.getColorStateList(
            when (task.workType) {
                "Image Processing"   -> R.color.warning
                "Monte Carlo"        -> R.color.info
                "Sentiment Analysis" -> R.color.primary
                else                 -> R.color.text_secondary
            }
        )

        // Status badge + icon colour driven by isPaused
        val displayStatus = if (task.isPaused) "Paused" else task.status
        holder.taskStatus.text = displayStatus

        when {
            task.isPaused -> {
                holder.taskStatus.setTextColor(context.getColor(R.color.warning))
                holder.taskStatus.backgroundTintList = context.getColorStateList(R.color.warning)
                holder.taskIcon.setColorFilter(context.getColor(R.color.warning))
            }
            task.status.lowercase() in listOf("running", "assigned") -> {
                holder.taskStatus.setTextColor(context.getColor(R.color.success))
                holder.taskStatus.backgroundTintList = context.getColorStateList(R.color.success)
                holder.taskIcon.setColorFilter(context.getColor(R.color.success))
            }
            task.status.lowercase() == "pending" -> {
                holder.taskStatus.setTextColor(context.getColor(R.color.warning))
                holder.taskStatus.backgroundTintList = context.getColorStateList(R.color.warning)
                holder.taskIcon.setColorFilter(context.getColor(R.color.warning))
            }
            task.status.lowercase() == "completed" -> {
                holder.taskStatus.setTextColor(context.getColor(R.color.info))
                holder.taskStatus.backgroundTintList = context.getColorStateList(R.color.info)
                holder.taskIcon.setColorFilter(context.getColor(R.color.info))
            }
            task.status.lowercase() == "failed" -> {
                holder.taskStatus.setTextColor(context.getColor(R.color.error))
                holder.taskStatus.backgroundTintList = context.getColorStateList(R.color.error)
                holder.taskIcon.setColorFilter(context.getColor(R.color.error))
            }
        }

        // Pause / Resume toggle button
        if (task.isPaused) {
            holder.pauseResumeButton.text = "Resume"
            holder.pauseResumeButton.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.success))
        } else {
            holder.pauseResumeButton.text = "Pause"
            holder.pauseResumeButton.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.warning))
        }
        holder.pauseResumeButton.setOnClickListener {
            onPauseResumeClick?.invoke(task.id, task.isPaused)
        }
    }

    override fun getItemCount() = tasks.size
    
    fun updateTasks(newTasks: List<TaskItem>) {
        tasks.clear()
        tasks.addAll(newTasks)
        notifyDataSetChanged()
    }
    
    fun addTask(task: TaskItem) {
        tasks.add(0, task)  // Add to beginning
        notifyItemInserted(0)
    }
    
    fun updateTask(position: Int, task: TaskItem) {
        if (position in tasks.indices) {
            tasks[position] = task
            notifyItemChanged(position)
        }
    }
}
