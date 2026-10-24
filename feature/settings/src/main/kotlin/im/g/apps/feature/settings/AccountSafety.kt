package im.g.apps.feature.settings

/**
 * Account safety rows that are built but still hidden, one switch each; turn one on when its flow is wired (see
 * README, "Extension points").
 */
internal object AccountSafety {
    /** "Create a new recovery key" in the account section. */
    const val NEW_RECOVERY_KEY = false

    /** "Delete account" in the account section. */
    const val DELETE_ACCOUNT = false
}
