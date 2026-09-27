package com.filo.transfer.core.network.discovery

/**
 * Structured errors for local NSD/mDNS discovery and service registration operations.
 */
sealed class DiscoveryError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Registration of the local mDNS service advertisement failed.
     */
    data class RegistrationFailed(
        val errorCode: Int,
        override val message: String = "NSD service registration failed (error code $errorCode)"
    ) : DiscoveryError(message)

    /**
     * Local mDNS discovery scan could not be initiated or failed.
     */
    data class DiscoveryFailed(
        val errorCode: Int,
        override val message: String = "NSD service discovery failed (error code $errorCode)"
    ) : DiscoveryError(message)

    /**
     * Resolving an individual discovered mDNS service instance failed.
     */
    data class ResolutionFailed(
        val serviceName: String,
        val errorCode: Int,
        override val message: String = "NSD resolution failed for '$serviceName' (error code $errorCode)"
    ) : DiscoveryError(message)

    /**
     * The resolved host address or TCP port is invalid, malformed, or unpermitted.
     */
    data class InvalidEndpoint(
        val detail: String,
        override val message: String = "Invalid endpoint resolved: $detail"
    ) : DiscoveryError(message)

    /**
     * An operation was requested that is already actively running.
     */
    data class AlreadyRunning(
        override val message: String = "Discovery or advertising is already running"
    ) : DiscoveryError(message)

    /**
     * An operation was requested to stop when not running.
     */
    data class NotRunning(
        override val message: String = "Discovery or advertising is not running"
    ) : DiscoveryError(message)

    /**
     * Unclassified error during discovery operations.
     */
    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null
    ) : DiscoveryError(message, cause)
}
