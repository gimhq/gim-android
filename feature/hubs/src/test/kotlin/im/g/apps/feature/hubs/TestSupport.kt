package im.g.apps.feature.hubs

import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.HubInfo
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

/** A signed-in fake whose home hub is [HUB_A]. */
suspend fun signedInClient(scope: CoroutineScope): FakeGimClient =
    FakeGimClient(scope, outboxDelays = null, homeHubUrl = HUB_A).apply { createIdentity("Sofia Costa") }

/** [GimClient] wrapper that can fail the hub calls the fake does not model. */
class TestClient(private val delegate: GimClient) : GimClient by delegate {
    var addError: GimException? = null
    var removeError: GimException? = null
    var primaryError: GimException? = null
    val removeCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun addHub(url: String): HubInfo {
        addError?.let { throw it }
        return delegate.addHub(url)
    }

    override suspend fun removeHub(hubId: String, confirmOwnedGroups: Boolean) {
        removeCalls += hubId to confirmOwnedGroups
        removeError?.let { throw it }
        delegate.removeHub(hubId, confirmOwnedGroups)
    }

    override suspend fun setPrimaryHub(hubId: String) {
        primaryError?.let { throw it }
        delegate.setPrimaryHub(hubId)
    }
}

const val HUB_A = "http://10.0.2.2:8080"
const val HUB_B = "http://10.0.2.2:8081"
val HUB_A_ID = FakeGimClient.fakeHubId(HUB_A)
val HUB_B_ID = FakeGimClient.fakeHubId(HUB_B)
