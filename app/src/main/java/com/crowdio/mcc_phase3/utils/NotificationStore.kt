package com.crowdio.mcc_phase3.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory store for in-app notification items shown in the Notifications tab.
 * Fed by NotificationHelper whenever it posts a system notification.
 */
object NotificationStore {

    enum class Type {
        TASK_ASSIGNED,
        TASK_COMPLETED,
        TASK_FAILED,
        CONNECTED,
        DISCONNECTED
    }

    data class AppNotification(
        val id: String = UUID.randomUUID().toString(),
        val type: Type,
        val title: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val isRead: Boolean = false
    ) {
        fun getFormattedTime(): String =
            SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    private const val MAX_ITEMS = 200

    private val _items = MutableStateFlow<List<AppNotification>>(emptyList())
    val items: StateFlow<List<AppNotification>> = _items

    val unreadCount: Int get() = _items.value.count { !it.isRead }

    fun add(type: Type, title: String, message: String) {
        val updated = listOf(AppNotification(type = type, title = title, message = message)) +
                _items.value
        _items.value = if (updated.size > MAX_ITEMS) updated.take(MAX_ITEMS) else updated
    }

    fun markAllRead() {
        _items.value = _items.value.map { it.copy(isRead = true) }
    }

    fun clearAll() {
        _items.value = emptyList()
    }
}
