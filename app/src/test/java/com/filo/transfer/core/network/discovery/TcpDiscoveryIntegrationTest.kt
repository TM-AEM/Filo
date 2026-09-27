package com.filo.transfer.core.network.discovery

import com.filo.transfer.core.network.protocol.FrameType
import com.filo.transfer.core.network.protocol.ProtocolFrame
import com.filo.transfer.core.network.transport.TcpClientTransport
import com.filo.transfer.core.network.transport.TcpServerTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpDiscoveryIntegrationTest {

    @Test
    fun `discovered endpoint directly establishes valid TCP connection without protocol modifications`() = runBlocking {
        val server = TcpServerTransport()
        try {
            // 1. Receiver binds to ephemeral port (0) to verify actual bound port propagation
            val actualBoundPort = server.bind(0)
            assertTrue("Bound port must be valid positive TCP port", actualBoundPort > 0)

            // 2. Discovery layer produces DiscoveryDevice using the actual bound port
            val device = DiscoveryDevice(
                id = "Filo-LocalReceiver",
                serviceName = "Filo-LocalReceiver",
                host = "127.0.0.1",
                port = actualBoundPort,
                serviceType = DiscoveryConstants.SERVICE_TYPE
            )

            // 3. Endpoint validation succeeds
            val validation = EndpointValidator.validate(device.host, device.port, allowLoopback = true)
            assertTrue("Local endpoint validation must pass", validation is EndpointValidator.ValidationResult.Valid)

            // 4. Accept server connection concurrently
            val serverConnectionDeferred = async {
                server.accept(timeoutMs = 5000)
            }

            // 5. Client connects directly using DiscoveryDevice's host and port
            val (host, port) = device.toEndpoint()
            val clientConnection = TcpClientTransport.connect(host, port, timeoutMs = 5000)
            val serverConnection = serverConnectionDeferred.await()

            try {
                // 6. Verify full duplex framed binary protocol communication over the discovered endpoint
                val helloPayload = "FILO_DISCOVERY_HELLO".toByteArray(Charsets.UTF_8)
                val helloFrame = ProtocolFrame(
                    type = FrameType.HELLO,
                    sequence = 1L,
                    payload = helloPayload
                )
                clientConnection.sendFrame(helloFrame)

                val receivedFrame = serverConnection.receiveFrame()
                assertEquals(FrameType.HELLO, receivedFrame.type)
                assertEquals(1L, receivedFrame.sequence)
                assertArrayEquals(helloPayload, receivedFrame.payload)

                val ackPayload = "FILO_DISCOVERY_HELLO_ACK".toByteArray(Charsets.UTF_8)
                val ackFrame = ProtocolFrame(
                    type = FrameType.HELLO_ACK,
                    sequence = 1L,
                    payload = ackPayload
                )
                serverConnection.sendFrame(ackFrame)

                val clientReceivedAck = clientConnection.receiveFrame()
                assertEquals(FrameType.HELLO_ACK, clientReceivedAck.type)
                assertEquals(1L, clientReceivedAck.sequence)
                assertArrayEquals(ackPayload, clientReceivedAck.payload)
            } finally {
                clientConnection.close()
                serverConnection.close()
            }
        } finally {
            server.close()
        }
    }
}
