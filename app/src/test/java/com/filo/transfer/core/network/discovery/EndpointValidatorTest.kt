package com.filo.transfer.core.network.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointValidatorTest {

    @Test
    fun `valid host and port returns Valid`() {
        val result = EndpointValidator.validate("192.168.1.100", 50222, allowLoopback = false)
        assertTrue(result is EndpointValidator.ValidationResult.Valid)
    }

    @Test
    fun `host with leading slash is handled correctly`() {
        val result = EndpointValidator.validate("/192.168.1.100", 50222, allowLoopback = false)
        assertTrue(result is EndpointValidator.ValidationResult.Valid)
    }

    @Test
    fun `missing or blank host returns Invalid`() {
        val resultNull = EndpointValidator.validate(null, 50222)
        assertTrue(resultNull is EndpointValidator.ValidationResult.Invalid)
        assertEquals("Host address is missing or blank", (resultNull as EndpointValidator.ValidationResult.Invalid).reason)

        val resultBlank = EndpointValidator.validate("   ", 50222)
        assertTrue(resultBlank is EndpointValidator.ValidationResult.Invalid)
    }

    @Test
    fun `port out of range returns Invalid`() {
        val portZero = EndpointValidator.validate("192.168.1.1", 0)
        assertTrue(portZero is EndpointValidator.ValidationResult.Invalid)

        val portNegative = EndpointValidator.validate("192.168.1.1", -5)
        assertTrue(portNegative is EndpointValidator.ValidationResult.Invalid)

        val portTooLarge = EndpointValidator.validate("192.168.1.1", 65536)
        assertTrue(portTooLarge is EndpointValidator.ValidationResult.Invalid)
    }

    @Test
    fun `valid boundary ports return Valid`() {
        assertTrue(EndpointValidator.validate("192.168.1.1", 1) is EndpointValidator.ValidationResult.Valid)
        assertTrue(EndpointValidator.validate("192.168.1.1", 65535) is EndpointValidator.ValidationResult.Valid)
    }

    @Test
    fun `loopback address rejected when allowLoopback is false`() {
        val localhost = EndpointValidator.validate("localhost", 50222, allowLoopback = false)
        assertTrue(localhost is EndpointValidator.ValidationResult.Invalid)

        val ipv4Loopback = EndpointValidator.validate("127.0.0.1", 50222, allowLoopback = false)
        assertTrue(ipv4Loopback is EndpointValidator.ValidationResult.Invalid)

        val ipv4CustomLoopback = EndpointValidator.validate("127.0.0.55", 50222, allowLoopback = false)
        assertTrue(ipv4CustomLoopback is EndpointValidator.ValidationResult.Invalid)

        val ipv6Loopback = EndpointValidator.validate("::1", 50222, allowLoopback = false)
        assertTrue(ipv6Loopback is EndpointValidator.ValidationResult.Invalid)
    }

    @Test
    fun `loopback address accepted when allowLoopback is true`() {
        val localhost = EndpointValidator.validate("localhost", 50222, allowLoopback = true)
        assertTrue(localhost is EndpointValidator.ValidationResult.Valid)

        val ipv4Loopback = EndpointValidator.validate("127.0.0.1", 50222, allowLoopback = true)
        assertTrue(ipv4Loopback is EndpointValidator.ValidationResult.Valid)

        val ipv6Loopback = EndpointValidator.validate("::1", 50222, allowLoopback = true)
        assertTrue(ipv6Loopback is EndpointValidator.ValidationResult.Valid)
    }

    @Test
    fun `isLoopbackAddress helper accurately identifies loopbacks`() {
        assertTrue(EndpointValidator.isLoopbackAddress("127.0.0.1"))
        assertTrue(EndpointValidator.isLoopbackAddress("127.1.2.3"))
        assertTrue(EndpointValidator.isLoopbackAddress("localhost"))
        assertTrue(EndpointValidator.isLoopbackAddress("::1"))

        assertFalse(EndpointValidator.isLoopbackAddress("192.168.1.1"))
        assertFalse(EndpointValidator.isLoopbackAddress("10.0.0.1"))
        assertFalse(EndpointValidator.isLoopbackAddress("172.16.0.1"))
    }
}
