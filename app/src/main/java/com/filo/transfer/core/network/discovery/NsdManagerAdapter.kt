package com.filo.transfer.core.network.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Adapter interface decoupling Android's [NsdManager] system service from discovery business logic.
 *
 * This design enables deterministic, fast unit and integration testing without requiring two physical devices
 * while preserving direct, untransformed Android platform compatibility.
 */
interface NsdManagerAdapter {
    fun registerService(
        serviceInfo: NsdServiceInfo,
        protocolType: Int,
        listener: NsdManager.RegistrationListener
    )

    fun unregisterService(listener: NsdManager.RegistrationListener)

    fun discoverServices(
        serviceType: String,
        protocolType: Int,
        listener: NsdManager.DiscoveryListener
    )

    fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener)

    fun resolveService(
        serviceInfo: NsdServiceInfo,
        listener: NsdManager.ResolveListener
    )
}

/**
 * Production implementation delegating directly to Android's platform [NsdManager].
 */
class AndroidNsdManagerAdapter(
    private val nsdManager: NsdManager
) : NsdManagerAdapter {

    override fun registerService(
        serviceInfo: NsdServiceInfo,
        protocolType: Int,
        listener: NsdManager.RegistrationListener
    ) {
        nsdManager.registerService(serviceInfo, protocolType, listener)
    }

    override fun unregisterService(listener: NsdManager.RegistrationListener) {
        nsdManager.unregisterService(listener)
    }

    override fun discoverServices(
        serviceType: String,
        protocolType: Int,
        listener: NsdManager.DiscoveryListener
    ) {
        nsdManager.discoverServices(serviceType, protocolType, listener)
    }

    override fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) {
        nsdManager.stopServiceDiscovery(listener)
    }

    override fun resolveService(
        serviceInfo: NsdServiceInfo,
        listener: NsdManager.ResolveListener
    ) {
        nsdManager.resolveService(serviceInfo, listener)
    }
}
