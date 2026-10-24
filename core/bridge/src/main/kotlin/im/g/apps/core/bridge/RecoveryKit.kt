package im.g.apps.core.bridge

// The recovery kit: the user id and recovery code written together, so one copy or file restores the account.
// Mirrors gim-core's synchronous recovery-kit functions (`formatRecoveryKit`, `parseRecoveryKit`, ...).

/** The localized words of the kit text, from string resources. */
data class RecoveryKitLabels(
    val title: String,
    val accountId: String,
    val recoveryKey: String,
    val note: String,
)

/** What [RecoveryKitCodec.parse] found: the normalized, valid user id and recovery code symbols, or null for each. */
data class ParsedRecoveryKit(val userId: String?, val recoveryCode: String?)

/**
 * gim-core's recovery-kit functions; synchronous and never throwing. The app uses
 * [im.g.apps.core.bridge.uniffi.UniffiRecoveryKitCodec], tests [im.g.apps.core.bridge.fake.FakeRecoveryKitCodec].
 */
interface RecoveryKitCodec {
    /** The kit text: title, `"<accountId>: <user id>"`, `"<recoveryKey>: <code grouped by four>"`, a blank line, note. */
    fun format(userId: String, recoveryCode: String, labels: RecoveryKitLabels): String

    /**
     * The first user id and the first valid recovery code (39 symbols) in pasted [text], ignoring the labels, so a kit
     * in any language, surrounded by other text or typed by hand still parses. The user id is never read as the code.
     */
    fun parse(text: String): ParsedRecoveryKit

    /** The last four symbols of the recovery code, which the user types back to show they saved the kit. */
    fun keyEnding(recoveryCode: String): String

    /** [typed] is the code's ending: four symbols, ignoring case, `-` and spaces. */
    fun matchesKeyEnding(recoveryCode: String, typed: String): Boolean
}
