package com.example.mcc_phase3.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.mcc_phase3.MobileWorkerActivity
import com.example.mcc_phase3.R

/**
 * Singleton helper for posting event-triggered notifications.
 *
 * Two channels are used by the app:
 *  - "mobile_worker_channel"  (IMPORTANCE_LOW)  — ongoing foreground service notification,
 *    managed entirely by MobileWorkerService.
 *  - CHANNEL_ID_EVENTS        (IMPORTANCE_DEFAULT) — task/connection event alerts shown by
 *    this helper; auto-cancelled when tapped.
 */
object NotificationHelper {

    const val CHANNEL_ID_EVENTS = "worker_events_channel"
    private const val CHANNEL_NAME_EVENTS = "Worker Events"

    // Stable notification IDs so only the latest of each type is visible at a time.
    private const val NOTIFY_TASK_ASSIGNED    = 2001
    private const val NOTIFY_TASK_COMPLETED   = 2002
    private const val NOTIFY_TASK_FAILED      = 2003
    private const val NOTIFY_CONNECTED        = 2004
    private const val NOTIFY_DISCONNECTED     = 2005

    /**
     * Register the events notification channel. Call once from MobileWorkerService.onCreate().
     */
    fun createEventChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_EVENTS,
                CHANNEL_NAME_EVENTS,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Task assignment, completion and connection events"
                setShowBadge(true)
            }
            notificationManager(context).createNotificationChannel(channel)
        }
    }

    // ── Public notification methods ─────────────────────────────────────────

    fun notifyTaskAssigned(context: Context, taskId: String, jobId: String?) {
        val shortId = taskId.takeLast(8)
        val jobPart = if (jobId != null) " (Job: ${jobId.takeLast(8)})" else ""
        post(context, NOTIFY_TASK_ASSIGNED, "Task Assigned", "Task …$shortId received$jobPart")
    }

    fun notifyTaskCompleted(context: Context, taskId: String) {
        post(context, NOTIFY_TASK_COMPLETED, "Task Completed", "Task …${taskId.takeLast(8)} finished successfully")
    }

    fun notifyTaskFailed(context: Context, taskId: String, reason: String?) {
        val msg = reason?.take(80) ?: "Unknown error"
        post(context, NOTIFY_TASK_FAILED, "Task Failed", "Task …${taskId.takeLast(8)}: $msg")
    }

    fun notifyWorkerConnected(context: Context) {
        post(context, NOTIFY_CONNECTED, "Worker Connected", "Successfully connected to foreman")
    }

    fun notifyWorkerDisconnected(context: Context, reason: String? = null) {
        val msg = if (!reason.isNullOrBlank()) "Disconnected: ${reason.take(60)}" else "Connection to foreman lost"
        post(context, NOTIFY_DISCONNECTED, "Worker Disconnected", msg)
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun post(context: Context, id: Int, title: String, content: String) {
        val nm = notificationManager(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) return

        val intent = Intent(context, MobileWorkerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_EVENTS)
            .setSmallIcon(R.drawable.ic_worker)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(id, notification)
    }

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
