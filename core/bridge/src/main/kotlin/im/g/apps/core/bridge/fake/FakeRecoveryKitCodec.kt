package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.ParsedRecoveryKit
import im.g.apps.core.bridge.RecoveryKitCodec
import im.g.apps.core.bridge.RecoveryKitLabels

/**
 * Pure-Kotlin [RecoveryKitCodec] behaving like gim-core's recovery-kit functions (formats from gim-core
 * docs/identity.md, "Identifiers" and "Recovery backup"); the device test UniffiRecoveryKitCodecTest runs the same
 * cases against the native ones.
 */
object FakeRecoveryKitCodec : RecoveryKitCodec {
    /** `"u"` + base32 (RFC 4648, lowercase, unpadded) of 34 bytes: 55 symbols, the last carrying 3 zero bits. */
    private val USER_ID_PATTERN = Regex("^u[a-z2-7]{54}[aiqy]$")
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 24 bytes in Crockford base32. */
    private const val CODE_SYMBOLS = 39
    private const val CODE_GROUP = 4
    private const val ENDING_SYMBOLS = 4

    override fun format(userId: String, recoveryCode: String, labels: RecoveryKitLabels): String =
        listOf(
            labels.title,
            "${labels.accountId}: ${userId.trim().lowercase()}",
            "${labels.recoveryKey}: ${normalize(recoveryCode).chunked(CODE_GROUP).joinToString("-")}",
            "",
            labels.note,
        ).joinToString("\n", postfix = "\n")

    /**
     * Tokens are runs of letters and digits; the code may be one token or tokens joined by single `-` or spaces. The
     * user id token is never part of the code.
     */
    override fun parse(text: String): ParsedRecoveryKit {
        val tokens = Regex("[\\p{L}\\p{Nd}]+").findAll(text).toList()
        val isUserId = tokens.map { USER_ID_PATTERN.matches(it.value.lowercase()) }
        val userId = tokens.indices.firstOrNull { isUserId[it] }?.let { tokens[it].value.lowercase() }

        // Chains of tokens joined by a single '-' or space, split at the user id.
        val chains = mutableListOf<MutableList<String>>()
        tokens.forEachIndexed { i, token ->
            if (isUserId[i]) {
                chains += mutableListOf<String>()
                return@forEachIndexed
            }
            val separator = if (i == 0) null else text.substring(tokens[i - 1].range.last + 1, token.range.first)
            if (chains.isEmpty() || isUserId[i - 1] || (separator != "-" && separator != " ")) {
                chains += mutableListOf<String>()
            }
            chains.last() += token.value
        }
        val recoveryCode = chains.firstNotNullOfOrNull { chain ->
            chain.indices.firstNotNullOfOrNull { start -> codeStartingAt(chain, start) }
        }
        return ParsedRecoveryKit(userId, recoveryCode)
    }

    override fun keyEnding(recoveryCode: String): String = normalize(recoveryCode).takeLast(ENDING_SYMBOLS)

    override fun matchesKeyEnding(recoveryCode: String, typed: String): Boolean {
        val symbols = normalize(typed)
        return symbols.length == ENDING_SYMBOLS && symbols == keyEnding(recoveryCode)
    }

    /** The valid code made of [chain]'s tokens from [start] holding exactly [CODE_SYMBOLS] characters, if any. */
    private fun codeStartingAt(chain: List<String>, start: Int): String? {
        var length = 0
        for (end in start until chain.size) {
            length += chain[end].length
            if (length > CODE_SYMBOLS) return null
            if (length == CODE_SYMBOLS) {
                return normalize(chain.subList(start, end + 1).joinToString("")).takeIf(::isValidCode)
            }
        }
        return null
    }

    /** As gim-core parses the code: `-` and whitespace dropped, uppercased, `I`/`L` -> `1` and `O` -> `0`. */
    private fun normalize(value: String): String =
        value.filterNot { it == '-' || it.isWhitespace() }.uppercase()
            .map {
                when (it) {
                    'I', 'L' -> '1'
                    'O' -> '0'
                    else -> it
                }
            }
            .joinToString("")

    /** 39 symbols, the last one's 3 padding bits zero. */
    private fun isValidCode(symbols: String): Boolean =
        symbols.length == CODE_SYMBOLS && symbols.all { it in CROCKFORD } && CROCKFORD.indexOf(symbols.last()) % 8 == 0
}
