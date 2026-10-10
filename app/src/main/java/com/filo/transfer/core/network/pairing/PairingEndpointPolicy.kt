package com.filo.transfer.core.network.pairing

/**
 * Literal-address policy for QR pairing endpoints.
 *
 * IPv4: only RFC1918 private ranges (10/8, 172.16/12, 192.168/16) and
 * link-local 169.254/16 are accepted. Loopback (127/8), unspecified (0/8),
 * multicast/reserved (>=224), and public addresses are rejected.
 *
 * IPv6 is not supported: any IPv6 literal, including unspecified (::) and
 * loopback (::1), is rejected. Hostnames are rejected so validation never
 * performs DNS resolution.
 */
object PairingEndpointPolicy {

    fun isPermittedAddress(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val ip = raw.trim()
        val octets = parseLiteralIpv4(ip) ?: return false
        return isPermittedIpv4(octets)
    }

    internal fun parseLiteralIpv4(ip: String): IntArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            if (part.any { it !in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isPermittedIpv4(octets: IntArray): Boolean {
        val a = octets[0]
        val b = octets[1]
        if (a == 0) return false
        if (a == 127) return false
        if (a >= 224) return false
        if (a == 10) return true
        if (a == 192 && b == 168) return true
        if (a == 172 && b in 16..31) return true
        if (a == 169 && b == 254) return true
        return false
    }
}
