package im.g.apps.feature.chatlist

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Chat list timestamps relative to [clock] in its zone: under a minute -> now, under an hour today -> minutes,
 * today -> localized clock time, the day before -> yesterday, the previous six days -> short weekday, this
 * year -> localized day and month, older -> localized short date. Future times (clock skew) show the clock time.
 */
internal class ChatTimeFormatter(private val clock: Clock, private val locale: Locale) {
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    private val dayMonthFormat = DateTimeFormatter.ofPattern(dayMonthPattern(locale), locale)
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)

    fun format(epochMs: Long): ChatTimeLabel {
        val now = clock.instant()
        val time = Instant.ofEpochMilli(epochMs).atZone(clock.zone)
        val date = time.toLocalDate()
        val today = LocalDate.now(clock)
        val age = Duration.between(time.toInstant(), now)
        return when {
            age.isNegative -> ChatTimeLabel.Clock(timeFormat.format(time))
            age < Duration.ofMinutes(1) -> ChatTimeLabel.JustNow
            date == today && age < Duration.ofHours(1) -> ChatTimeLabel.Minutes(age.toMinutes().toInt())
            date == today -> ChatTimeLabel.Clock(timeFormat.format(time))
            date == today.minusDays(1) -> ChatTimeLabel.Yesterday
            date.isAfter(today.minusDays(WEEK_DAYS)) ->
                ChatTimeLabel.Weekday(date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale))
            date.year == today.year -> ChatTimeLabel.Date(dayMonthFormat.format(date))
            else -> ChatTimeLabel.Date(dateFormat.format(date))
        }
    }

    private companion object {
        const val WEEK_DAYS = 7L

        /** The locale's medium date pattern with the year and its separator removed ("MMM d, y" -> "MMM d"). */
        fun dayMonthPattern(locale: Locale): String =
            DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.MEDIUM, null, IsoChronology.INSTANCE, locale)
                .replace(Regex("[^\\p{L}']*y+[^\\p{L}']*"), " ")
                .trim()
    }
}
