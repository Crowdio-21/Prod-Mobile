package com.example.mcc_phase3.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.mcc_phase3.MobileWorkerActivity
import com.example.mcc_phase3.R
import com.example.mcc_phase3.utils.NotificationStore

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
        val title = "Task Assigned"
        val msg = "Task \u2026$shortId received$jobPart"
        NotificationStore.add(NotificationStore.Type.TASK_ASSIGNED, title, msg)
        post(context, NOTIFY_TASK_ASSIGNED, title, msg)
    }

    fun notifyTaskCompleted(context: Context, taskId: String) {
        val title = "Task Completed"
        val msg = "Task \u2026${taskId.takeLast(8)} finished successfully"
        NotificationStore.add(NotificationStore.Type.TASK_COMPLETED, title, msg)
        post(context, NOTIFY_TASK_COMPLETED, title, msg)
    }

    fun notifyTaskFailed(context: Context, taskId: String, reason: String?) {
        val title = "Task Failed"
        val msg = (reason?.take(80) ?: "Unknown error").let { "Task \u2026${taskId.takeLast(8)}: $it" }
        NotificationStore.add(NotificationStore.Type.TASK_FAILED, title, msg)
        post(context, NOTIFY_TASK_FAILED, title, msg)
    }

    fun notifyWorkerConnected(context: Context) {
        val title = "Worker Connected"
        val msg = "Successfully connected to foreman"
        NotificationStore.add(NotificationStore.Type.CONNECTED, title, msg)
        post(context, NOTIFY_CONNECTED, title, msg)
    }

    fun notifyWorkerDisconnected(context: Context, reason: String? = null) {
        val title = "Worker Disconnected"
        val msg = if (!reason.isNullOrBlank()) "Disconnected: ${reason.take(60)}" else "Connection to foreman lost"
        NotificationStore.add(NotificationStore.Type.DISCONNECTED, title, msg)
        post(context, NOTIFY_DISCONNECTED, title, msg)
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

        val appIcon = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_EVENTS)
            .setSmallIcon(R.drawable.ic_worker)
            .setLargeIcon(appIcon)
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
