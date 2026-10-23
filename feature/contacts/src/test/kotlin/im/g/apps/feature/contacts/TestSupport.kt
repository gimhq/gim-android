package im.g.apps.feature.contacts

import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.UserProfile
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

/** Demo-data fake whose outbox only advances manually, so nothing runs on its own. */
fun demoClient(scope: CoroutineScope): FakeGimClient = FakeGimClient.withDemoData(scope, outboxDelays = null)

/** [GimClient] wrapper that records lookups and can fail selected calls. */
class TestClient(private val delegate: GimClient) : GimClient by delegate {
    val lookups = mutableListOf<String>()
    var findUserError: GimException? = null
    var createDirectChatError: GimException? = null
    var createGroupChatError: GimException? = null
    var chatMembersError: GimException? = null
    var removeMemberError: GimException? = null
    var setGroupInfoError: GimException? = null
    var createDirectChatCalls = 0

    /** When set, replaces the signed-in user's id reported by [userId]. */
    var selfUserId: String? = null

    override fun userId(): String? = selfUserId ?: delegate.userId()

    override suspend fun findUser(handle: String): UserProfile? {
        lookups += handle
        findUserError?.let { throw it }
        return delegate.findUser(handle)
    }

    override suspend fun createDirectChat(userId: String): String {
        createDirectChatCalls++
        createDirectChatError?.let { throw it }
        return delegate.createDirectChat(userId)
    }

    override suspend fun createGroupChat(name: String, memberUserIds: List<String>): String {
        createGroupChatError?.let { throw it }
        return delegate.createGroupChat(name, memberUserIds)
    }

    override suspend fun chatMembers(chatId: String): List<UserProfile> {
        chatMembersError?.let { throw it }
        return delegate.chatMembers(chatId)
    }

    override suspend fun removeMember(chatId: String, userId: String) {
        removeMemberError?.let { throw it }
        delegate.removeMember(chatId, userId)
    }

    override suspend fun setGroupInfo(chatId: String, name: String?, avatarFilePath: String?) {
        setGroupInfoError?.let { throw it }
        delegate.setGroupInfo(chatId, name, avatarFilePath)
    }
}

/** A second hub for suspension tests. */
const val HUB_B = "https://hub-b.example.eu"
