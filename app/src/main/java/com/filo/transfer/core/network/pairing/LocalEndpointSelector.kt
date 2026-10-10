package com.filo.transfer.core.network.pairing

enum class LocalNetworkKind {
    WIFI,
    ETHERNET,
    VPN,
    CELLULAR,
    OTHER
}

data class LocalEndpointCandidate(
    val ip: String,
    val kind: LocalNetworkKind,
    val active: Boolean = true
)

sealed interface LocalEndpointResult {
    data class Success(val ip: String) : LocalEndpointResult
    data class Failure(val reason: String) : LocalEndpointResult
}

/**
 * Pure selection policy for QR pairing LAN endpoints.
 *
 * Only active Wi-Fi or Ethernet candidates whose addresses pass
 * [PairingEndpointPolicy] are eligible. VPN, cellular, and other
 * transports are never selected, even when they carry a private IPv4.
 *
 * Preference order:
 * 1. Network kind: Wi-Fi, then Ethernet
 * 2. Address class: 192.168/16, 172.16/12, 10/8, then 169.254/16
 * 3. Lexicographic IPv4 string for a stable remainder
 */
object LocalEndpointSelector {

    fun select(candidates: List<LocalEndpointCandidate>): LocalEndpointResult {
        val eligible = candidates.mapNotNull { candidate ->
            if (candidate.kind != LocalNetworkKind.WIFI && candidate.kind != LocalNetworkKind.ETHERNET) {
                return@mapNotNull null
            }
            if (!PairingEndpointPolicy.isPermittedAddress(candidate.ip)) return@mapNotNull null
            RankedCandidate(
                ip = candidate.ip,
                activeRank = if (candidate.active) 0 else 1,
                kindRank = kindRank(candidate.kind),
                rangeRank = rangeRank(candidate.ip)
            )
        }
        val best = eligible.minWithOrNull(comparator) ?: return LocalEndpointResult.Failure(
            "No permitted LAN IPv4 endpoint is available"
        )
        return LocalEndpointResult.Success(best.ip)
    }

    private data class RankedCandidate(
        val ip: String,
        val activeRank: Int,
        val kindRank: Int,
        val rangeRank: Int
    )

    private val comparator = compareBy<RankedCandidate>(
        { it.activeRank },
        { it.kindRank },
        { it.rangeRank },
        { it.ip }
    )

    private fun kindRank(kind: LocalNetworkKind): Int = when (kind) {
        LocalNetworkKind.WIFI -> 0
        LocalNetworkKind.ETHERNET -> 1
        else -> 99
    }

    private fun rangeRank(ip: String): Int {
        val octets = PairingEndpointPolicy.parseLiteralIpv4(ip) ?: return 99
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 192 && b == 168 -> 0
            a == 172 && b in 16..31 -> 1
            a == 10 -> 2
            a == 169 && b == 254 -> 3
            else -> 99
        }
    }
}
