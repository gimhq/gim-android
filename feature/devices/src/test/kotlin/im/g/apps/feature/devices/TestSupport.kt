package im.g.apps.feature.devices

import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Installs a [StandardTestDispatcher] as Main so `runTest` controls `viewModelScope` in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(private val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** A signed-in fake with [linked] approved devices besides the current one; returns the fake and their ids. */
suspend fun signedInClient(scope: CoroutineScope, linked: Int = 1): Pair<FakeGimClient, List<String>> {
    val client = FakeGimClient(scope, clock = { CREATED_AT_MS }, outboxDelays = null)
    client.createIdentity("Sofia Costa")
    val ids = List(linked) {
        val qr = FakeGimClient(scope, outboxDelays = null).startLink(emptyList()).qrString()
        val approval = client.approveLink(qr)
        client.confirmApproval(approval, accepted = true)
        approval.deviceId()
    }
    return client to ids
}

/** [GimClient] wrapper that can fail the devices calls. */
class TestClient(private val delegate: GimClient) : GimClient by delegate {
    var devicesError: GimException? = null
    var revokeError: GimException? = null
    var devicesCalls = 0

    override suspend fun devices(): List<DeviceInfo> {
        devicesCalls++
        devicesError?.let { throw it }
        return delegate.devices()
    }

    override suspend fun revokeDevice(deviceId: String) {
        revokeError?.let { throw it }
        delegate.revokeDevice(deviceId)
    }
}

/** 2026-10-15 12:00 UTC. */
const val CREATED_AT_MS = 1_792_065_600_000L
