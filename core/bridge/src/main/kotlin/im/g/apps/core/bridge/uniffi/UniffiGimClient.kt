package im.g.apps.core.bridge.uniffi

import im.g.apps.core.bridge.ChatListListener
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.ConnectionStateListener
import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.HubInfo
import im.g.apps.core.bridge.HubListListener
import im.g.apps.core.bridge.LinkSession
import im.g.apps.core.bridge.LocalTimeZone
import im.g.apps.core.bridge.NotificationContent
import im.g.apps.core.bridge.PendingApproval
import im.g.apps.core.bridge.PlatformHooks
import im.g.apps.core.bridge.PrivacySettings
import im.g.apps.core.bridge.PushRegistration
import im.g.apps.core.bridge.RecoveryRotation
import im.g.apps.core.bridge.ReportReason
import im.g.apps.core.bridge.Session
import im.g.apps.core.bridge.TaskHandle
import im.g.apps.core.bridge.Timeline
import im.g.apps.core.bridge.TimelineListener
import im.g.apps.core.bridge.TypingListener
import im.g.apps.core.bridge.UserProfile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Creates [GimClient]s backed by gim-core's UniFFI bindings. The native library `libgim_core.so` is loaded
 * on the first [create] call, never earlier.
 */
class UniffiGimClientFactory : GimClientFactory {
    override suspend fun create(config: ClientConfig, hooks: PlatformHooks): GimClient {
        CallbackThreads.install()
        return UniffiGimClient(core { im.g.gimcore.createClient(config.toCore(), PlatformHooksAdapter(hooks)) })
    }
}

/** Maps every call 1:1 onto gim-core's `GimClient`; see the mapping table in [GimClient]. */
internal class UniffiGimClient(private val inner: im.g.gimcore.GimClient) : GimClient {
    override suspend fun session(): Session? = core { inner.session()?.toMirror() }

    override fun userId(): String? = inner.userId()

    override fun localStateReset(): Boolean = inner.localStateReset()

    override suspend fun createIdentity(displayName: String): String = core { inner.createIdentity(displayName) }

    override suspend fun restoreFromRecovery(userId: String, recoveryCode: String, revokeOthers: Boolean) =
        core { inner.restoreFromRecovery(userId, recoveryCode, revokeOthers) }

    override suspend fun rotateRecoveryCode(): RecoveryRotation = core { inner.rotateRecoveryCode().toMirror() }

    override fun recoveryCode(): String? = core { inner.recoveryCode() }

    override fun startLink(hubs: List<String>): LinkSession = UniffiLinkSession(core { inner.startLink(hubs) })

    override suspend fun waitLink(link: LinkSession): String = core { inner.waitLink(link.native()) }

    override suspend fun confirmLink(accepted: Boolean) = core { inner.confirmLink(accepted) }

    override suspend fun approveLink(qrString: String): PendingApproval =
        UniffiPendingApproval(core { inner.approveLink(qrString) })

    override suspend fun confirmApproval(approval: PendingApproval, accepted: Boolean) =
        core { inner.confirmApproval(approval.native(), accepted) }

    override suspend fun devices(): List<DeviceInfo> = core { inner.devices().map { it.toMirror() } }

    override suspend fun userDevices(userId: String): List<DeviceInfo> =
        core { inner.userDevices(userId).map { it.toMirror() } }

    override suspend fun revokeDevice(deviceId: String) = core { inner.revokeDevice(deviceId) }

    override suspend fun hubs(): List<HubInfo> = core { inner.hubs().map { it.toMirror() } }

    override suspend fun addHub(url: String): HubInfo = core { inner.addHub(url).toMirror() }

    override suspend fun removeHub(hubId: String, confirmOwnedGroups: Boolean) =
        core { inner.removeHub(hubId, confirmOwnedGroups) }

    override suspend fun setPrimaryHub(hubId: String) = core { inner.setPrimaryHub(hubId) }

