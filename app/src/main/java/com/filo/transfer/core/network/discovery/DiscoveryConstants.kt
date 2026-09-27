package com.filo.transfer.core.network.discovery

/**
 * Constants governing Network Service Discovery (NSD/mDNS) in Filo.
 */
object DiscoveryConstants {
    /**
     * Standard DNS-SD / mDNS service type for Filo peer-to-peer TCP transfers.
     */
    const val SERVICE_TYPE = "_filo._tcp"

    /**
     * Normalized service type with trailing dot as formatted by some mDNS responders.
     */
    const val SERVICE_TYPE_WITH_DOT = "_filo._tcp."

    /**
     * Default human-readable service name prefix.
     */
    const val DEFAULT_NAME_PREFIX = "Filo"

    /**
     * Default timeout for resolving an individual NSD service instance (10 seconds).
     */
    const val RESOLVE_TIMEOUT_MS = 10_000L
}
