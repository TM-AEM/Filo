package com.filo.transfer.core.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Android Network Service Discovery (NSD / mDNS) implementation for Filo.
 *
 * KEY RESPONSIBILITIES:
 * - Advertises local device availability and bound TCP transfer port using DNS-SD (_filo._tcp).
 * - Browses for Filo peer instances on the local Wi-Fi / LAN network.
 * - Resolves service hostnames and TCP ports with sequential queueing to prevent Android's
 *   native `FAILURE_ALREADY_ACTIVE` resolve concurrency bug.
 * - Validates resolved endpoints (rejecting malformed addresses, out-of-range ports, and unauthorized loopbacks).
 * - Enforces idempotency for start/stop/restart transitions.
 * - Decouples discovery from transfer execution: purely produces [DiscoveryDevice] endpoints.
 */
class NsdDiscoveryService(
    private val nsdAdapter: NsdManagerAdapter,
    private val allowLoopback: Boolean = false,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : DiscoveryService {

    private val advertisingLock = Any()
    private val discoveryLock = Any()

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    override val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    private val _advertisingState = MutableStateFlow<AdvertisingState>(AdvertisingState.Idle)
    override val advertisingState: StateFlow<AdvertisingState> = _advertisingState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveryDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveryDevice>> = _discoveredDevices.asStateFlow()

    private val devicesMap = ConcurrentHashMap<String, DiscoveryDevice>()
    private val pendingResolves = ConcurrentHashMap.newKeySet<String>()

    private var activeRegistrationListener: NsdManager.RegistrationListener? = null
    private var isAdvertisingStarting = false
    private var registeredServiceName: String? = null

    private var activeDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscoveryStarting = false

    private var resolveChannel = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private var resolveJob: Job? = null

    init {
        startResolveWorker()
    }

    override fun startAdvertising(port: Int, customServiceName: String?) {
        val validation = EndpointValidator.validate("127.0.0.1", port, allowLoopback = true)
        if (validation is EndpointValidator.ValidationResult.Invalid) {
            _advertisingState.value = AdvertisingState.Failed(
                DiscoveryError.InvalidEndpoint("Cannot advertise port $port: ${validation.reason}")
            )
            return
        }

        synchronized(advertisingLock) {
            if (activeRegistrationListener != null || isAdvertisingStarting) {
                // Already advertising or actively registering: idempotent no-op
                return
            }
            isAdvertisingStarting = true
            _advertisingState.value = AdvertisingState.Starting
        }

        val baseName = customServiceName?.trim()?.ifBlank { null } ?: generateDefaultServiceName()

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = baseName
            this.serviceType = DiscoveryConstants.SERVICE_TYPE
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                synchronized(advertisingLock) {
                    isAdvertisingStarting = false
                    registeredServiceName = registeredInfo.serviceName
                    val actualPort = registeredInfo.port.takeIf { it > 0 } ?: port
                    _advertisingState.value = AdvertisingState.Advertising(
                        serviceName = registeredInfo.serviceName,
                        port = actualPort
                    )
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(advertisingLock) {
                    isAdvertisingStarting = false
                    activeRegistrationListener = null
                    registeredServiceName = null
                    _advertisingState.value = AdvertisingState.Failed(
                        DiscoveryError.RegistrationFailed(errorCode)
                    )
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                synchronized(advertisingLock) {
                    isAdvertisingStarting = false
                    activeRegistrationListener = null
                    registeredServiceName = null
                    _advertisingState.value = AdvertisingState.Stopped
                }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(advertisingLock) {
                    isAdvertisingStarting = false
                    activeRegistrationListener = null
                    registeredServiceName = null
                    _advertisingState.value = AdvertisingState.Failed(
                        DiscoveryError.RegistrationFailed(
                            errorCode,
                            "Failed to unregister NSD service (error code $errorCode)"
                        )
                    )
                }
            }
        }

        synchronized(advertisingLock) {
            activeRegistrationListener = listener
        }

        try {
            nsdAdapter.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            synchronized(advertisingLock) {
                isAdvertisingStarting = false
                activeRegistrationListener = null
                registeredServiceName = null
                _advertisingState.value = AdvertisingState.Failed(
                    DiscoveryError.Unknown("Failed to initiate service registration: ${e.message}", e)
                )
            }
        }
    }

    override fun stopAdvertising() {
        val listenerToUnregister = synchronized(advertisingLock) {
            val listener = activeRegistrationListener
            activeRegistrationListener = null
            isAdvertisingStarting = false
            registeredServiceName = null

            _advertisingState.value = AdvertisingState.Stopped
            if (listener == null) {
                return
            }
            listener
        }

        try {
            nsdAdapter.unregisterService(listenerToUnregister)
        } catch (_: Exception) {
            // Already handled state transition to Stopped
        }
    }

    override fun startDiscovery() {
        synchronized(discoveryLock) {
            if (activeDiscoveryListener != null || isDiscoveryStarting) {
                // Idempotent: already discovering or starting
                return
            }
            isDiscoveryStarting = true
            _discoveryState.value = DiscoveryState.Starting
            devicesMap.clear()
            pendingResolves.clear()
            _discoveredDevices.value = emptyList()
        }

        ensureResolveWorkerRunning()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                synchronized(discoveryLock) {
                    isDiscoveryStarting = false
                    _discoveryState.value = DiscoveryState.Discovering
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(discoveryLock) {
                    isDiscoveryStarting = false
                    activeDiscoveryListener = null
                    _discoveryState.value = DiscoveryState.Failed(
                        DiscoveryError.DiscoveryFailed(errorCode)
                    )
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                handleServiceFound(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                handleServiceLost(serviceInfo)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                synchronized(discoveryLock) {
                    isDiscoveryStarting = false
                    activeDiscoveryListener = null
                    _discoveryState.value = DiscoveryState.Stopped
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(discoveryLock) {
                    isDiscoveryStarting = false
                    activeDiscoveryListener = null
                    _discoveryState.value = DiscoveryState.Failed(
                        DiscoveryError.DiscoveryFailed(
                            errorCode,
                            "Failed to stop discovery cleanly (error code $errorCode)"
                        )
                    )
                }
            }
        }

        synchronized(discoveryLock) {
            activeDiscoveryListener = listener
        }

        try {
            nsdAdapter.discoverServices(DiscoveryConstants.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            synchronized(discoveryLock) {
                isDiscoveryStarting = false
                activeDiscoveryListener = null
                _discoveryState.value = DiscoveryState.Failed(
                    DiscoveryError.Unknown("Failed to start service discovery: ${e.message}", e)
                )
            }
        }
    }

    override fun stopDiscovery() {
        val listenerToStop = synchronized(discoveryLock) {
            val listener = activeDiscoveryListener
            activeDiscoveryListener = null
            isDiscoveryStarting = false

            _discoveryState.value = DiscoveryState.Stopped
            if (listener == null) {
                return
            }
            listener
        }

        try {
            nsdAdapter.stopServiceDiscovery(listenerToStop)
        } catch (_: Exception) {
            // Already handled state transition to Stopped
        }
    }

    override fun reset() {
        stopDiscovery()
        stopAdvertising()
        devicesMap.clear()
        pendingResolves.clear()
        _discoveredDevices.value = emptyList()
        _discoveryState.value = DiscoveryState.Idle
        _advertisingState.value = AdvertisingState.Idle
    }

    private fun handleServiceFound(serviceInfo: NsdServiceInfo) {
        val type = serviceInfo.serviceType?.removeSuffix(".")
        val expectedType = DiscoveryConstants.SERVICE_TYPE.removeSuffix(".")
        if (type != null && !type.contains("filo", ignoreCase = true) && type != expectedType) {
            return
        }

        val name = serviceInfo.serviceName ?: return

        // Filter out our own advertised service instance
        val selfName = registeredServiceName
        if (selfName != null && name == selfName) {
            return
        }

        // Deduplicate: avoid re-queueing if already known or resolve pending
        if (devicesMap.containsKey(name) || !pendingResolves.add(name)) {
            return
        }

        resolveChannel.trySend(serviceInfo)
    }

    private fun handleServiceLost(serviceInfo: NsdServiceInfo) {
        val name = serviceInfo.serviceName ?: return
        pendingResolves.remove(name)
        val removed = devicesMap.remove(name)
        if (removed != null) {
            _discoveredDevices.value = devicesMap.values.toList()
        }
    }

    private fun ensureResolveWorkerRunning() {
        if (resolveJob?.isActive != true) {
            startResolveWorker()
        }
    }

    private fun startResolveWorker() {
        resolveJob = coroutineScope.launch {
            for (serviceInfo in resolveChannel) {
                if (!isActive) break
                val name = serviceInfo.serviceName ?: continue
                try {
                    val resolved = resolveServiceSuspend(serviceInfo)
                    if (resolved != null) {
                        processResolvedService(resolved)
                    }
                } finally {
                    pendingResolves.remove(name)
                }
            }
        }
    }

    private suspend fun resolveServiceSuspend(serviceInfo: NsdServiceInfo): NsdServiceInfo? {
        return withTimeoutOrNull(DiscoveryConstants.RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (continuation.isActive) {
                            continuation.resume(resolved)
                        }
                    }

                    override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }

                try {
                    nsdAdapter.resolveService(serviceInfo, resolveListener)
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    private fun processResolvedService(serviceInfo: NsdServiceInfo) {
        val host = serviceInfo.host?.hostAddress ?: serviceInfo.host?.hostName
        val port = serviceInfo.port
        val name = serviceInfo.serviceName ?: return

        val validation = EndpointValidator.validate(host, port, allowLoopback)
        if (validation is EndpointValidator.ValidationResult.Invalid) {
            return
        }

        val device = DiscoveryDevice(
            id = name,
            serviceName = name,
            host = host!!,
            port = port,
            serviceType = serviceInfo.serviceType ?: DiscoveryConstants.SERVICE_TYPE,
            resolvedAddress = serviceInfo.host
        )

        devicesMap[name] = device
        _discoveredDevices.value = devicesMap.values.toList()
    }

    private fun generateDefaultServiceName(): String {
        val model = try {
            Build.MODEL.filter { it.isLetterOrDigit() }.take(10)
        } catch (_: Throwable) {
            "Android"
        }.ifBlank { "Android" }
        val randomSuffix = UUID.randomUUID().toString().replace("-", "").take(4)
        return "${DiscoveryConstants.DEFAULT_NAME_PREFIX}-$model-$randomSuffix"
    }

    companion object {
        /**
         * Factory method creating [NsdDiscoveryService] backed by platform [NsdManager].
         */
        fun create(context: Context, allowLoopback: Boolean = false): NsdDiscoveryService {
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            return NsdDiscoveryService(
                nsdAdapter = AndroidNsdManagerAdapter(nsdManager),
                allowLoopback = allowLoopback
            )
        }
    }
}
