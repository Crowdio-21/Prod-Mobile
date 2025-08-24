package com.example.mcc_phase3.data.websocket

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

class WebSocketManager private constructor() {

    private var webSocket: WebSocketClient? = null
    private var isConnected = false
    private val listeners = mutableSetOf<WebSocketListener>()
    private var currentUrl: String? = null
    private val messageCounter = AtomicLong(0)
    private var connectionStartTime: Long = 0

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
        Log.d(TAG, "🔌 connect() called with URL: $url")
        currentUrl = url
        connectionStartTime = System.currentTimeMillis()

        if (isConnected) {
            Log.w(TAG, "⚠️ Already connected, disconnecting first")
            disconnect()
        }

        try {
            val uri = URI(url)
            webSocket = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    val connectionTime = System.currentTimeMillis() - connectionStartTime
                    Log.d(TAG, "✅ Connected to $url in ${connectionTime}ms")
                    isConnected = true
                    listeners.forEach { it.onConnected() }
                }

                override fun onMessage(message: String?) {
                    val msgCount = messageCounter.incrementAndGet()
                    Log.d(TAG, "📨 Message #$msgCount received (${message?.length ?: 0} chars)")
                    message?.let { msg ->
                        listeners.forEach { it.onMessage(msg) }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.w(TAG, "🔌 Disconnected from $currentUrl (code=$code, reason=$reason, remote=$remote)")
                    isConnected = false
                    listeners.forEach { it.onDisconnected() }
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "❌ WebSocket error", ex)
                    listeners.forEach { it.onError(ex) }
                }
            }

            Log.d(TAG, "🚀 Starting WebSocket connection...")
            webSocket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to create/connect WebSocket", e)
        }
    }

    fun disconnect() {
        Log.d(TAG, "🔌 disconnect() called")
        webSocket?.close()
        webSocket = null
        isConnected = false
        currentUrl = null
        Log.d(TAG, "🧹 WebSocket references cleared")
    }

    fun sendMessage(message: String) {
        if (isConnected) {
            try {
                webSocket?.send(message)
                Log.d(TAG, "✅ Message sent")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send message", e)
            }
        } else {
            Log.w(TAG, "⚠️ Cannot send message - not connected")
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
