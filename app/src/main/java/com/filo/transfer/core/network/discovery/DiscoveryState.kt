package com.filo.transfer.core.network.discovery

/**
 * Explicit states representing the local NSD discovery lifecycle.
 */
sealed interface DiscoveryState {
    /** Discovery is inactive. */
    data object Idle : DiscoveryState

    /** Discovery initiation has been requested. */
    data object Starting : DiscoveryState

    /** Actively listening for and browsing peer Filo services on the local network. */
    data object Discovering : DiscoveryState

    /** Discovery stop has been requested. */
    data object Stopping : DiscoveryState

    /** Discovery has stopped cleanly. */
    data object Stopped : DiscoveryState

    /** Discovery encountered an unrecoverable failure. */
    data class Failed(val error: DiscoveryError) : DiscoveryState
}

/**
 * Explicit states representing the local NSD service advertisement lifecycle.
 */
sealed interface AdvertisingState {
    /** Advertising is inactive. */
    data object Idle : AdvertisingState

    /** Advertisement registration has been requested. */
    data object Starting : AdvertisingState

    /** Actively registered and advertising availability via mDNS. */
    data class Advertising(
        val serviceName: String,
        val port: Int
    ) : AdvertisingState

    /** Unregistration has been requested. */
    data object Stopping : AdvertisingState

    /** Advertisement has been unregistered cleanly. */
    data object Stopped : AdvertisingState

    /** Service advertisement registration encountered an error. */
    data class Failed(val error: DiscoveryError) : AdvertisingState
}
