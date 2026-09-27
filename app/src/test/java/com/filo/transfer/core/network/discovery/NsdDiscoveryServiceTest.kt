package com.filo.transfer.core.network.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NsdDiscoveryServiceTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeAdapter: FakeNsdManagerAdapter
    private lateinit var discoveryService: NsdDiscoveryService

    @Before
    fun setUp() {
        fakeAdapter = FakeNsdManagerAdapter()
        discoveryService = NsdDiscoveryService(
            nsdAdapter = fakeAdapter,
            allowLoopback = false,
            coroutineScope = testScope
        )
    }

    @Test
    fun `initial states are Idle and discovered devices is empty`() {
        assertEquals(DiscoveryState.Idle, discoveryService.discoveryState.value)
        assertEquals(AdvertisingState.Idle, discoveryService.advertisingState.value)
        assertTrue(discoveryService.discoveredDevices.value.isEmpty())
    }

    @Test
    fun `startAdvertising transitions to Advertising on registration success`() {
        discoveryService.startAdvertising(50222, "Filo-DeviceA")

        assertEquals(1, fakeAdapter.registerCalls.size)
        val registration = fakeAdapter.registerCalls.first()
        assertEquals("Filo-DeviceA", registration.serviceInfo.serviceName)
        assertEquals(50222, registration.serviceInfo.port)

        // Trigger callback with potentially renamed service info
        val registeredInfo = createServiceInfo("Filo-DeviceA (1)", "_filo._tcp", 50222, "192.168.1.5")
        registration.listener.onServiceRegistered(registeredInfo)

        val state = discoveryService.advertisingState.value
        assertTrue(state is AdvertisingState.Advertising)
        assertEquals("Filo-DeviceA (1)", (state as AdvertisingState.Advertising).serviceName)
        assertEquals(50222, state.port)
    }

    @Test
    fun `startAdvertising is idempotent and does not register twice`() {
        discoveryService.startAdvertising(50222, "Filo-DeviceA")
        discoveryService.startAdvertising(50222, "Filo-DeviceA")

        assertEquals(1, fakeAdapter.registerCalls.size)
    }

    @Test
    fun `startAdvertising with invalid port transitions to Failed`() {
        discoveryService.startAdvertising(0)
        val state = discoveryService.advertisingState.value
        assertTrue(state is AdvertisingState.Failed)
        assertTrue((state as AdvertisingState.Failed).error is DiscoveryError.InvalidEndpoint)
    }

    @Test
    fun `stopAdvertising transitions to Stopped and unregisters listener`() {
        discoveryService.startAdvertising(50222, "Filo-DeviceA")
        val registration = fakeAdapter.registerCalls.first()
        registration.listener.onServiceRegistered(createServiceInfo("Filo-DeviceA", "_filo._tcp", 50222, "192.168.1.5"))

        discoveryService.stopAdvertising()

        assertEquals(1, fakeAdapter.unregisterCalls.size)
        // Simulate unregistration callback
        registration.listener.onServiceUnregistered(registration.serviceInfo)

        assertEquals(AdvertisingState.Stopped, discoveryService.advertisingState.value)
    }

    @Test
    fun `stopAdvertising is safe and idempotent when not advertising`() {
        discoveryService.stopAdvertising()
        discoveryService.stopAdvertising()

        assertEquals(0, fakeAdapter.unregisterCalls.size)
        assertEquals(AdvertisingState.Stopped, discoveryService.advertisingState.value)
    }

    @Test
    fun `startAdvertising registration failure updates state to Failed`() {
        discoveryService.startAdvertising(50222, "Filo-DeviceA")
        val registration = fakeAdapter.registerCalls.first()

        registration.listener.onRegistrationFailed(registration.serviceInfo, NsdManager.FAILURE_INTERNAL_ERROR)

        val state = discoveryService.advertisingState.value
        assertTrue(state is AdvertisingState.Failed)
        val error = (state as AdvertisingState.Failed).error
        assertTrue(error is DiscoveryError.RegistrationFailed)
        assertEquals(NsdManager.FAILURE_INTERNAL_ERROR, (error as DiscoveryError.RegistrationFailed).errorCode)
    }

    @Test
    fun `startDiscovery transitions to Discovering on discovery started callback`() {
        discoveryService.startDiscovery()

        assertEquals(1, fakeAdapter.discoverCalls.size)
        val discoverCall = fakeAdapter.discoverCalls.first()
        assertEquals(DiscoveryConstants.SERVICE_TYPE, discoverCall.serviceType)

        discoverCall.listener.onDiscoveryStarted(DiscoveryConstants.SERVICE_TYPE)
        assertEquals(DiscoveryState.Discovering, discoveryService.discoveryState.value)
    }

    @Test
    fun `startDiscovery is idempotent`() {
        discoveryService.startDiscovery()
        discoveryService.startDiscovery()

        assertEquals(1, fakeAdapter.discoverCalls.size)
    }

    @Test
    fun `stopDiscovery transitions to Stopped and invokes adapter`() {
        discoveryService.startDiscovery()
        val discoverCall = fakeAdapter.discoverCalls.first()
        discoverCall.listener.onDiscoveryStarted(DiscoveryConstants.SERVICE_TYPE)

        discoveryService.stopDiscovery()
        assertEquals(1, fakeAdapter.stopDiscoverCalls.size)

        discoverCall.listener.onDiscoveryStopped(DiscoveryConstants.SERVICE_TYPE)
        assertEquals(DiscoveryState.Stopped, discoveryService.discoveryState.value)
    }

    @Test
    fun `startDiscovery failure updates state to Failed`() {
        discoveryService.startDiscovery()
        val discoverCall = fakeAdapter.discoverCalls.first()

        discoverCall.listener.onStartDiscoveryFailed(DiscoveryConstants.SERVICE_TYPE, NsdManager.FAILURE_MAX_LIMIT)

        val state = discoveryService.discoveryState.value
        assertTrue(state is DiscoveryState.Failed)
        val error = (state as DiscoveryState.Failed).error
        assertTrue(error is DiscoveryError.DiscoveryFailed)
        assertEquals(NsdManager.FAILURE_MAX_LIMIT, (error as DiscoveryError.DiscoveryFailed).errorCode)
    }

    @Test
    fun `service found triggers resolution and emits validated DiscoveryDevice`() = runTest {
        discoveryService.startDiscovery()
        val discoverCall = fakeAdapter.discoverCalls.first()

        val foundInfo = createServiceInfo("Filo-Peer1", "_filo._tcp", 50222, "192.168.1.100")
        discoverCall.listener.onServiceFound(foundInfo)

        // Adapter received resolve call
        assertEquals(1, fakeAdapter.resolveCalls.size)
        val resolveCall = fakeAdapter.resolveCalls.first()
        assertEquals("Filo-Peer1", resolveCall.serviceInfo.serviceName)

        // Resolve successfully
        val resolvedInfo = createServiceInfo("Filo-Peer1", "_filo._tcp", 50222, "192.168.1.100")
        resolveCall.listener.onServiceResolved(resolvedInfo)

        val devices = discoveryService.discoveredDevices.value
        assertEquals(1, devices.size)
        val device = devices.first()
        assertEquals("Filo-Peer1", device.serviceName)
        assertEquals("192.168.1.100", device.host)
        assertEquals(50222, device.port)
        assertTrue(device.isValidEndpoint)
        assertEquals("192.168.1.100" to 50222, device.toEndpoint())
    }

    @Test
    fun `duplicate service found does not duplicate devices in list`() = runTest {
        discoveryService.startDiscovery()
        val discoverCall = fakeAdapter.discoverCalls.first()

        val foundInfo = createServiceInfo("Filo-Peer1", "_filo._tcp", 50222, "192.168.1.100")
        discoverCall.listener.onServiceFound(foundInfo)

        val resolveCall = fakeAdapter.resolveCalls.first()
        resolveCall.listener.onServiceResolved(foundInfo)

        // Same service reported again
        discoverCall.listener.onServiceFound(foundInfo)

        assertEquals(1, discoveryService.discoveredDevices.value.size)
    }

    @Test
    fun `service lost removes device from discovered devices list`() = runTest {
        discoveryService.startDiscovery()
        val discoverCall = fakeAdapter.discoverCalls.first()

        val foundInfo = createServiceInfo("Filo-Peer1", "_filo._tcp", 50222, "192.168.1.100")
        discoverCall.listener.onServiceFound(foundInfo)
        fakeAdapter.resolveCalls.first().listener.onServiceResolved(foundInfo)

        assertEquals(1, discoveryService.discoveredDevices.value.size)

        // Service lost
        discoverCall.listener.onServiceLost(foundInfo)
        assertEquals(0, discoveryService.discoveredDevices.value.size)
    }

    @Test
    fun `own advertised service is filtered out from discovery`() = runTest {
        // Start advertising as "Filo-MySelf"
        discoveryService.startAdvertising(50222, "Filo-MySelf")
        val reg = fakeAdapter.registerCalls.first()
        reg.listener.onServiceRegistered(createServiceInfo("Filo-MySelf", "_filo._tcp", 50222, "192.168.1.5"))

        discoveryService.startDiscovery()
        val discoverCall = fakeAdapter.discoverCalls.first()

        // Discovery hears own service
        val ownFoundInfo = createServiceInfo("Filo-MySelf", "_filo._tcp", 50222, "192.168.1.5")
        discoverCall.listener.onServiceFound(ownFoundInfo)

        // Should not resolve own service
        assertEquals(0, fakeAdapter.resolveCalls.size)
        assertTrue(discoveryService.discoveredDevices.value.isEmpty())
    }

    @Test
    fun `non filo service type is ignored`() = runTest {
        discoveryService.startDiscovery()
        val discoverCall = fakeAdapter.discoverCalls.first()

        val printerInfo = createServiceInfo("HP-Printer", "_ipp._tcp", 631, "192.168.1.200")
        discoverCall.listener.onServiceFound(printerInfo)

        assertEquals(0, fakeAdapter.resolveCalls.size)
        assertTrue(discoveryService.discoveredDevices.value.isEmpty())
    }

    @Test
    fun `invalid resolved endpoint is rejected and not emitted`() = runTest {
        discoveryService.startDiscovery()
        val discoverCall = fakeAdapter.discoverCalls.first()

        val invalidInfo = createServiceInfo("Filo-BadPort", "_filo._tcp", 0, "192.168.1.10")
        discoverCall.listener.onServiceFound(invalidInfo)
        fakeAdapter.resolveCalls.first().listener.onServiceResolved(invalidInfo)

        // Rejected due to port 0
        assertTrue(discoveryService.discoveredDevices.value.isEmpty())
    }

    @Test
    fun `loopback endpoint rejected when allowLoopback is false`() = runTest {
        discoveryService.startDiscovery()
        val discoverCall = fakeAdapter.discoverCalls.first()

        val loopbackInfo = createServiceInfo("Filo-Loopback", "_filo._tcp", 50222, "127.0.0.1")
        discoverCall.listener.onServiceFound(loopbackInfo)
        fakeAdapter.resolveCalls.first().listener.onServiceResolved(loopbackInfo)

        assertTrue(discoveryService.discoveredDevices.value.isEmpty())
    }

    @Test
    fun `loopback endpoint accepted when allowLoopback is true`() = runTest {
        val loopbackDiscoveryService = NsdDiscoveryService(
            nsdAdapter = fakeAdapter,
            allowLoopback = true,
            coroutineScope = testScope
        )

        loopbackDiscoveryService.startDiscovery()
        val discoverCall = fakeAdapter.discoverCalls.first()

        val loopbackInfo = createServiceInfo("Filo-Loopback", "_filo._tcp", 50222, "127.0.0.1")
        discoverCall.listener.onServiceFound(loopbackInfo)
        fakeAdapter.resolveCalls.first().listener.onServiceResolved(loopbackInfo)

        assertEquals(1, loopbackDiscoveryService.discoveredDevices.value.size)
        assertEquals("127.0.0.1", loopbackDiscoveryService.discoveredDevices.value.first().host)
    }

    @Test
    fun `rapid start stop restart transitions execute cleanly`() {
        for (i in 1..5) {
            discoveryService.startAdvertising(50222)
            discoveryService.stopAdvertising()
            discoveryService.startDiscovery()
            discoveryService.stopDiscovery()
        }

        assertEquals(AdvertisingState.Stopped, discoveryService.advertisingState.value)
        assertEquals(DiscoveryState.Stopped, discoveryService.discoveryState.value)
    }

    @Test
    fun `reset cleanly clears all state and devices`() = runTest {
        discoveryService.startAdvertising(50222, "Filo-DeviceA")
        discoveryService.startDiscovery()

        val discoverCall = fakeAdapter.discoverCalls.first()
        val peerInfo = createServiceInfo("Filo-Peer", "_filo._tcp", 50222, "192.168.1.150")
        discoverCall.listener.onServiceFound(peerInfo)
        fakeAdapter.resolveCalls.first().listener.onServiceResolved(peerInfo)

        assertFalse(discoveryService.discoveredDevices.value.isEmpty())

        discoveryService.reset()

        assertEquals(DiscoveryState.Idle, discoveryService.discoveryState.value)
        assertEquals(AdvertisingState.Idle, discoveryService.advertisingState.value)
        assertTrue(discoveryService.discoveredDevices.value.isEmpty())
    }

    private fun createServiceInfo(
        name: String,
        type: String,
        port: Int,
        hostAddress: String
    ): NsdServiceInfo {
        val info = NsdServiceInfo()
        info.serviceName = name
        info.serviceType = type
        info.port = port
        try {
            info.host = InetAddress.getByName(hostAddress)
        } catch (_: Throwable) {
            try {
                val field = NsdServiceInfo::class.java.getDeclaredField("mHost")
                field.isAccessible = true
                field.set(info, InetAddress.getByName(hostAddress))
            } catch (_: Throwable) {}
        }
        return info
    }

    class FakeNsdManagerAdapter : NsdManagerAdapter {
        data class RegisterCall(
            val serviceInfo: NsdServiceInfo,
            val protocolType: Int,
            val listener: NsdManager.RegistrationListener
        )

        data class DiscoverCall(
            val serviceType: String,
            val protocolType: Int,
            val listener: NsdManager.DiscoveryListener
        )

        data class ResolveCall(
            val serviceInfo: NsdServiceInfo,
            val listener: NsdManager.ResolveListener
        )

        val registerCalls = mutableListOf<RegisterCall>()
        val unregisterCalls = mutableListOf<NsdManager.RegistrationListener>()
        val discoverCalls = mutableListOf<DiscoverCall>()
        val stopDiscoverCalls = mutableListOf<NsdManager.DiscoveryListener>()
        val resolveCalls = mutableListOf<ResolveCall>()

        override fun registerService(
            serviceInfo: NsdServiceInfo,
            protocolType: Int,
            listener: NsdManager.RegistrationListener
        ) {
            registerCalls.add(RegisterCall(serviceInfo, protocolType, listener))
        }

        override fun unregisterService(listener: NsdManager.RegistrationListener) {
            unregisterCalls.add(listener)
        }

        override fun discoverServices(
            serviceType: String,
            protocolType: Int,
            listener: NsdManager.DiscoveryListener
        ) {
            discoverCalls.add(DiscoverCall(serviceType, protocolType, listener))
        }

        override fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) {
            stopDiscoverCalls.add(listener)
        }

        override fun resolveService(
            serviceInfo: NsdServiceInfo,
            listener: NsdManager.ResolveListener
        ) {
            resolveCalls.add(ResolveCall(serviceInfo, listener))
        }
    }
}
