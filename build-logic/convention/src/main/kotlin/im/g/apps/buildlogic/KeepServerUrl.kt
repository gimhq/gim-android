package im.g.apps.buildlogic

/** Built-in keep server (privacy settings backup, user reports), on unless overridden. */
const val BUILT_IN_KEEP_SERVER_URL = "https://keep.g.im"

/**
 * Resolves `BuildConfig.KEEP_SERVER_URL`: Gradle property `gim.keepServerUrl` if set, else env `GIM_KEEP_SERVER_URL`
 * if set, else [BUILT_IN_KEEP_SERVER_URL]. A set value that is blank or `off` turns the keep server off (empty).
 */
fun resolveKeepServerUrl(property: String?, env: String?): String {
    val override = (property ?: env)?.trim() ?: return BUILT_IN_KEEP_SERVER_URL
    return if (override.equals("off", ignoreCase = true)) "" else override
}
