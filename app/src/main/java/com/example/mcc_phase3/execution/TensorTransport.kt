package com.example.mcc_phase3.execution

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*

class TensorTransport(private val scope: CoroutineScope) {
    private val selectorManager = SelectorManager(Dispatchers.IO)

    /**
     * Send an activation tensor to another device.
     * Protocol: [4-byte id length][id bytes][4-byte tensor length][tensor bytes]
     */
    suspend fun sendTensor(host: String, port: Int, layerId: String, data: ByteArray) {
        val socket = aSocket(selectorManager).tcp().connect(host, port)
        val output = socket.openWriteChannel(autoFlush = true)

        // Header: layer ID length + layer ID + tensor length + tensor
        val idBytes = layerId.toByteArray(Charsets.UTF_8)
        output.writeInt(idBytes.size)
        output.writeFully(idBytes, 0, idBytes.size)
        output.writeInt(data.size)
        output.writeFully(data, 0, data.size)

        socket.close()
    }

    /**
     * Listen for incoming tensors from other devices.
     * Returns (layerId, tensorBytes) pairs via callback.
     */
    suspend fun receiveTensor(port: Int, onReceived: suspend (String, ByteArray) -> Unit) {
        val server = aSocket(selectorManager).tcp().bind("0.0.0.0", port)
        while (true) {
            val socket = server.accept()
            scope.launch {
                val input = socket.openReadChannel()

                val idLen = input.readInt()
                val idBytes = ByteArray(idLen)
                input.readFully(idBytes, 0, idLen)
                val layerId = String(idBytes, Charsets.UTF_8)

                val tensorLen = input.readInt()
                val tensorBytes = ByteArray(tensorLen)
                input.readFully(tensorBytes, 0, tensorLen)

                socket.close()
                onReceived(layerId, tensorBytes)
            }
        }
    }
}
