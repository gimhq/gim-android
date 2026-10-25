package im.g.apps

import androidx.test.core.app.ApplicationProvider
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.core.bridge.fake.FakeRecoveryKitCodec
import im.g.apps.feature.settings.InMemoryAppLocales
import im.g.apps.feature.settings.InMemoryThemeModeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * A container whose stored server URL restores a client from [factory] (demo data by default); [keepServerUrl] turns
 * on the keep server for the container and the demo client.
 */
fun testContainer(
    storedUrl: String? = DemoData.SERVER_URL,
    scope: CoroutineScope = CoroutineScope(Job()),
    keepServerUrl: String? = null,
    factory: GimClientFactory = FakeGimClientFactory { _, _ ->
        FakeGimClient.withDemoData(scope, outboxDelays = null, keepServerUrl = keepServerUrl)
    },
) = AppContainer(
    context = ApplicationProvider.getApplicationContext(),
    appScope = scope,
    serverUrlStore = InMemoryServerUrlStore(storedUrl),
    clientFactory = factory,
    themeModeStore = InMemoryThemeModeStore(),
    appLocales = InMemoryAppLocales(),
    keepServerUrl = keepServerUrl,
    recoveryKitCodec = FakeRecoveryKitCodec,
)