    override suspend fun setDisplayName(name: String) = core { inner.setDisplayName(name) }

    override suspend fun ownProfile(): UserProfile? = core { inner.ownProfile()?.toMirror() }

    override suspend fun setAvatar(filePath: String, mime: String) = core { inner.setAvatar(filePath, mime) }

    override suspend fun removeAvatar() = core { inner.removeAvatar() }

    override suspend fun signOut() = core { inner.signOut() }

    override suspend fun deleteAccount(): List<String> = core { inner.deleteAccount() }

    override suspend fun reportUser(userId: String, reason: ReportReason, note: String?, messageIds: List<String>): String =
        core { inner.reportUser(userId, reason.toCore(), note, messageIds) }

    override suspend fun registerPush(registration: PushRegistration) =
        core { inner.registerPush(registration.toCore()) }

    override suspend fun unregisterPush() = core { inner.unregisterPush() }

    override suspend fun processPush(payload: String): NotificationContent =
        core { inner.processPush(payload).toMirror() }

    override suspend fun startSync() = core { inner.startSync() }

    override suspend fun stopSync() = core { inner.stopSync() }

    override fun setNetworkReachable(reachable: Boolean) = inner.setNetworkReachable(reachable)

    override suspend fun createDirectChat(userId: String): String = core { inner.createDirectChat(userId) }

    override suspend fun createGroupChat(name: String, memberUserIds: List<String>): String =
        core { inner.createGroupChat(name, memberUserIds) }

    override suspend fun chatMembers(chatId: String): List<UserProfile> =
        core { inner.chatMembers(chatId).map { it.toMirror() } }

    override suspend fun addMembers(chatId: String, userIds: List<String>) = core { inner.addMembers(chatId, userIds) }

    override suspend fun removeMember(chatId: String, userId: String) = core { inner.removeMember(chatId, userId) }

    override suspend fun leaveChat(chatId: String) = core { inner.leaveChat(chatId) }

    override suspend fun chatState(chatId: String): ChatState = core { inner.chatState(chatId).toMirror() }

    override suspend fun setGroupInfo(chatId: String, name: String?, avatarFilePath: String?) =
        core { inner.setGroupInfo(chatId, name, avatarFilePath) }

    override suspend fun sendText(chatId: String, body: String, replyTo: String?): String =
        core { inner.sendText(chatId, body, replyTo) }

    override suspend fun sendMedia(
        chatId: String,
        filePath: String,
        mime: String,
        caption: String?,
        replyTo: String?,
    ): String = core { inner.sendMedia(chatId, filePath, mime, caption, replyTo) }

    override suspend fun editMessage(chatId: String, messageId: String, newBody: String) =
        core { inner.editMessage(chatId, messageId, newBody) }

    override suspend fun deleteMessage(chatId: String, messageId: String) =
        core { inner.deleteMessage(chatId, messageId) }

    override suspend fun react(chatId: String, messageId: String, emoji: String) =
        core { inner.react(chatId, messageId, emoji) }

    override suspend fun unreact(chatId: String, messageId: String, emoji: String) =
        core { inner.unreact(chatId, messageId, emoji) }

    override suspend fun retrySend(chatId: String, messageId: String) = core { inner.retrySend(chatId, messageId) }

    override suspend fun cancelSend(chatId: String, messageId: String) = core { inner.cancelSend(chatId, messageId) }

    override suspend fun markRead(chatId: String, upToMessageId: String) =
        core { inner.markRead(chatId, upToMessageId) }

    override suspend fun setTyping(chatId: String, active: Boolean) = core { inner.setTyping(chatId, active) }

    override suspend fun stopTyping(chatId: String) = core { inner.stopTyping(chatId) }

    override suspend fun fetchMedia(chatId: String, messageId: String, destPath: String): String =
        core { inner.fetchMedia(chatId, messageId, destPath) }

