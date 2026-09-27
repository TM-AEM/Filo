package com.filo.transfer.core.network.discovery

import java.net.InetAddress

/**
 * Immutable value representation of a discovered Filo peer device endpoint on the local network.
 *
 * Designed to provide the minimal, unauthenticated network endpoint details required
 * to initiate a TCP transfer session via [com.filo.transfer.core.network.transport.TcpClientTransport].
 */
data class DiscoveryDevice(
    val id: String,
    val serviceName: String,
    val host: String,
    val port: Int,
    val serviceType: String = DiscoveryConstants.SERVICE_TYPE,
    val resolvedAddress: InetAddress? = null,
    val discoveredAtTimestamp: Long = System.currentTimeMillis()
) {
    /**
     * True if this device model contains a structurally valid TCP network endpoint.
     */
    val isValidEndpoint: Boolean
        get() = host.isNotBlank() && port in 1..65535

    /**
     * Returns the host and port pair ready for [com.filo.transfer.core.network.transport.TcpClientTransport.connect].
     */
    fun toEndpoint(): Pair<String, Int> = host to port
}
