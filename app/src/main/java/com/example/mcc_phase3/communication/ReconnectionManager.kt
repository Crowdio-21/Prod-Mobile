package com.example.mcc_phase3.communication

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random

class ReconnectionManager(
    private val scope: CoroutineScope,
    private val connectFn: suspend () -> Unit
) {
    companion object {
        private const val BASE_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 30_000L
        private const val JITTER_FACTOR = 0.2
    }

    private var reconnectJob: Job? = null
    @Volatile
    private var started = false
    @Volatile
    private var connected = false
    @Volatile
    private var attempt = 0

    fun start() {
        started = true
    }

    fun stop() {
        started = false
        connected = false
        attempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
    }

    fun onDisconnected() {
        if (!started) {
            return
        }

        connected = false
        if (reconnectJob?.isActive == true) {
            return
        }

        reconnectJob = scope.launch {
            while (started && !connected && isActive) {
                val delayMs = nextDelayMillis()
                delay(delayMs)
                if (!started || connected || !isActive) {
                    break
                }
                connectFn()
            }
            reconnectJob = null
        }
    }

    fun onConnected() {
        connected = true
        attempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun nextDelayMillis(): Long {
        val exponent = min(attempt, 10)
        val withoutJitter = min(BASE_DELAY_MS * (1L shl exponent), MAX_DELAY_MS)
        attempt += 1

        val jitterWindow = (withoutJitter * JITTER_FACTOR).toLong().coerceAtLeast(1L)
        return (withoutJitter - jitterWindow) + Random.nextLong(jitterWindow * 2)
    }
}