    override suspend fun fetchThumbnail(chatId: String, messageId: String, destPath: String): String? =
        core { inner.fetchThumbnail(chatId, messageId, destPath) }

    override suspend fun fetchUserAvatar(userId: String, destPath: String): String =
        core { inner.fetchUserAvatar(userId, destPath) }

    override suspend fun fetchChatAvatar(chatId: String, destPath: String): String =
        core { inner.fetchChatAvatar(chatId, destPath) }

    override suspend fun privacy(): PrivacySettings = core { inner.privacy().toMirror() }

    override suspend fun setReadReceipts(enabled: Boolean) = core { inner.setReadReceipts(enabled) }

    override suspend fun setTypingIndicators(enabled: Boolean) = core { inner.setTypingIndicators(enabled) }

    override suspend fun blockUser(userId: String) = core { inner.blockUser(userId) }

    override suspend fun unblockUser(userId: String) = core { inner.unblockUser(userId) }

    override suspend fun setChatMuted(chatId: String, mutedUntilMs: Long) =
        core { inner.setChatMuted(chatId, mutedUntilMs.toCoreMutedUntil()) }

    override fun mutedForever(): Long = im.g.gimcore.mutedForever().toMirrorMutedUntil()

    override suspend fun setChatPinned(chatId: String, pinned: Boolean) = core { inner.setChatPinned(chatId, pinned) }

    override suspend fun setChatArchived(chatId: String, archived: Boolean) =
        core { inner.setChatArchived(chatId, archived) }

    override suspend fun chatList(listener: ChatListListener): TaskHandle =
        UniffiTaskHandle(core { inner.chatList(ChatListListenerAdapter(listener)) })

    override suspend fun timeline(chatId: String): Timeline = UniffiTimeline(core { inner.timeline(chatId) })

    override fun setLocalTimeZone(zone: LocalTimeZone?) = inner.setLocalTimeZone(zone?.let(::LocalTimeZoneAdapter))

    override suspend fun connectionState(listener: ConnectionStateListener): TaskHandle =
        UniffiTaskHandle(core { inner.connectionState(ConnectionStateListenerAdapter(listener)) })

    override suspend fun typing(chatId: String, listener: TypingListener): TaskHandle =
        UniffiTaskHandle(core { inner.typing(chatId, TypingListenerAdapter(listener)) })

    override suspend fun hubList(listener: HubListListener): TaskHandle =
        UniffiTaskHandle(core { inner.hubList(HubListListenerAdapter(listener)) })
}

internal class UniffiLinkSession(val inner: im.g.gimcore.LinkSession) : LinkSession {
    override fun qrString(): String = inner.qrString()
}

internal class UniffiPendingApproval(val inner: im.g.gimcore.PendingApproval) : PendingApproval {
    override fun sas(): String = inner.sas()

    override fun deviceId(): String = inner.deviceId()
}

/** Only sessions from [UniffiGimClient.startLink] can be passed back to gim-core. */
private fun LinkSession.native(): im.g.gimcore.LinkSession =
    (this as? UniffiLinkSession)?.inner ?: throw GimException.InvalidInput("link: not created by this client")

/** Only approvals from [UniffiGimClient.approveLink] can be passed back to gim-core. */
private fun PendingApproval.native(): im.g.gimcore.PendingApproval =
    (this as? UniffiPendingApproval)?.inner ?: throw GimException.InvalidInput("approval: not created by this client")

internal class UniffiTimeline(private val inner: im.g.gimcore.Timeline) : Timeline {
    override fun chatId(): String = inner.chatId()

    override suspend fun addListener(listener: TimelineListener): TaskHandle =
        UniffiTaskHandle(core { inner.addListener(TimelineListenerAdapter(listener)) })

    override suspend fun paginateBackwards(count: Int): Boolean =
        core { inner.paginateBackwards(count.toPaginationCount()) }

    override fun chatState(): ChatState? = core { inner.chatState()?.toMirror() }
}

