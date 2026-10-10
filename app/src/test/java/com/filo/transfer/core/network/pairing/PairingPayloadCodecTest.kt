package com.filo.transfer.core.network.pairing

import com.filo.transfer.core.network.protocol.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadCodecTest {

    private val now = 1_700_000_000L
    private val fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun validJson(
        v: String = "1",
        ip: String = "\"192.168.1.42\"",
        port: String = "50222",
        exp: String = (now + 60).toString(),
        fp: String = "\"$fingerprint\"",
        extra: String = ""
    ): String {
        return """{"v":$v,"ip":$ip,"port":$port,"exp":$exp,"fp":$fp$extra}"""
    }

    private fun decode(raw: String, at: Long = now): PairingDecodeResult {
        return PairingPayloadCodec.decode(raw, nowEpochSeconds = at)
    }

    private fun assertFailure(raw: String, at: Long = now): PairingDecodeResult.Failure {
        val result = decode(raw, at)
        assertTrue("expected Failure for: $raw", result is PairingDecodeResult.Failure)
        return result as PairingDecodeResult.Failure
    }

    @Test
    fun validPayloadRoundTrip() {
        val payload = PairingPayload(
            schemaVersion = 1,
            ip = "192.168.1.42",
            port = 50222,
            expiresAtEpochSeconds = now + 60,
            fingerprint = fingerprint,
            protocolVersion = ProtocolConstants.CURRENT_PROTOCOL_VERSION.toInt()
        )

        val encoded = PairingPayloadCodec.encode(payload)
        val result = decode(encoded)

        assertTrue(result is PairingDecodeResult.Success)
        assertEquals(payload, (result as PairingDecodeResult.Success).payload)
    }

    @Test
    fun payloadOfExactly512BytesIsAcceptedWhenOtherwiseValid() {
        val json = paddedToExactBytes(512)
        assertEquals(512, json.toByteArray(Charsets.UTF_8).size)

        val result = decode(json)
        assertTrue(result is PairingDecodeResult.Success)
        val payload = (result as PairingDecodeResult.Success).payload
        assertEquals("192.168.1.42", payload.ip)
        assertEquals(50222, payload.port)
        assertEquals(fingerprint, payload.fingerprint)
    }

    @Test
    fun payloadLargerThan512BytesIsRejected() {
        val json = paddedToExactBytes(513)
        assertEquals(513, json.toByteArray(Charsets.UTF_8).size)
        assertFailure(json)
    }

    @Test
    fun emptyInputIsRejected() {
        assertFailure("")
        assertFailure("   ")
    }

    @Test
    fun malformedAndTruncatedJsonAreRejected() {
        assertFailure("{")
        assertFailure("""{"v":1,"ip":"192.168.1.42"""")
        assertFailure("not-json")
        assertFailure("{v:1}")
    }

    @Test
    fun nonObjectJsonIsRejected() {
        assertFailure("[]")
        assertFailure("\"string\"")
        assertFailure("1")
        assertFailure("true")
        assertFailure("null")
    }

    @Test
    fun unsupportedSchemaVersionIsRejected() {
        assertFailure(validJson(v = "2"))
        assertFailure(validJson(v = "0"))
        assertFailure(validJson(v = "-1"))
    }

    @Test
    fun missingRequiredFieldsAreRejected() {
        assertFailure("""{"ip":"192.168.1.42","port":50222,"exp":${now + 60},"fp":"$fingerprint"}""")
        assertFailure("""{"v":1,"port":50222,"exp":${now + 60},"fp":"$fingerprint"}""")
        assertFailure("""{"v":1,"ip":"192.168.1.42","exp":${now + 60},"fp":"$fingerprint"}""")
        assertFailure("""{"v":1,"ip":"192.168.1.42","port":50222,"fp":"$fingerprint"}""")
        assertFailure("""{"v":1,"ip":"192.168.1.42","port":50222,"exp":${now + 60}}""")
    }

    @Test
    fun blankOrMalformedIpIsRejected() {
        assertFailure(validJson(ip = "\"\""))
        assertFailure(validJson(ip = "\"   \""))
        assertFailure(validJson(ip = "\"192.168.1\""))
        assertFailure(validJson(ip = "\"192.168.1.256\""))
        assertFailure(validJson(ip = "\"not-an-ip\""))
        assertFailure(validJson(ip = "\"localhost\""))
        assertFailure(validJson(ip = "\"example.com\""))
        assertFailure(validJson(ip = "\"192.168.1.42/24\""))
    }

    @Test
    fun loopbackIsRejected() {
        assertFailure(validJson(ip = "\"127.0.0.1\""))
        assertFailure(validJson(ip = "\"127.0.0.55\""))
        assertFailure(validJson(ip = "\"127.255.255.255\""))
    }

    @Test
    fun unspecifiedAddressesAreRejected() {
        assertFailure(validJson(ip = "\"0.0.0.0\""))
        assertFailure(validJson(ip = "\"::\""))
        assertFailure(validJson(ip = "\"::1\""))
    }

    @Test
    fun invalidPortsAreRejected() {
        assertFailure(validJson(port = "0"))
        assertFailure(validJson(port = "-1"))
        assertFailure(validJson(port = "65536"))
        assertFailure(validJson(port = "50222.5"))
        assertFailure(validJson(port = "\"50222\""))
    }

    @Test
    fun expiredPayloadIsRejected() {
        assertFailure(validJson(exp = (now - 1).toString()))
        assertFailure(validJson(exp = (now - 120).toString()))
    }

    @Test
    fun payloadExpiringAtCurrentTimeIsRejected() {
        assertFailure(validJson(exp = now.toString()))
    }

    @Test
    fun validFutureExpirationIsAccepted() {
        val result = decode(validJson(exp = (now + 1).toString()))
        assertTrue(result is PairingDecodeResult.Success)
        assertEquals(now + 1, (result as PairingDecodeResult.Success).payload.expiresAtEpochSeconds)
    }

    @Test
    fun expirationAtTenMinuteHorizonIsAccepted() {
        val result = decode(validJson(exp = (now + 600).toString()))
        assertTrue(result is PairingDecodeResult.Success)
    }

    @Test
    fun expirationBeyondTenMinuteHorizonIsRejected() {
        assertFailure(validJson(exp = (now + 601).toString()))
        assertFailure(validJson(exp = (now + 3_600).toString()))
    }

    @Test
    fun malformedFingerprintIsRejected() {
        assertFailure(validJson(fp = "\"abc\""))
        assertFailure(validJson(fp = "\"${fingerprint}0\""))
        assertFailure(validJson(fp = "\"${fingerprint.substring(0, 63)}g\""))
        assertFailure(validJson(fp = "\"${fingerprint.substring(0, 63)}\""))
    }

    @Test
    fun uppercaseFingerprintIsRejected() {
        assertFailure(validJson(fp = "\"${fingerprint.uppercase()}\""))
    }

    @Test
    fun unsupportedProtocolVersionIsRejected() {
        assertFailure(validJson(extra = ",\"pv\":2"))
        assertFailure(validJson(extra = ",\"pv\":0"))
        val other = ProtocolConstants.CURRENT_PROTOCOL_VERSION.toInt() + 1
        assertFailure(validJson(extra = ",\"pv\":$other"))
    }

    @Test
    fun absentProtocolVersionIsAccepted() {
        val result = decode(validJson())
        assertTrue(result is PairingDecodeResult.Success)
        assertNull((result as PairingDecodeResult.Success).payload.protocolVersion)
    }

    @Test
    fun matchingProtocolVersionIsAccepted() {
        val pv = ProtocolConstants.CURRENT_PROTOCOL_VERSION.toInt()
        val result = decode(validJson(extra = ",\"pv\":$pv"))
        assertTrue(result is PairingDecodeResult.Success)
        assertEquals(pv, (result as PairingDecodeResult.Success).payload.protocolVersion)
    }

    @Test
    fun unknownFieldsDoNotChangeValidatedBehavior() {
        val result = decode(validJson(extra = ",\"name\":\"Filo\",\"action\":\"delete-all\""))
        assertTrue(result is PairingDecodeResult.Success)
        val payload = (result as PairingDecodeResult.Success).payload
        assertEquals("192.168.1.42", payload.ip)
        assertEquals(50222, payload.port)
        assertEquals(fingerprint, payload.fingerprint)
        assertEquals(1, payload.schemaVersion)
        assertNull(payload.protocolVersion)
    }

    @Test
    fun decodeNeverThrowsOnInvalidInput() {
        val inputs = listOf(
            "",
            "   ",
            "{",
            "[]",
            "null",
            "\u0000",
            "x".repeat(600),
            validJson(ip = "\"127.0.0.1\""),
            validJson(exp = now.toString())
        )
        for (input in inputs) {
            val result = decode(input)
            assertTrue(
                "decode must return Failure or Success for: $input",
                result is PairingDecodeResult.Failure || result is PairingDecodeResult.Success
            )
        }
    }

    private fun paddedToExactBytes(targetBytes: Int): String {
        val prefix =
            """{"v":1,"ip":"192.168.1.42","port":50222,"exp":${now + 60},"fp":"$fingerprint","pad":""""
        val suffix = "\"}"
        val padBytes = targetBytes - prefix.toByteArray(Charsets.UTF_8).size - suffix.toByteArray(Charsets.UTF_8).size
        require(padBytes >= 0) { "target $targetBytes is smaller than the unpadded payload" }
        return prefix + "a".repeat(padBytes) + suffix
    }
}
