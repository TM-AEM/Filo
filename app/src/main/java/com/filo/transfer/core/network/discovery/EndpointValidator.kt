package com.filo.transfer.core.network.discovery

import java.net.InetAddress

/**
 * Strict validator for resolved network endpoints to ensure connectivity and security.
 *
 * Enforces:
 * - Non-blank host strings.
 * - Port within valid TCP range (1..65535).
 * - Host IP / hostname validity via [InetAddress].
 * - Loopback address filtering (rejecting 127.0.0.1, ::1, localhost unless [allowLoopback] is true).
 */
object EndpointValidator {

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    /**
     * Validates [host] and [port].
     *
     * @param host Target host address or name.
     * @param port Target TCP port.
     * @param allowLoopback When true, loopback addresses (127.x.x.x, ::1, localhost) are permitted (e.g. for testing).
     */
    fun validate(
        host: String?,
        port: Int,
        allowLoopback: Boolean = false
    ): ValidationResult {
        if (host.isNullOrBlank()) {
            return ValidationResult.Invalid("Host address is missing or blank")
        }

        if (port !in 1..65535) {
            return ValidationResult.Invalid("Port $port is outside valid TCP port range (1..65535)")
        }

        val cleanHost = host.trim().removePrefix("/").lowercase()

        val isLoopback = isLoopbackAddress(cleanHost)
        if (isLoopback && !allowLoopback) {
            return ValidationResult.Invalid(
                "Loopback address '$cleanHost' is not permitted for remote network discovery"
            )
        }

        return try {
            val inetAddress = InetAddress.getByName(cleanHost)
            if (inetAddress.isLoopbackAddress && !allowLoopback) {
                ValidationResult.Invalid("Loopback address '${inetAddress.hostAddress}' is not permitted")
            } else {
                ValidationResult.Valid
            }
        } catch (e: Exception) {
            ValidationResult.Invalid("Malformed or unresolvable host '$cleanHost': ${e.message}")
        }
    }

    /**
     * Determines whether a host string designates a loopback interface.
     */
    fun isLoopbackAddress(host: String): Boolean {
        val normalized = host.trim().removePrefix("/").lowercase()
        if (normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") {
            return true
        }
        if (normalized.startsWith("127.")) {
            return true
        }
        return try {
            InetAddress.getByName(normalized).isLoopbackAddress
        } catch (_: Exception) {
            false
        }
    }
}
