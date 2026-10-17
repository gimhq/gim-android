package im.g.apps.buildlogic

/** Product default hub, used by release builds when no override is set. */
const val RELEASE_DEFAULT_HUB_URL = "https://hub.g.im"

/**
 * Resolves `BuildConfig.DEFAULT_HUB_URL`: Gradle property `gim.defaultHubUrl` if not blank, else env
 * `GIM_DEFAULT_HUB_URL` if not blank, else [RELEASE_DEFAULT_HUB_URL] for release builds and empty for debug.
 */
fun resolveDefaultHubUrl(property: String?, env: String?, release: Boolean): String {
    val override = property?.takeIf { it.isNotBlank() } ?: env?.takeIf { it.isNotBlank() }
    return override?.trim() ?: if (release) RELEASE_DEFAULT_HUB_URL else ""
}
