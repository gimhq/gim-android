package im.g.apps.feature.chat.timeline

import im.g.apps.core.bridge.MembershipChangeKind
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.TimelineItem
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
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Maps gim-core timeline snapshots (oldest first) to UI rows (newest first). Rows whose inputs did not
 * change are reused by key, so unchanged rows keep their instances across updates. Not thread-safe: call
 * [map] from one coroutine at a time.
 */
internal class TimelineMapper(private val zone: ZoneId, locale: Locale) {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    private class Cached(val input: Any, val row: TimelineRowUi)

    private data class MessageInput(
        val item: MessageItem,
        val showSender: Boolean,
        val groupedWithPrevious: Boolean,
        val groupedWithNext: Boolean,
        val imagePath: String?,
    )

    private data class DateInput(val day: LocalDate, val today: LocalDate)

    private var cache = HashMap<String, Cached>()

    /**
     * Core's separators bucket by UTC day (M1; the binding exposes no UTC offset), so they are dropped and
     * rebuilt here from the item timestamps in [zone], keyed by the local day start.
     */
    fun map(
        items: List<TimelineItem>,
        isGroup: Boolean,
        imagePaths: Map<String, String>,
        nowMs: Long,
    ): ImmutableList<TimelineRowUi> {
        val today = localDate(nowMs)
        val events = items.filter { it !is TimelineItem.DateSeparator }
        val days = events.map { localDate(it.timestampMs) }
        val next = HashMap<String, Cached>(events.size + days.size)
        val rows = persistentListOf<TimelineRowUi>().builder()
        for (index in events.indices.reversed()) {
            val item = events[index]
            val day = days[index]
            val input: Any = when (item) {
                is TimelineItem.Message -> {
                    val message = item.item
                    val previous = grouped(events.getOrNull(index - 1), days.getOrNull(index - 1), message, day)
                    MessageInput(
                        item = message,
                        showSender = isGroup && !message.isOwn && !previous,
                        groupedWithPrevious = previous,
                        groupedWithNext = grouped(events.getOrNull(index + 1), days.getOrNull(index + 1), message, day),
                        imagePath = imagePaths[message.messageId],
                    )
                }
                is TimelineItem.MembershipChange -> item
                is TimelineItem.DateSeparator -> error("filtered")
            }
            rows += row(item.key, input, next)
            if (day != days.getOrNull(index - 1)) {
                rows += row("date-${day.atStartOfDay(zone).toInstant().toEpochMilli()}", DateInput(day, today), next)
            }
        }
        cache = next
        return rows.build()
    }

    private fun row(key: String, input: Any, next: HashMap<String, Cached>): TimelineRowUi {
        val row = cache[key]?.takeIf { it.input == input }?.row ?: buildRow(key, input)
        next[key] = Cached(input, row)
        return row
    }

    private fun buildRow(key: String, input: Any): TimelineRowUi = when (input) {
        is MessageInput -> messageRow(key, input)
        is DateInput -> DateSeparatorRow(key, dayLabel(input.day, input.today))
        is TimelineItem.MembershipChange -> SystemRow(key, membershipText(input))
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
            senderName = if (input.showSender) message.sender.displayName else null,
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
                FileUi(name, formatSize(it.media.size), it.media.mime, typeLabel(name, it.media.mime))
            },
            replyPreview = message.replyTo?.let { ReplyPreviewUi(it.senderName, it.text) },
            reactions = message.reactions.map { ReactionChipModel(it.emoji, it.count, it.own) }.toImmutableList(),
            edited = message.edited && !deleted,
            deleted = deleted,
            unsupported = content == MessageContent.Unsupported,
            tick = message.sendState?.toTick(),
            time = timeFormatter.format(Instant.ofEpochMilli(message.sentAtMs).atZone(zone)),
            groupedWithPrevious = input.groupedWithPrevious,
            groupedWithNext = input.groupedWithNext,
        )
    }

    private fun dayLabel(day: LocalDate, today: LocalDate): UiText =
        when (day) {
            today -> UiText.Res(R.string.chat_today)
            today.minusDays(1) -> UiText.Res(R.string.chat_yesterday)
            else -> UiText.Raw(dateFormatter.format(day))
        }

    private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private companion object {
        fun grouped(neighbour: TimelineItem?, neighbourDay: LocalDate?, message: MessageItem, day: LocalDate): Boolean {
            val other = (neighbour as? TimelineItem.Message)?.item ?: return false
            return neighbourDay == day && other.sender.userId == message.sender.userId &&
                abs(other.sentAtMs - message.sentAtMs) <= GROUPING_WINDOW_MS
        }

        val TimelineItem.timestampMs: Long
            get() = when (this) {
                is TimelineItem.Message -> item.sentAtMs
                is TimelineItem.MembershipChange -> atMs
                is TimelineItem.DateSeparator -> dayStartMs
            }

        fun membershipText(change: TimelineItem.MembershipChange): UiText = UiText.Res(
            when (change.change) {
                MembershipChangeKind.ADDED -> R.string.chat_member_added
                MembershipChangeKind.REMOVED -> R.string.chat_member_removed
                MembershipChangeKind.LEFT -> R.string.chat_member_left
            },
            persistentListOf(change.userName),
        )

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

        fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = listOf("KB", "MB", "GB")
            var value = bytes / 1024.0
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024
                unit++
            }
            return if (value >= 10) "${value.roundToLong()} ${units[unit]}" else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
        }

        const val DEFAULT_ASPECT_RATIO = 4f / 3f
        const val MIN_ASPECT_RATIO = 0.5f
        const val MAX_ASPECT_RATIO = 2f
    }
}
