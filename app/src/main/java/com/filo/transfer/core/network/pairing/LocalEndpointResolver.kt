package com.filo.transfer.core.network.pairing

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Resolves a local IPv4 address suitable for QR pairing.
 *
 * Uses [ConnectivityManager] only ([ACCESS_NETWORK_STATE]). Address
 * selection is delegated to [LocalEndpointSelector]; this class only
 * enumerates link addresses. It does not perform DNS lookups.
 */
class LocalEndpointResolver(
    private val connectivityManager: ConnectivityManager
) {

    fun resolve(): LocalEndpointResult {
        return try {
            LocalEndpointSelector.select(collectCandidates())
        } catch (_: Throwable) {
            LocalEndpointResult.Failure("Unable to inspect local network interfaces")
        }
    }

    private fun collectCandidates(): List<LocalEndpointCandidate> {
        val networks = connectivityManager.allNetworks
        if (networks.isEmpty()) return emptyList()
        val activeNetwork = connectivityManager.activeNetwork
        val collected = ArrayList<LocalEndpointCandidate>()
        for (network in networks) {
            collectFromNetwork(network, activeNetwork, collected)
        }
        return collected
    }

    private fun collectFromNetwork(
        network: Network,
        activeNetwork: Network?,
        into: MutableList<LocalEndpointCandidate>
    ) {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
        val kind = classify(capabilities)
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return
        val isActive = network == activeNetwork
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            if (address !is Inet4Address) continue
            val host = address.hostAddress ?: continue
            into.add(LocalEndpointCandidate(ip = host, kind = kind, active = isActive))
        }
    }

    private fun classify(capabilities: NetworkCapabilities): LocalNetworkKind {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        ) {
            return LocalNetworkKind.VPN
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return LocalNetworkKind.WIFI
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return LocalNetworkKind.ETHERNET
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return LocalNetworkKind.CELLULAR
        }
        return LocalNetworkKind.OTHER
    }
}
