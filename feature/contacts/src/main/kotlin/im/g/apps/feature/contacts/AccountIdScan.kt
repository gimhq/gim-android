package im.g.apps.feature.contacts

/** Same rule as onboarding's user ID check: `u`, 54 base32 symbols, and a final `a`, `i`, `q` or `y`. */
private val ACCOUNT_ID_PATTERN = Regex("^u[a-z2-7]{54}[aiqy]$")

private val TOKEN = Regex("[\\p{L}\\p{Nd}]+")

/**
 * The first Account ID among the letter/digit runs of a scanned QR code's [text], lowercased, or null. A contact
 * QR code holds the plain ID, but an ID inside a URL or a sentence is accepted too.
 */
internal fun findAccountId(text: String): String? =
    TOKEN.findAll(text).map { it.value.lowercase() }.firstOrNull { ACCOUNT_ID_PATTERN.matches(it) }
