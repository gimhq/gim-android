package im.g.apps.feature.contacts

import androidx.annotation.StringRes
import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatSummary

/**
 * One chat action; [chatActions] lists those that apply to a chat, in display order. Chat info and the chat list
 * long-press share it.
 */
enum class ChatAction(@StringRes val label: Int) {
    MUTE(R.string.contacts_chat_action_mute),
    UNMUTE(R.string.contacts_chat_action_unmute),
    PIN(R.string.contacts_chat_action_pin),
    UNPIN(R.string.contacts_chat_action_unpin),
    ARCHIVE(R.string.contacts_chat_action_archive),
    UNARCHIVE(R.string.contacts_chat_action_unarchive),
    BLOCK(R.string.contacts_chat_action_block),
    UNBLOCK(R.string.contacts_chat_action_unblock),
    REPORT(R.string.contacts_chat_action_report),
}

/** How long [ChatAction.MUTE] mutes a chat; [durationMs] is null for "until unmuted". */
enum class MuteOption(val durationMs: Long?, @StringRes val label: Int) {
    ONE_HOUR(HOUR_MS, R.string.contacts_mute_one_hour),
    EIGHT_HOURS(8 * HOUR_MS, R.string.contacts_mute_eight_hours),
    ONE_WEEK(7 * 24 * HOUR_MS, R.string.contacts_mute_one_week),
    ALWAYS(null, R.string.contacts_mute_always),
    ;

    /** The `mutedUntilMs` for a mute starting at [nowMs]; [forever] is `GimClient.mutedForever()`. */
    fun mutedUntilMs(nowMs: Long, forever: Long): Long = durationMs?.let { nowMs + it } ?: forever
}

private const val HOUR_MS = 60 * 60_000L

fun ChatSummary.isMuted(nowMs: Long): Boolean = mutedUntilMs > nowMs

/**
 * The actions for [chat] at [nowMs]: mute, pin and archive (or their undo) for every chat; for a direct chat with a
 * known peer also block (or unblock) and, when [reportAvailable] (a keep server is configured), report.
 */
fun chatActions(chat: ChatSummary, nowMs: Long, reportAvailable: Boolean): List<ChatAction> = buildList {
    add(if (chat.isMuted(nowMs)) ChatAction.UNMUTE else ChatAction.MUTE)
    add(if (chat.pinned) ChatAction.UNPIN else ChatAction.PIN)
    add(if (chat.archived) ChatAction.UNARCHIVE else ChatAction.ARCHIVE)
    if (chat.kind == ChatKind.DIRECT && chat.peer != null) {
        add(if (chat.blocked) ChatAction.UNBLOCK else ChatAction.BLOCK)
        if (reportAvailable) add(ChatAction.REPORT)
    }
}
