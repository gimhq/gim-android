package im.g.apps.feature.chat.timeline

import im.g.apps.core.bridge.MembershipChangeKind
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.displayLabel
import im.g.apps.core.bridge.displayNameOrShortId
import im.g.apps.core.bridge.key
import im.g.apps.core.designsystem.component.ReactionChipModel
import im.g.apps.core.designsystem.component.TickState
import im.g.apps.feature.chat.R
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Maps gim-core timeline snapshots (oldest first) to UI rows (newest first). Rows whose inputs did not
 * change are reused by key, so unchanged rows keep their instances across updates. Not thread-safe: call
 * [map] from one coroutine at a time.
 */
internal class TimelineMapper(private val zone: ZoneId, private val locale: Locale) {
    private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    private val weekdayFormatter = DateTimeFormatter.ofPattern("EEEE", locale)
    private val dayMonthFormatter = DateTimeFormatter.ofPattern(
        dayMonthPattern(
            DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.LONG, null, IsoChronology.INSTANCE, locale),
        ),
        locale,
    )
    private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    private class Cached(val input: Any, val row: TimelineRowUi)

    private data class MessageInput(
        val item: MessageItem,
        val inGroup: Boolean,
        val showSender: Boolean,
        val groupedWithPrevious: Boolean,
        val groupedWithNext: Boolean,
        val imagePath: String?,
        val mediaUnavailable: Boolean,
    )

    private data class DateInput(val dayStartMs: Long, val today: LocalDate)

    /** The signed-in user, or another user by display label. */
    private sealed interface Who {
        data object You : Who

        data class Named(val name: String) : Who
    }

    /** A membership change with its user and actor (null if unknown) resolved by user id. */
    private data class MembershipInput(val change: MembershipChangeKind, val user: Who, val actor: Who?)

    private var cache = HashMap<String, Cached>()

    fun map(
        items: List<TimelineItem>,
        isGroup: Boolean,
        imagePaths: Map<String, String>,
        nowMs: Long,
        unavailableMedia: Set<String> = emptySet(),
        selfUserId: String? = null,
        names: Map<String, String> = emptyMap(),
    ): ImmutableList<TimelineRowUi> {
        val today = localDate(nowMs)
        val next = HashMap<String, Cached>(items.size)
        val rows = persistentListOf<TimelineRowUi>().builder()
        for (index in items.indices.reversed()) {
            val item = items[index]
            val key = item.key
            val input: Any = when (item) {
                is TimelineItem.Message -> {
                    val message = item.item
                    val previous = grouped(items.getOrNull(index - 1), message)
                    MessageInput(
                        item = message,
                        inGroup = isGroup,
                        showSender = isGroup && !message.isOwn && !previous,
                        groupedWithPrevious = previous,
                        groupedWithNext = grouped(items.getOrNull(index + 1), message),
                        imagePath = imagePaths[message.messageId],
                        mediaUnavailable = message.messageId in unavailableMedia,
                    )
                }
                is TimelineItem.DateSeparator -> DateInput(item.dayStartMs, today)
                is TimelineItem.MembershipChange -> membershipInput(item, selfUserId, names)
            }
            val row = cache[key]?.takeIf { it.input == input }?.row ?: buildRow(key, input)
            next[key] = Cached(input, row)
            rows += row
        }
        cache = next
        return rows.build()
    }

    private fun buildRow(key: String, input: Any): TimelineRowUi = when (input) {
        is MessageInput -> messageRow(key, input)
        is DateInput -> DateSeparatorRow(key, dayLabel(input.dayStartMs, input.today))
        is MembershipInput -> SystemRow(key, membershipText(input))
        else -> error("unexpected input $input")
    }

    private fun messageRow(key: String, input: MessageInput): MessageRow {
        val message = input.item
        val content = message.content
        val deleted = content == MessageContent.Deleted
        return MessageRow(
            key = key,
            contentType = when (content) {
                is MessageContent.Text -> CONTENT_TYPE_TEXT
                is MessageContent.Image -> CONTENT_TYPE_IMAGE
                is MessageContent.File -> CONTENT_TYPE_FILE
                MessageContent.Deleted -> CONTENT_TYPE_DELETED
                MessageContent.Unsupported -> CONTENT_TYPE_UNSUPPORTED
            },
            outgoing = message.isOwn,
            senderName = if (input.showSender) message.sender.displayLabel else null,
            body = when (content) {
                is MessageContent.Text -> content.body
                is MessageContent.Image -> content.caption
                is MessageContent.File -> content.caption
                else -> null
            },
            image = (content as? MessageContent.Image)?.let {
                ImageUi(aspectRatio = aspectRatio(it.width, it.height), path = input.imagePath)
            },
            file = (content as? MessageContent.File)?.let {
                val name = it.fileName ?: it.media.messageId
                FileUi(name, formatSize(it.media.size, locale), it.media.mime, typeLabel(name, it.media.mime))
            },
            replyPreview = message.replyTo?.let { ReplyPreviewUi(displayNameOrShortId(it.senderName), it.text) },
            reactions = message.reactions.map { ReactionChipModel(it.emoji, it.count, it.own) }.toImmutableList(),
            edited = message.edited && !deleted,
            deleted = deleted,
            unsupported = content == MessageContent.Unsupported,
            tick = message.sendState?.toTick(),
            time = timeFormatter.format(Instant.ofEpochMilli(message.sentAtMs).atZone(zone)),
            groupedWithPrevious = input.groupedWithPrevious,
            groupedWithNext = input.groupedWithNext,
            mediaUnavailable = input.mediaUnavailable,
            sendFailure = (message.sendState as? SendState.Failed)?.cause,
            sender = if (input.inGroup && !message.isOwn) SenderUi(message.sender.userId, message.sender.displayLabel) else null,
        )
    }

    private fun dayLabel(dayStartMs: Long, today: LocalDate): UiText {
        val day = localDate(dayStartMs)
        return when (day) {
            today -> UiText.Res(R.string.chat_today)
            today.minusDays(1) -> UiText.Res(R.string.chat_yesterday)
            else -> UiText.Raw(
                when {
                    day.isBefore(today) && day.isAfter(today.minusDays(7)) -> weekdayFormatter.format(day)
                    day.year == today.year -> dayMonthFormatter.format(day)
                    else -> dateFormatter.format(day)
                },
            )
        }
    }

    private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private companion object {
        fun grouped(neighbour: TimelineItem?, message: MessageItem): Boolean {
            val other = (neighbour as? TimelineItem.Message)?.item ?: return false
            return other.sender.userId == message.sender.userId &&
                abs(other.sentAtMs - message.sentAtMs) <= GROUPING_WINDOW_MS
        }

        /** Resolves [change] by user id only, never by name; an actor that is the user themselves is dropped. */
        fun membershipInput(
            change: TimelineItem.MembershipChange,
            selfUserId: String?,
            names: Map<String, String>,
        ): MembershipInput {
            fun who(userId: String, fallback: String): Who =
                if (userId == selfUserId) Who.You else Who.Named(displayNameOrShortId(names[userId] ?: fallback))
            return MembershipInput(
                change = change.change,
                user = who(change.userId, change.userName.ifBlank { change.userId }),
                actor = change.actorUserId?.takeIf { it != change.userId }?.let { who(it, it) },
            )
        }

        fun membershipText(input: MembershipInput): UiText {
            fun res(id: Int, vararg args: String) = UiText.Res(id, persistentListOf(*args))
            val actor = input.actor
            return when (val user = input.user) {
                Who.You -> when (input.change) {
                    MembershipChangeKind.ADDED -> when (actor) {
                        is Who.Named -> res(R.string.chat_member_added_you, actor.name)
                        else -> res(R.string.chat_member_you_were_added)
                    }
                    MembershipChangeKind.REMOVED -> res(R.string.chat_member_removed_you)
                    MembershipChangeKind.LEFT -> res(R.string.chat_member_you_left)
                }
                is Who.Named -> when (input.change) {
                    MembershipChangeKind.ADDED -> when (actor) {
                        Who.You -> res(R.string.chat_member_you_added, user.name)
                        is Who.Named -> res(R.string.chat_member_added_by, actor.name, user.name)
                        null -> res(R.string.chat_member_added, user.name)
                    }
                    MembershipChangeKind.REMOVED -> when (actor) {
                        Who.You -> res(R.string.chat_member_you_removed, user.name)
                        is Who.Named -> res(R.string.chat_member_removed_by, actor.name, user.name)
                        null -> res(R.string.chat_member_removed, user.name)
                    }
                    MembershipChangeKind.LEFT -> res(R.string.chat_member_left, user.name)
                }
            }
        }

        fun SendState.toTick(): TickState = when (this) {
            SendState.Pending -> TickState.PENDING
            SendState.Sent -> TickState.SENT
            SendState.Delivered -> TickState.DELIVERED
            SendState.Read -> TickState.READ
            is SendState.Failed -> TickState.FAILED
        }

        fun aspectRatio(width: Int?, height: Int?): Float =
            if (width == null || height == null || width <= 0 || height <= 0) {
                DEFAULT_ASPECT_RATIO
            } else {
                (width.toFloat() / height).coerceIn(MIN_ASPECT_RATIO, MAX_ASPECT_RATIO)
            }

        fun typeLabel(name: String, mime: String): String =
            name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 4 }?.uppercase(Locale.ROOT)
                ?: mime.substringAfter('/').take(4).uppercase(Locale.ROOT)

        const val DEFAULT_ASPECT_RATIO = 4f / 3f
        const val MIN_ASPECT_RATIO = 0.5f
        const val MAX_ASPECT_RATIO = 2f
    }
}

