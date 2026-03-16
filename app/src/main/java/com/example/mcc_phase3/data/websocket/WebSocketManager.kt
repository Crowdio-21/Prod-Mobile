package com.example.mcc_phase3.data.websocket

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

class WebSocketManager private constructor() {

    private var webSocket: WebSocketClient? = null
    private var isConnected = false
    private val listeners = mutableSetOf<WebSocketListener>()
    private var currentUrl: String? = null
    private val messageCounter = AtomicLong(0)
    private var connectionStartTime: Long = 0
    private var reconnectJob: Job? = null
    private var shouldReconnect = true
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10

    companion object {
        private const val TAG = "WebSocketManager"

        @Volatile
        private var instance: WebSocketManager? = null

        fun getInstance(): WebSocketManager {
            return instance ?: synchronized(this) {
                instance ?: WebSocketManager().also { instance = it }
            }
        }
    }

    init {
        Log.d(TAG, "=== WebSocketManager Initialized ===")
    }

    fun connect(url: String) {
        Log.d(TAG, "connect() called with URL: $url")
        currentUrl = url
        connectionStartTime = System.currentTimeMillis()

        if (isConnected) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        try {
            val uri = URI(url)
            webSocket = object : WebSocketClient(uri) {
                init {
                    // Set connection timeout to 30 seconds
                    setConnectionLostTimeout(30)
                    // Enable keep-alive
                    setTcpNoDelay(true)
                }
                
                override fun onOpen(handshakedata: ServerHandshake?) {
                    val connectionTime = System.currentTimeMillis() - connectionStartTime
                    Log.d(TAG, "Connected to $url in ${connectionTime}ms")
                    isConnected = true
                    listeners.forEach { it.onConnected() }
                }

                override fun onMessage(message: String?) {
                    val msgCount = messageCounter.incrementAndGet()
                    Log.d(TAG, "Message #$msgCount received (${message?.length ?: 0} chars)")
                    message?.let { msg ->
                        listeners.forEach { it.onMessage(msg) }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.w(TAG, "Disconnected from $currentUrl (code=$code, reason=$reason, remote=$remote)")
                    isConnected = false
                    listeners.forEach { it.onDisconnected() }
                    
                    // Attempt reconnection if it wasn't a manual disconnect
                    if (shouldReconnect && currentUrl != null) {
                        scheduleReconnection()
                    }
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket error", ex)
                    listeners.forEach { it.onError(ex) }
                }
            }

            Log.d(TAG, "Starting WebSocket connection...")
            webSocket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to create/connect WebSocket", e)
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect() called")
        shouldReconnect = false
        reconnectJob?.cancel()
        webSocket?.close()
        webSocket = null
        isConnected = false
        currentUrl = null
        reconnectAttempts = 0
        Log.d(TAG, "🧹 WebSocket references cleared")
    }
    
    private fun scheduleReconnection() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.e(TAG, "🚨 Max reconnection attempts reached ($maxReconnectAttempts), giving up")
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            val delay = minOf(1000L * (1 shl reconnectAttempts), 30000L) // Exponential backoff, max 30s
            Log.d(TAG, "Scheduling reconnection attempt ${reconnectAttempts + 1} in ${delay}ms")
            
            delay(delay)
            
            if (shouldReconnect && currentUrl != null) {
                reconnectAttempts++
                Log.d(TAG, "Attempting reconnection #$reconnectAttempts to $currentUrl")
                connect(currentUrl!!)
            }
        }
    }

    fun sendMessage(message: String) {
        if (isConnected) {
            try {
                webSocket?.send(message)
                Log.d(TAG, "Message sent")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
            }
        } else {
            Log.w(TAG, "Cannot send message - not connected")
        }
    }

    fun addListener(listener: WebSocketListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: WebSocketListener) {
        listeners.remove(listener)
    }

    fun isConnected(): Boolean = isConnected

    interface WebSocketListener {
        fun onConnected()
        fun onMessage(message: String)
        fun onDisconnected()
        fun onError(error: Exception?)
    }
}
