package im.g.apps.feature.chat

import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Ids of own failed messages whose send or retry failed with `UserSuspended`: they offer no retry (gim-core's guidance
 * for a suspended account). gim-core's `SendState.Failed` does not carry the cause, so the chat screen records it;
 * [ChatViewModel] owns one and shares it with the composer.
 */
class SuspendedSends {
    private val _ids = MutableStateFlow<PersistentSet<String>>(persistentSetOf())
    val ids: StateFlow<PersistentSet<String>> = _ids.asStateFlow()

    operator fun contains(messageId: String): Boolean = messageId in _ids.value

    fun add(messageIds: Collection<String>) = _ids.update { it.addAll(messageIds) }
}