/** [bytes] in B/KB/MB/GB; the number follows [locale] ("1.5 MB" in en, "1,5 MB" in es). */
internal fun formatSize(bytes: Long, locale: Locale): String {
    if (bytes < 1024) return String.format(locale, "%d B", bytes)
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 10) {
        String.format(locale, "%d %s", value.roundToLong(), units[unit])
    } else {
        String.format(locale, "%.1f %s", value, units[unit])
    }
}

/**
 * [longDatePattern] without its year field and the separators that belong to it, e.g. "MMMM d, y" (en-US)
 * becomes "MMMM d" and "d. MMMM y" (de-DE) becomes "d. MMMM".
 */
internal fun dayMonthPattern(longDatePattern: String): String {
    // Fields are runs of one pattern letter; everything else (quoted text included) is a literal.
    val tokens = Regex("([a-zA-Z])\\1*|'[^']*'|[^a-zA-Z']+").findAll(longDatePattern).map { it.value }.toList()
    fun isField(token: String) = token[0] in 'a'..'z' || token[0] in 'A'..'Z'
    val year = tokens.indexOfFirst { it[0] == 'y' || it[0] == 'u' }
    if (year < 0) return longDatePattern
    var start = year
    var end = year + 1
    while (end < tokens.size && !isField(tokens[end])) end++
    // A trailing year also takes the separator before it ("MMMM d, y").
    if (end == tokens.size) while (start > 0 && !isField(tokens[start - 1])) start--
    return (tokens.subList(0, start) + tokens.subList(end, tokens.size)).joinToString("").trim()
}
