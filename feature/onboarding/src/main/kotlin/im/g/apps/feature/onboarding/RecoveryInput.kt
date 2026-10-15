package im.g.apps.feature.onboarding

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

// Formats from gim-core docs/identity.md ("Identifiers", "Recovery backup").

/** `"u"` + base32 (RFC 4648, lowercase, unpadded) of 34 bytes: 55 symbols, the last carrying 3 zero bits. */
private val USER_ID_PATTERN = Regex("^u[a-z2-7]{54}[aiqy]$")

private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** 24 bytes in Crockford base32. */
internal const val RECOVERY_CODE_SYMBOLS = 39

private const val RECOVERY_CODE_GROUP = 4

/** What the user pasted or typed, trimmed and lowercased (ids are canonical lowercase). */
internal fun normalizeUserId(value: String): String = value.trim().lowercase()

internal fun isValidUserId(value: String): Boolean = USER_ID_PATTERN.matches(value)

/**
 * The recovery code's symbols as gim-core parses them: `-` and whitespace dropped, uppercased, `I`/`L` -> `1` and
 * `O` -> `0`. Other characters are kept so they can be flagged.
 */
internal fun normalizeRecoveryCode(value: String): String =
    value.filterNot { it == '-' || it.isWhitespace() }.uppercase()
        .map {
            when (it) {
                'I', 'L' -> '1'
                'O' -> '0'
                else -> it
            }
        }
        .joinToString("")

/** [symbols] (from [normalizeRecoveryCode]) is a whole code: 39 symbols, the last one's 3 padding bits zero. */
internal fun isValidRecoveryCode(symbols: String): Boolean =
    symbols.length == RECOVERY_CODE_SYMBOLS &&
        symbols.all { it in CROCKFORD } &&
        CROCKFORD.indexOf(symbols.last()) % 8 == 0

/** [symbols] cannot become a valid code by typing more: a foreign character, too long, or bad padding. */
internal fun isBrokenRecoveryCode(symbols: String): Boolean =
    symbols.any { it !in CROCKFORD } || symbols.length > RECOVERY_CODE_SYMBOLS ||
        (symbols.length == RECOVERY_CODE_SYMBOLS && !isValidRecoveryCode(symbols))

/** Groups [symbols] in blocks of four separated by `-`, as gim-core displays the code. */
internal fun formatRecoveryCode(symbols: String): String = symbols.chunked(RECOVERY_CODE_GROUP).joinToString("-")

/**
 * Shows the normalized symbols grouped like [formatRecoveryCode] while the field holds only the symbols, so the
 * cursor stays put as separators appear.
 */
internal object RecoveryCodeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val length = text.length
        return TransformedText(
            AnnotatedString(formatRecoveryCode(text.text)),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    if (offset == 0) 0 else offset + (offset - 1) / RECOVERY_CODE_GROUP

                override fun transformedToOriginal(offset: Int): Int =
                    (offset - offset / (RECOVERY_CODE_GROUP + 1)).coerceIn(0, length)
            },
        )
    }
}
