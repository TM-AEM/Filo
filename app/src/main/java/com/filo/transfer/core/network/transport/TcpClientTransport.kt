package com.filo.transfer.core.network.transport

import com.filo.transfer.core.network.model.NetworkError
import com.filo.transfer.core.network.protocol.ProtocolConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Client-side TCP transport that initiates connection to a Filo peer server.
 */
object TcpClientTransport {

    /**
     * Connects to the given [host] and [port] with bounded connection timeout.
     *
     * @return An active, owned [SocketConnection].
     */
    suspend fun connect(
        host: String,
        port: Int,
        timeoutMs: Int = ProtocolConstants.CONNECT_TIMEOUT_MS
    ): SocketConnection = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            SocketConnection.configureSocket(socket)
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            SocketConnection(socket)
        } catch (e: SocketTimeoutException) {
            try { socket.close() } catch (_: Throwable) {}
            throw NetworkError.Timeout("Connection to $host:$port timed out after ${timeoutMs}ms", e)
        } catch (e: SocketException) {
            try { socket.close() } catch (_: Throwable) {}
            throw NetworkError.ConnectionFailed("Failed to connect to $host:$port: ${e.message}", e)
        } catch (e: IOException) {
            try { socket.close() } catch (_: Throwable) {}
            throw NetworkError.IoError("I/O error connecting to $host:$port: ${e.message}", e)
        }
    }
}
