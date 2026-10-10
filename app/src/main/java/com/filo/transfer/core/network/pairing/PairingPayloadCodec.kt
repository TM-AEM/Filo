package com.filo.transfer.core.network.pairing

import com.filo.transfer.core.network.protocol.ProtocolConstants
import com.squareup.moshi.JsonReader
import okio.Buffer

object PairingPayloadCodec {

    const val SUPPORTED_SCHEMA_VERSION = 1
    const val MAX_PAYLOAD_BYTES = 512
    const val MAX_EXPIRY_HORIZON_SECONDS = 600L

    private val FINGERPRINT_PATTERN = Regex("^[0-9a-f]{64}$")

    fun encode(payload: PairingPayload): String {
        val builder = StringBuilder(128)
        builder.append("{\"v\":").append(payload.schemaVersion)
            .append(",\"ip\":\"").append(jsonEscape(payload.ip)).append("\"")
            .append(",\"port\":").append(payload.port)
            .append(",\"exp\":").append(payload.expiresAtEpochSeconds)
            .append(",\"fp\":\"").append(jsonEscape(payload.fingerprint)).append("\"")
        val protocolVersion = payload.protocolVersion
        if (protocolVersion != null) {
            builder.append(",\"pv\":").append(protocolVersion)
        }
        builder.append('}')
        return builder.toString()
    }

    fun decode(raw: String, nowEpochSeconds: Long): PairingDecodeResult {
        return try {
            decodeOrThrow(raw, nowEpochSeconds)
        } catch (_: Throwable) {
            PairingDecodeResult.Failure("Malformed pairing payload")
        }
    }

    private fun decodeOrThrow(raw: String, nowEpochSeconds: Long): PairingDecodeResult {
        if (raw.isBlank()) {
            return PairingDecodeResult.Failure("Empty pairing payload")
        }
        val byteSize = raw.toByteArray(Charsets.UTF_8).size
        if (byteSize > MAX_PAYLOAD_BYTES) {
            return PairingDecodeResult.Failure("Pairing payload exceeds $MAX_PAYLOAD_BYTES bytes")
        }

        val parsed = parseObject(raw) ?: return PairingDecodeResult.Failure("Malformed pairing payload")

        val schemaVersion = parsed.schemaVersion
            ?: return PairingDecodeResult.Failure("Missing schema version")
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return PairingDecodeResult.Failure("Unsupported pairing schema version: $schemaVersion")
        }

        val ip = parsed.ip ?: return PairingDecodeResult.Failure("Missing ip")
        if (!PairingEndpointPolicy.isPermittedAddress(ip)) {
            return PairingDecodeResult.Failure("Disallowed pairing endpoint address")
        }

        val port = parsed.port ?: return PairingDecodeResult.Failure("Missing port")
        if (port !in 1..65535) {
            return PairingDecodeResult.Failure("Port $port is outside valid TCP port range (1..65535)")
        }

        val exp = parsed.exp ?: return PairingDecodeResult.Failure("Missing exp")
        if (exp <= nowEpochSeconds) {
            return PairingDecodeResult.Failure("Pairing payload has expired")
        }
        if (exp > nowEpochSeconds + MAX_EXPIRY_HORIZON_SECONDS) {
            return PairingDecodeResult.Failure("Pairing payload expiration exceeds maximum horizon")
        }

        val fingerprint = parsed.fp ?: return PairingDecodeResult.Failure("Missing fp")
        if (!FINGERPRINT_PATTERN.matches(fingerprint)) {
            return PairingDecodeResult.Failure("Invalid pairing fingerprint")
        }

        val protocolVersion = parsed.pv
        if (protocolVersion != null && protocolVersion != ProtocolConstants.CURRENT_PROTOCOL_VERSION.toInt()) {
            return PairingDecodeResult.Failure("Unsupported protocol version: $protocolVersion")
        }

        return PairingDecodeResult.Success(
            PairingPayload(
                schemaVersion = schemaVersion,
                ip = ip,
                port = port,
                expiresAtEpochSeconds = exp,
                fingerprint = fingerprint,
                protocolVersion = protocolVersion
            )
        )
    }

    private data class RawFields(
        var schemaVersion: Int? = null,
        var ip: String? = null,
        var port: Int? = null,
        var exp: Long? = null,
        var fp: String? = null,
        var pv: Int? = null
    )

    private fun parseObject(raw: String): RawFields? {
        val reader = JsonReader.of(Buffer().writeUtf8(raw))
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            return null
        }
        val fields = RawFields()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "v" -> fields.schemaVersion = readExactInt(reader) ?: return null
                "ip" -> {
                    if (reader.peek() != JsonReader.Token.STRING) return null
                    fields.ip = reader.nextString()
                }
                "port" -> fields.port = readExactInt(reader) ?: return null
                "exp" -> fields.exp = readExactLong(reader) ?: return null
                "fp" -> {
                    if (reader.peek() != JsonReader.Token.STRING) return null
                    fields.fp = reader.nextString()
                }
                "pv" -> fields.pv = readExactInt(reader) ?: return null
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return fields
    }

    private fun readExactInt(reader: JsonReader): Int? {
        if (reader.peek() != JsonReader.Token.NUMBER) return null
        val value = reader.nextDouble()
        if (value != kotlin.math.floor(value)) return null
        if (value > Int.MAX_VALUE || value < Int.MIN_VALUE) return null
        return value.toInt()
    }

    private fun readExactLong(reader: JsonReader): Long? {
        if (reader.peek() != JsonReader.Token.NUMBER) return null
        val value = reader.nextDouble()
        if (value != kotlin.math.floor(value)) return null
        if (value > Long.MAX_VALUE.toDouble() || value < Long.MIN_VALUE.toDouble()) return null
        return value.toLong()
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }
}
