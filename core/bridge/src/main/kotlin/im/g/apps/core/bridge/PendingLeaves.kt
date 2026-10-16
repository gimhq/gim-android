package im.g.apps.core.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Chats the signed-in user left with [GimClient.leaveChat] whose own [MembershipChangeKind.LEFT] change has not
 * arrived yet. gim-core refuses sends to them until then, also across restarts, but does not expose that state, so
 * the app keeps it: add after `leaveChat` returns, remove once [hasOwnLeft] sees the change.
 */
interface PendingLeaveStore {
    /** Chat id -> epoch milliseconds of the `leaveChat` call. */
    val pending: StateFlow<Map<String, Long>>

    fun add(chatId: String, requestedAtMs: Long)

    fun remove(chatId: String)

    /** Forgets every pending leave (sign-out). */
    fun clear()
}

/** Process-local [PendingLeaveStore] for tests and previews. */
class InMemoryPendingLeaveStore(initial: Map<String, Long> = emptyMap()) : PendingLeaveStore {
    private val state = MutableStateFlow(initial)

    override val pending: StateFlow<Map<String, Long>> = state.asStateFlow()

    override fun add(chatId: String, requestedAtMs: Long) = state.update { it + (chatId to requestedAtMs) }

    override fun remove(chatId: String) = state.update { it - chatId }

    override fun clear() {
        state.value = emptyMap()
    }
}

/**
 * Whether [items] contain the own `Left` change of [self] applied at or after [sinceMs]. Membership changes carry
 * only a name: gim-core shows the user's display name, or the user id until the name is resolved.
 */
fun List<TimelineItem>.hasOwnLeft(self: Session, sinceMs: Long): Boolean = any {
    it is TimelineItem.MembershipChange && it.change == MembershipChangeKind.LEFT && it.atMs >= sinceMs &&
        (it.userName == self.displayName || it.userName == self.userId)
}
