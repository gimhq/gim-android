package im.g.apps.feature.onboarding

// The recovery kit: the user ID and recovery code written together, so one copy or file restores the account.

private const val ENDING_SYMBOLS = 4

/** The localized words of the kit text, from string resources. */
internal data class RecoveryKitLabels(
    val title: String,
    val accountId: String,
    val recoveryKey: String,
    val note: String,
)

/** What [parseRecoveryKit] found: the normalized, valid user ID and recovery code symbols, or null for each. */
internal data class ParsedRecoveryKit(val userId: String?, val recoveryCode: String?)

/** The kit text: title, user ID line, recovery code line (grouped), a blank line and the note. */
internal fun formatRecoveryKit(userId: String, recoveryCode: String, labels: RecoveryKitLabels): String =
    listOf(
        labels.title,
        "${labels.accountId}: ${normalizeUserId(userId)}",
        "${labels.recoveryKey}: ${formatRecoveryCode(normalizeRecoveryCode(recoveryCode))}",
        "",
        labels.note,
    ).joinToString("\n", postfix = "\n")

/**
 * Finds the user ID and recovery code in [text] without relying on the labels, so a kit in any language or copied
 * by hand still parses. Tokens are runs of letters and digits; the code may be one token or tokens joined by single
 * `-` or spaces. The user ID token is never part of the code.
 */
internal fun parseRecoveryKit(text: String): ParsedRecoveryKit {
    val tokens = Regex("[\\p{L}\\p{Nd}]+").findAll(text).toList()
    val isUserId = tokens.map { isValidUserId(it.value.lowercase()) }
    val userId = tokens.indices.firstOrNull { isUserId[it] }?.let { tokens[it].value.lowercase() }

    // Chains of tokens joined by a single '-' or space, split at the user ID.
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

/** The valid code made of [chain]'s tokens from [start] holding exactly [RECOVERY_CODE_SYMBOLS] characters, if any. */
private fun codeStartingAt(chain: List<String>, start: Int): String? {
    var length = 0
    for (end in start until chain.size) {
        length += chain[end].length
        if (length > RECOVERY_CODE_SYMBOLS) return null
        if (length == RECOVERY_CODE_SYMBOLS) {
            return normalizeRecoveryCode(chain.subList(start, end + 1).joinToString("")).takeIf(::isValidRecoveryCode)
        }
    }
    return null
}

/** The last four symbols of the recovery code, which the user types back to show they saved the kit. */
internal fun recoveryKeyEnding(recoveryCode: String): String =
    normalizeRecoveryCode(recoveryCode).takeLast(ENDING_SYMBOLS)

/** [typed] is the code's ending: four symbols, ignoring case, `-` and whitespace like [normalizeRecoveryCode]. */
internal fun matchesRecoveryKeyEnding(recoveryCode: String, typed: String): Boolean {
    val symbols = normalizeRecoveryCode(typed)
    return symbols.length == ENDING_SYMBOLS && symbols == recoveryKeyEnding(recoveryCode)
}
