package com.filo.transfer.core.network.transport

import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.protocol.ProtocolConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server-side TCP transport that listens for incoming Filo client connections.
 *
 * RESOURCE OWNERSHIP:
 * - Creates, manages, and owns the [ServerSocket].
 * - Accepts incoming [java.net.Socket] instances and wraps them in [SocketConnection].
 * - Guarantees deterministic cleanup via [close].
 */
class TcpServerTransport : Closeable {

    private var serverSocket: ServerSocket? = null
    private val isClosedFlag = AtomicBoolean(false)

    val isBound: Boolean
        get() = serverSocket?.isBound == true && !isClosedFlag.get()

    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    /**
     * Binds the server socket to the given [port] (0 for system-assigned ephemeral port).
     *
     * @return The local port on which the server is listening.
     */
    @Synchronized
    fun bind(port: Int = ProtocolConstants.DEFAULT_PORT, backlog: Int = 5): Int {
        if (isClosedFlag.get()) {
            throw NetworkError.ConnectionFailed("TcpServerTransport has already been closed")
        }
        if (serverSocket != null && serverSocket?.isBound == true) {
            return serverSocket!!.localPort
        }

        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port), backlog)
            this.serverSocket = socket
            return socket.localPort
        } catch (e: IOException) {
            throw NetworkError.ConnectionFailed("Failed to bind ServerSocket on port $port: ${e.message}", e)
        }
    }

    /**
     * Suspends while waiting for an incoming TCP client connection.
     */
    suspend fun accept(timeoutMs: Int = ProtocolConstants.CONNECT_TIMEOUT_MS): SocketConnection =
        withContext(Dispatchers.IO) {
            val server = serverSocket ?: throw NetworkError.ConnectionFailed("Server is not bound")
            if (isClosedFlag.get() || server.isClosed) {
                throw NetworkError.ConnectionFailed("Server socket is closed")
            }

            try {
                server.soTimeout = timeoutMs
                val clientSocket = server.accept()
                SocketConnection(clientSocket)
            } catch (e: SocketTimeoutException) {
                throw NetworkError.Timeout("Timed out waiting for client connection (${timeoutMs}ms)", e)
            } catch (e: SocketException) {
                if (isClosedFlag.get() || server.isClosed) {
                    throw NetworkError.ConnectionFailed("Server socket was closed", e)
                }
                throw NetworkError.ConnectionFailed("Socket error while accepting client: ${e.message}", e)
            } catch (e: IOException) {
                throw NetworkError.IoError("I/O error while accepting client: ${e.message}", e)
            }
        }

    override fun close() {
        if (isClosedFlag.compareAndSet(false, true)) {
            try {
                serverSocket?.close()
            } catch (_: Throwable) {}
            serverSocket = null
        }
    }
}
