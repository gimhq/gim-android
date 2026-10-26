package im.g.apps.buildlogic

/** Built-in hub, prefilled in onboarding when no override is set. */
const val BUILT_IN_HUB_URL = "https://hub.g.im"

/**
 * Resolves `BuildConfig.DEFAULT_HUB_URL`: Gradle property `gim.defaultHubUrl` if not blank, else env
 * `GIM_DEFAULT_HUB_URL` if not blank, else [BUILT_IN_HUB_URL].
 */
fun resolveDefaultHubUrl(property: String?, env: String?): String {
    val override = property?.takeIf { it.isNotBlank() } ?: env?.takeIf { it.isNotBlank() }
    return override?.trim() ?: BUILT_IN_HUB_URL
}
