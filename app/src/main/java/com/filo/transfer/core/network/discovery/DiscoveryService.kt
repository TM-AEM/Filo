package com.filo.transfer.core.network.discovery

import kotlinx.coroutines.flow.StateFlow

/**
 * Primary domain interface for local Filo device discovery and mDNS advertisement.
 *
 * Provides lifecycle controls for advertising local transfer availability and browsing
 * for peers on the local network.
 */
interface DiscoveryService {

    /**
     * Observable state of active peer browsing / discovery.
     */
    val discoveryState: StateFlow<DiscoveryState>

    /**
     * Observable state of local service advertisement.
     */
    val advertisingState: StateFlow<AdvertisingState>

    /**
     * Observable list of actively discovered and resolved Filo peer endpoints.
     */
    val discoveredDevices: StateFlow<List<DiscoveryDevice>>

    /**
     * Begins advertising this device's presence and TCP transfer port on the local network.
     *
     * Idempotent: repeated calls while advertising do not create multiple registrations.
     *
     * @param port TCP port on which the receiver is actively listening (1..65535).
     * @param customServiceName Optional custom human-readable name. If null, a collision-safe name is generated.
     */
    fun startAdvertising(port: Int, customServiceName: String? = null)

    /**
     * Stops advertising and unregisters the mDNS service.
     *
     * Idempotent: calling when not advertising is a safe no-op.
     */
    fun stopAdvertising()

    /**
     * Begins browsing for Filo peers on the local network.
     *
     * Idempotent: calling while discovery is already running is a safe no-op.
     */
    fun startDiscovery()

    /**
     * Stops browsing for peers.
     *
     * Idempotent: calling when discovery is stopped is a safe no-op.
     */
    fun stopDiscovery()

    /**
     * Cleanly stops both advertising and discovery, clearing all discovered devices.
     */
    fun reset()
}
