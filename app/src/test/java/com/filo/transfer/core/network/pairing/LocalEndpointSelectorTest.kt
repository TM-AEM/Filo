package com.filo.transfer.core.network.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEndpointSelectorTest {

    private fun wifi(ip: String, active: Boolean = true) =
        LocalEndpointCandidate(ip, LocalNetworkKind.WIFI, active)

    private fun ethernet(ip: String, active: Boolean = true) =
        LocalEndpointCandidate(ip, LocalNetworkKind.ETHERNET, active)

    private fun vpn(ip: String, active: Boolean = true) =
        LocalEndpointCandidate(ip, LocalNetworkKind.VPN, active)

    private fun cellular(ip: String, active: Boolean = true) =
        LocalEndpointCandidate(ip, LocalNetworkKind.CELLULAR, active)

    private fun other(ip: String, active: Boolean = true) =
        LocalEndpointCandidate(ip, LocalNetworkKind.OTHER, active)

    private fun successIp(candidates: List<LocalEndpointCandidate>): String {
        val result = LocalEndpointSelector.select(candidates)
        assertTrue("expected Success, got $result", result is LocalEndpointResult.Success)
        return (result as LocalEndpointResult.Success).ip
    }

    private fun assertFailure(candidates: List<LocalEndpointCandidate>) {
        val result = LocalEndpointSelector.select(candidates)
        assertTrue("expected Failure, got $result", result is LocalEndpointResult.Failure)
    }

    @Test
    fun preferredValidLanIpv4IsSelectedDeterministically() {
        val ip = successIp(
            listOf(
                wifi("10.0.0.5"),
                wifi("192.168.1.42")
            )
        )
        assertEquals("192.168.1.42", ip)
    }

    @Test
    fun wifiIsPreferredOverEthernet() {
        val ip = successIp(
            listOf(
                ethernet("192.168.0.10"),
                wifi("192.168.1.42")
            )
        )
        assertEquals("192.168.1.42", ip)
    }

    @Test
    fun ethernetIsUsedWhenWifiIsAbsent() {
        val ip = successIp(listOf(ethernet("192.168.0.10")))
        assertEquals("192.168.0.10", ip)
    }

    @Test
    fun loopbackIsRejected() {
        assertFailure(listOf(wifi("127.0.0.1"), wifi("127.0.0.55")))
    }

    @Test
    fun unspecifiedAddressIsRejected() {
        assertFailure(listOf(wifi("0.0.0.0")))
    }

    @Test
    fun multicastIsRejected() {
        assertFailure(listOf(wifi("224.0.0.1"), wifi("239.255.255.250")))
    }

    @Test
    fun hostnamesAreNeverAccepted() {
        assertFailure(listOf(wifi("localhost"), wifi("example.com"), wifi("my-phone.local")))
    }

    @Test
    fun ipv6OnlyCandidatesYieldNoSupportedEndpoint() {
        assertFailure(listOf(wifi("fe80::1"), wifi("2001:db8::1"), wifi("::1"), wifi("::")))
    }

    @Test
    fun noCandidatesYieldsFailure() {
        assertFailure(emptyList())
    }

    @Test
    fun vpnAddressIsNotSelectedWhenWifiLanExists() {
        val ip = successIp(
            listOf(
                vpn("10.8.0.2"),
                wifi("192.168.1.42")
            )
        )
        assertEquals("192.168.1.42", ip)
    }

    @Test
    fun vpnOrCellularAloneIsNotSelected() {
        assertFailure(listOf(vpn("10.8.0.2")))
        assertFailure(listOf(cellular("10.20.30.40")))
        assertFailure(listOf(other("192.168.1.1")))
    }

    @Test
    fun publicIpv4IsRejectedEvenOnWifi() {
        assertFailure(listOf(wifi("8.8.8.8"), wifi("1.1.1.1")))
    }

    @Test
    fun linkLocalIsAcceptedWhenItIsTheOnlyLanCandidate() {
        val ip = successIp(listOf(wifi("169.254.10.20")))
        assertEquals("169.254.10.20", ip)
    }

    @Test
    fun rfc1918IsPreferredOverLinkLocal() {
        val ip = successIp(
            listOf(
                wifi("169.254.10.20"),
                wifi("192.168.1.42")
            )
        )
        assertEquals("192.168.1.42", ip)
    }

    @Test
    fun inactiveWifiIsNotPreferredOverActiveEthernet() {
        val ip = successIp(
            listOf(
                wifi("192.168.1.42", active = false),
                ethernet("192.168.0.10", active = true)
            )
        )
        assertEquals("192.168.0.10", ip)
    }
}
