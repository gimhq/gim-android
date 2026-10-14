package im.g.apps.core.bridge

import im.g.apps.core.bridge.platform.PlatformHooks

/**
 * Kotlin mirror of gim-core's UniFFI `GimClient` object.
 *
 * gim-core (Rust) owns storage, networking, sync, outbox, MLS and media crypto; this interface is the only
 * surface the UI layer sees. Names and shapes follow UniFFI output (plain records, sealed enums, suspend
 * methods, callback listeners, a `TaskHandle`-style [Subscription]) so the real adapter over the vendored
 * bindings maps 1:1 with no logic of its own. Until the bindings are vendored, use
 * [im.g.apps.core.bridge.fake.FakeGimClient].
 *
 * Every suspend method throws [GimException] on failure. Every `subscribe*` call immediately delivers the
 * current state (a `Reset` diff, or the current value) and then incremental updates.
 */
interface GimClient {
    suspend fun loginState(): LoginState

    suspend fun register(serverUrl: String, handle: String, displayName: String): Session

    suspend fun logout()

    /** Starts the sync loop (stream connection, outbox sender). */
    suspend fun start()

    /** Stops the sync loop. */
    suspend fun stop()

    suspend fun findUser(handle: String): User?

    /** Returns the existing direct chat with [userId] or creates one. */
    suspend fun createDirectChat(userId: UserId): ChatId

    suspend fun createGroupChat(name: String, memberIds: List<UserId>): ChatId

    suspend fun addMember(chatId: ChatId, userId: UserId)

    suspend fun removeMember(chatId: ChatId, userId: UserId)

    suspend fun chatDetails(chatId: ChatId): ChatSummary

    /** Enqueues a text message in the outbox and returns its client id. */
    suspend fun sendText(chatId: ChatId, body: String, replyTo: MessageId?): MessageId

    suspend fun sendMedia(
        chatId: ChatId,
        filePath: String,
        mime: String,
        fileName: String,
        caption: String?,
        replyTo: MessageId?,
    ): MessageId

    suspend fun editMessage(messageId: MessageId, newBody: String)

    suspend fun deleteMessage(messageId: MessageId)

    suspend fun toggleReaction(messageId: MessageId, emoji: String)

    /** Re-queues a [SendStatus.FAILED] message. */
    suspend fun retrySend(messageId: MessageId)

    suspend fun markRead(chatId: ChatId, upTo: MessageId)

    suspend fun setTyping(chatId: ChatId, typing: Boolean)

    /** Loads up to [count] older items into the timeline. Returns true while more history exists. */
    suspend fun paginateBackwards(chatId: ChatId, count: Int): Boolean

    /** Downloads and decrypts a media blob; returns the absolute path of the decrypted file. */
    suspend fun fetchMedia(mediaId: String): String

    fun subscribeChatList(listener: ChatListListener): Subscription

    fun subscribeTimeline(chatId: ChatId, listener: TimelineListener): Subscription

    fun subscribeConnection(listener: ConnectionListener): Subscription

    fun subscribeTyping(listener: TypingListener): Subscription
}

/** Creates a [GimClient] bound to the platform hooks; mirrors gim-core's UniFFI constructor. */
fun interface GimClientFactory {
    fun create(hooks: PlatformHooks): GimClient
}
