package com.filo.transfer.core.network.pairing

data class PairingPayload(
    val schemaVersion: Int,
    val ip: String,
    val port: Int,
    val expiresAtEpochSeconds: Long,
    val fingerprint: String,
    val protocolVersion: Int? = null
)

sealed interface PairingDecodeResult {
    data class Success(val payload: PairingPayload) : PairingDecodeResult
    data class Failure(val reason: String) : PairingDecodeResult
}
