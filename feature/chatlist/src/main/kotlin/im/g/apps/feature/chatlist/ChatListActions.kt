package im.g.apps.feature.chatlist

import androidx.annotation.StringRes

/**
 * A chat list long-press action. The same mute, pin and archive actions as chat info (feature/contacts ChatActions),
 * kept here because feature modules do not depend on each other; block and report stay in chat info.
 */
enum class ChatListAction(@StringRes val label: Int, @StringRes val errorMessage: Int) {
    MUTE(R.string.chatlist_action_mute, R.string.chatlist_error_mute),
    UNMUTE(R.string.chatlist_action_unmute, R.string.chatlist_error_unmute),
    PIN(R.string.chatlist_action_pin, R.string.chatlist_error_pin),
    UNPIN(R.string.chatlist_action_unpin, R.string.chatlist_error_unpin),
    ARCHIVE(R.string.chatlist_action_archive, R.string.chatlist_error_archive),
    UNARCHIVE(R.string.chatlist_action_unarchive, R.string.chatlist_error_unarchive),
}

/** How long [ChatListAction.MUTE] mutes a chat; [durationMs] is null for "until unmuted". */
enum class MuteOption(val durationMs: Long?, @StringRes val label: Int) {
    ONE_HOUR(HOUR_MS, R.string.chatlist_mute_one_hour),
    EIGHT_HOURS(8 * HOUR_MS, R.string.chatlist_mute_eight_hours),
    ONE_WEEK(7 * 24 * HOUR_MS, R.string.chatlist_mute_one_week),
    ALWAYS(null, R.string.chatlist_mute_always),
    ;

    /** The `mutedUntilMs` for a mute starting at [nowMs]; [forever] is `GimClient.mutedForever()`. */
    fun mutedUntilMs(nowMs: Long, forever: Long): Long = durationMs?.let { nowMs + it } ?: forever
}

private const val HOUR_MS = 60 * 60_000L