/** gim-core takes a `u16`: negative counts are rejected, larger ones clamped. */
internal fun Int.toPaginationCount(): UShort {
    if (this < 0) throw GimException.InvalidInput("count: must not be negative")
    return coerceAtMost(UShort.MAX_VALUE.toInt()).toUShort()
}

/** Holds gim-core's `TaskHandle`; the subscription lives as long as this object is not closed. */
internal class UniffiTaskHandle(private val inner: im.g.gimcore.TaskHandle) : TaskHandle {
    private val closed = AtomicBoolean(false)

    override fun cancel() {
        if (!closed.get()) inner.cancel()
    }

    override fun isFinished(): Boolean = closed.get() || inner.isFinished()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            inner.cancel()
            inner.close()
        }
    }
}

// Listener adapters run on gim-core's callback thread: map and forward synchronously so order is kept.

internal class ChatListListenerAdapter(private val listener: ChatListListener) : im.g.gimcore.ChatListListener {
    override fun onUpdate(diffs: List<im.g.gimcore.ChatListDiff>) = listener.onUpdate(diffs.map { it.toMirror() })
}

internal class TimelineListenerAdapter(private val listener: TimelineListener) : im.g.gimcore.TimelineListener {
    override fun onUpdate(diffs: List<im.g.gimcore.TimelineDiff>) = listener.onUpdate(diffs.map { it.toMirror() })
}

internal class LocalTimeZoneAdapter(private val zone: LocalTimeZone) : im.g.gimcore.LocalTimeZone {
    override fun utcOffsetSecs(atMs: Long): Int = zone.utcOffsetSecs(atMs)
}

internal class ConnectionStateListenerAdapter(
    private val listener: ConnectionStateListener,
) : im.g.gimcore.ConnectionStateListener {
    override fun onChange(state: im.g.gimcore.ConnectionState) = listener.onChange(state.toMirror())
}

internal class TypingListenerAdapter(private val listener: TypingListener) : im.g.gimcore.TypingListener {
    override fun onUpdate(userIds: List<String>) = listener.onUpdate(userIds)
}

internal class HubListListenerAdapter(private val listener: HubListListener) : im.g.gimcore.HubListListener {
    override fun onUpdate(hubs: List<im.g.gimcore.HubInfo>) = listener.onUpdate(hubs.map { it.toMirror() })
}

/**
 * Exposes the mirror [PlatformHooks] to gim-core. Only `secureStoreGet` and `secureStoreSet` may fail across
 * the FFI; their mirror [GimException]s map back 1:1 and any other exception becomes `Storage`.
 */
internal class PlatformHooksAdapter(private val hooks: PlatformHooks) : im.g.gimcore.PlatformHooks {
    override fun secureStoreGet(key: String): ByteArray? = hook { hooks.secureStoreGet(key) }

    override fun secureStoreSet(key: String, value: ByteArray) = hook { hooks.secureStoreSet(key, value) }

    override fun secureStoreDelete(key: String) = hooks.secureStoreDelete(key)

    override fun dataDir(): String = hooks.dataDir()

    override fun isNetworkReachable(): Boolean = hooks.isNetworkReachable()
}

/** Runs a fallible platform hook, rethrowing its failures as gim-core errors. */
private inline fun <T> hook(block: () -> T): T = try {
    block()
} catch (e: GimException) {
    throw e.toCore()
} catch (e: Exception) {
    throw im.g.gimcore.GimException.Storage(e.message ?: e.toString()).apply { initCause(e) }
}

/** Runs a gim-core call, rethrowing its errors as mirror [GimException]s. */
private inline fun <T> core(block: () -> T): T = try {
    block()
} catch (e: im.g.gimcore.GimException) {
    throw e.toMirror()
} catch (e: im.g.gimcore.InternalException) {
    throw GimException.Internal(e.message.orEmpty()).apply { initCause(e) }
}
