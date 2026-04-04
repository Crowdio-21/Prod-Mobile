package com.crowdio.mcc_phase3.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Singleton event logger for tracking important runtime events in the app
 */
object EventLogger {
    private const val TAG = "EventLogger"
    private const val MAX_EVENTS = 500 // Keep last 500 events
    
    enum class Level {
        INFO,
        SUCCESS,
        WARNING,
        ERROR,
        DEBUG
    }
    
    data class Event(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val level: Level,
        val category: String,
        val message: String
    ) {
        fun getFormattedTime(): String {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
        
        fun getFormattedDateTime(): String {
            val sdf = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }
    
    private val events = ConcurrentLinkedQueue<Event>()
    private val _eventsFlow = MutableStateFlow<List<Event>>(emptyList())
    val eventsFlow: StateFlow<List<Event>> = _eventsFlow
    
    /**
     * Log an event
     */
    fun log(level: Level, category: String, message: String) {
        val event = Event(level = level, category = category, message = message)
        
        // Add to queue
        events.add(event)
        
        // Trim if exceeds max
        while (events.size > MAX_EVENTS) {
            events.poll()
        }
        
        // Update flow
        _eventsFlow.value = events.toList()
        
        // Also log to Logcat with appropriate level
        val logMessage = "[$category] $message"
        when (level) {
            Level.INFO -> Log.i(TAG, logMessage)
            Level.SUCCESS -> Log.i(TAG, "$logMessage")
            Level.WARNING -> Log.w(TAG, logMessage)
            Level.ERROR -> Log.e(TAG, logMessage)
            Level.DEBUG -> Log.d(TAG, logMessage)
        }
    }
    
    // Convenience methods
    fun info(category: String, message: String) = log(Level.INFO, category, message)
    fun success(category: String, message: String) = log(Level.SUCCESS, category, message)
    fun warning(category: String, message: String) = log(Level.WARNING, category, message)
    fun error(category: String, message: String) = log(Level.ERROR, category, message)
    fun debug(category: String, message: String) = log(Level.DEBUG, category, message)
    
    /**
     * Get all events
     */
    fun getEvents(): List<Event> = events.toList()
    
    /**
     * Get events filtered by category
     */
    fun getEventsByCategory(category: String): List<Event> {
        return events.filter { it.category == category }
    }
    
    /**
     * Get events filtered by level
     */
    fun getEventsByLevel(level: Level): List<Event> {
        return events.filter { it.level == level }
    }
    
    /**
     * Clear all events
     */
    fun clear() {
        events.clear()
        _eventsFlow.value = emptyList()
    }
    
    /**
     * Get event count
     */
    fun getEventCount(): Int = events.size
    
    // Common category constants
    object Categories {
        const val WORKER = "Worker"
        const val TASK = "Task"
        const val WEBSOCKET = "WebSocket"
        const val PYTHON = "Python"
        const val CHECKPOINT = "Checkpoint"
        const val PROGRESS = "Progress"
        const val NETWORK = "Network"
        const val SERVICE = "Service"
        const val ERROR = "Error"
        const val SYSTEM = "System"
    }
}
