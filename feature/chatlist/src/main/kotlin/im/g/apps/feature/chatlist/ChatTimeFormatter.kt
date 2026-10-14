package im.g.apps.feature.chatlist

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Chat list timestamps relative to [clock]'s date in its zone: today -> "HH:mm", the previous six days ->
 * short weekday name, older -> "dd.MM.yy".
 */
internal class ChatTimeFormatter(private val clock: Clock, private val locale: Locale) {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yy", locale)

    fun format(epochMs: Long): String {
        val zone = clock.zone
        val time = Instant.ofEpochMilli(epochMs).atZone(zone)
        val date = time.toLocalDate()
        val today = LocalDate.now(clock)
        return when {
            !date.isBefore(today) -> timeFormat.format(time)
            date.isAfter(today.minusDays(WEEK_DAYS)) -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            else -> dateFormat.format(date)
        }
    }

    private companion object {
        const val WEEK_DAYS = 7L
    }
}
