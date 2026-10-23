package im.g.apps.testing

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import im.g.apps.feature.settings.R as SettingsR

// Settings steps shared by the device tests: the chat list's own avatar opens Settings, whose rows lead on.

/** Tags of internal constants in ChatListTestTags and SettingsTestTags. */
object SettingsTags {
    const val OPEN = "chatlist_settings"
    const val SIGN_OUT = "settings_sign_out"
    const val SIGN_OUT_CONFIRM = "settings_sign_out_confirm"
}

/** On the chat list: taps the own avatar, waits for Settings and taps the row titled [row]. */
fun AndroidComposeTestRule<*, out ComponentActivity>.openSettingsRow(
    logTag: String,
    row: String,
    timeoutMs: Long,
    fail: (String, Throwable) -> Nothing,
) {
    val settings = hasText(activity.getString(SettingsR.string.settings_title))
    clickWhenSettled(logTag, "own avatar", SettingsTags.OPEN, timeoutMs, next = settings)
    awaitNode("settings shown", settings, timeoutMs, fail)
    clickWhenSettled(logTag, "settings row $row", hasText(row) and hasClickAction(), timeoutMs)
}

/** On the chat list: Settings -> Sign out, then confirms the dialog. Does not wait for onboarding. */
fun AndroidComposeTestRule<*, out ComponentActivity>.signOutFromSettings(
    logTag: String,
    timeoutMs: Long,
    fail: (String, Throwable) -> Nothing,
) {
    openSettingsRow(logTag, activity.getString(SettingsR.string.settings_sign_out), timeoutMs, fail)
    val confirm = hasTestTag(SettingsTags.SIGN_OUT_CONFIRM)
    awaitNode("sign-out dialog", confirm, timeoutMs, fail)
    clickWhenSettled(logTag, "confirm sign out", confirm, timeoutMs)
}
