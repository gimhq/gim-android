package im.g.apps.e2e

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import im.g.apps.GimApplication
import im.g.apps.MainActivity
import im.g.apps.core.bridge.GimClient
import im.g.apps.feature.settings.ProfileQrPayloadKey
import im.g.apps.feature.settings.ThemeMode
import im.g.apps.testing.allowPermissionDialog
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.clickWhenSettled
import im.g.apps.testing.completeRecoveryKit
import im.g.apps.testing.dismissSystemDialogs
import im.g.apps.testing.keepScreenOn
import im.g.apps.testing.saveFailureScreenshot
import im.g.apps.testing.showServerField
import im.g.apps.testing.skipIntro
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.contacts.R as ContactsR
import im.g.apps.feature.onboarding.R as OnboardingR
import im.g.apps.feature.settings.R as SettingsR

/**
 * Settings against a live gim-hub (instrumentation argument `gimServerUrl`) with a fresh account: the own avatar
 * opens Settings; Profile shows the Account ID and its QR code, renames and claims and removes a username;
 * Appearance keeps Dark across an activity recreation; Language and Help and about open; the sign-out dialog
 * names the recovery kit. Then New chat -> Scan QR code asks for the camera: on an emulator without a camera the
 * scanner falls back to "can't use the camera" (CameraScannerE2eTest opens a real back camera on a phone).
 */
@RunWith(AndroidJUnit4::class)
class SettingsE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL

    /** Per-wait timeout; CI's software-rendered emulator passes `timeoutScale` > 1. */
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
    private val app get() = instrumentation.targetContext.applicationContext as GimApplication

    @After
    fun tearDown() {
        // The theme survives sign-out: never leave Dark behind for the next test.
        app.container.themeModeStore.set(ThemeMode.SYSTEM)
        // Leave the app signed out for the next test, which starts at onboarding.
        runBlocking { app.container.signOut() }
    }

    @Test
    fun settingsScreensAndNewChatScan() {
        keepScreenOn(instrumentation, compose.activity)
        awaitServerReachable(instrumentation.targetContext, serverUrl)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }
        waitForApp("app shown")

        // Onboarding: skip the intro, create an account, pass the recovery kit check, skip the username.
        val name = "Settings $suffix"
        compose.skipIntro(LOG_TAG, timeoutMs, ::failStep)
        compose.showServerField(LOG_TAG, timeoutMs, ::failStep)
        enterText("server address", TAG_SERVER_URL, serverUrl)
        click("create new account", TAG_CREATE_ACCOUNT, next = hasTestTag(TAG_DISPLAY_NAME))
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        enterText("display name", TAG_DISPLAY_NAME, name)
        click("create account", TAG_SUBMIT)
        compose.completeRecoveryKit(LOG_TAG, timeoutMs, ::failStep)
        waitFor("username step", hasTestTag(TAG_SKIP_HANDLE))
        click("skip username", TAG_SKIP_HANDLE)
        waitForText("chat list", string(ChatListR.string.chatlist_title))

        // The own avatar opens Settings; the header shows the display name.
        openSettings()
        waitFor("settings header shows the name", header(name))

        // Profile: the Account ID is the plain ID, and the QR code encodes exactly it.
        click("settings header", TAG_HEADER, next = hasTestTag(TAG_ACCOUNT_ID))
        waitFor("profile account id", hasTestTag(TAG_ACCOUNT_ID))
        waitFor("profile qr code", hasTestTag(TAG_QR_CODE))
        val accountId = compose.onNodeWithTag(TAG_ACCOUNT_ID, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        val payload = compose.onNodeWithTag(TAG_QR_CODE, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(ProfileQrPayloadKey)
        assertTrue("Account ID '$accountId' is not a plain ID", ACCOUNT_ID.matches(accountId))
        assertEquals("QR payload", accountId, payload)
        assertEquals("Account ID of the signed-in client", appClient().userId(), accountId)

        // Rename: the profile row shows the new name, and so does Settings reopened from the chat list.
        val newName = "Renamed $suffix"
        click("name row", TAG_NAME, next = hasTestTag(TAG_NAME_FIELD))
        waitFor("name dialog", hasTestTag(TAG_NAME_FIELD))
        enterText("name", TAG_NAME_FIELD, newName)
        click("save name", TAG_NAME_SAVE)
        waitFor("profile shows the new name", hasTestTag(TAG_NAME) and hasText(newName))
        goBack("settings after profile", hasTestTag(TAG_HEADER))
        goBack("chat list after settings", hasText(string(ChatListR.string.chatlist_title)))
        openSettings()
        waitFor("settings header shows the new name", header(newName))

        // Username: claim one, then remove it.
        val username = "set_$suffix"
        click("settings header", TAG_HEADER, next = hasTestTag(TAG_USERNAME_ADD))
        click("add username", TAG_USERNAME_ADD, next = hasTestTag(TAG_USERNAME_FIELD))
        waitFor("username dialog", hasTestTag(TAG_USERNAME_FIELD))
        enterText("username", TAG_USERNAME_FIELD, username)
        click("save username", TAG_USERNAME_CLAIM)
        waitForText("profile shows the username", string(SettingsR.string.settings_username, username))
        click("remove username", TAG_USERNAME_REMOVE, next = hasTestTag(TAG_USERNAME_REMOVE_CONFIRM))
        waitFor("remove username dialog", hasTestTag(TAG_USERNAME_REMOVE_CONFIRM))
        click("confirm remove username", TAG_USERNAME_REMOVE_CONFIRM)
        waitFor("add username shown again", hasTestTag(TAG_USERNAME_ADD))
        goBack("settings after profile", hasTestTag(TAG_HEADER))

        // Appearance: Dark stays selected after the activity is recreated; then back to the phone setting.
        click("appearance row", TAG_APPEARANCE, next = hasTestTag(TAG_DARK))
        waitFor("appearance screen", hasTestTag(TAG_DARK))
        click("dark", TAG_DARK)
        // Choosing a theme sets AppCompat's night mode, which recreates the activity when the mode changes.
        waitForApp("app in dark mode", night = true)
        waitFor("dark selected", hasTestTag(TAG_DARK) and isSelected())
        compose.activityRule.scenario.recreate()
        waitForApp("app after recreation", night = true)
        waitFor("dark still selected after recreation", hasTestTag(TAG_DARK) and isSelected())
        click("use phone setting", TAG_SYSTEM)
        waitForApp("app in the phone's mode", night = systemNight())
        waitFor("phone setting selected", hasTestTag(TAG_SYSTEM) and isSelected())
        goBack("settings after appearance", hasTestTag(TAG_HEADER))

        // Language: the phone language and English are offered; the locale is not switched.
        click("language row", TAG_LANGUAGE, next = hasTestTag(TAG_LANGUAGE_PHONE))
        waitFor("phone language option", hasTestTag(TAG_LANGUAGE_PHONE) and hasText(string(SettingsR.string.language_phone)))
        waitFor("english option", hasTestTag(TAG_LANGUAGE_EN) and hasText(ENGLISH))
        goBack("settings after language", hasTestTag(TAG_HEADER))

        // Help and about: the app version, and the licences list.
        val context = instrumentation.targetContext
        val version = checkNotNull(context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        click("help row", TAG_HELP, next = hasTestTag(TAG_LICENCES))
        waitFor("help shows the app version", hasText(string(SettingsR.string.help_app_version)))
        waitForText("help shows version $version", version)
        click("licences", TAG_LICENCES, next = hasText(string(SettingsR.string.library_zxing)))
        waitForText("licences list", string(SettingsR.string.library_zxing))
        goBack("help after licences", hasTestTag(TAG_LICENCES))
        goBack("settings after help", hasTestTag(TAG_HEADER))

        // Sign out asks first and names the recovery kit; cancelled here (RestoreE2eTest signs out for real).
        click("sign out row", TAG_SIGN_OUT, next = hasTestTag(TAG_SIGN_OUT_CONFIRM))
        waitForText("sign-out dialog names the recovery kit", string(SettingsR.string.settings_sign_out_body))
        click("cancel sign out", hasText(string(SettingsR.string.settings_cancel)) and hasClickAction())
        waitFor("sign-out dialog closed") {
            compose.onAllNodes(hasTestTag(TAG_SIGN_OUT_CONFIRM)).fetchSemanticsNodes().isEmpty()
        }
        goBack("chat list after settings", hasText(string(ChatListR.string.chatlist_title)))

        // New chat -> Scan QR code: the camera permission is asked for (once per install) and the scanner opens,
        // or reports that no camera can be used.
        compose.onNodeWithContentDescription(string(ChatListR.string.chatlist_new_message)).performClick()
        waitForText("new message menu", string(ChatListR.string.chatlist_new_chat))
        compose.onNodeWithText(string(ChatListR.string.chatlist_new_chat)).performClick()
        waitFor("new chat", hasTestTag(TAG_SCAN_QR))
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        click("scan qr code", TAG_SCAN_QR)
        if (!granted) {
            assertTrue("no camera permission dialog", allowPermissionDialog(instrumentation, timeoutMs))
        }
        val unavailable = hasText(string(ContactsR.string.contacts_scan_camera_unavailable))
        waitFor("scanner shown or camera unavailable", hasTestTag(TAG_SCANNER) or unavailable)
        assertEquals(
            "camera permission after the scan button",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA),
        )
        // The scanner may fall back to the notice at any moment; close it while it is still up.
        waitFor("scanner closed") {
            if (compose.onAllNodes(hasTestTag(TAG_CLOSE_SCANNER)).fetchSemanticsNodes().isNotEmpty()) {
                runCatching { compose.onNodeWithTag(TAG_CLOSE_SCANNER).performClick() }
            }
            compose.onAllNodes(hasTestTag(TAG_SCANNER)).fetchSemanticsNodes().isEmpty()
        }
        waitFor("new chat after the scanner", hasTestTag(TAG_SCAN_QR))
    }

    /** On the chat list: taps the own avatar and waits for Settings. */
    private fun openSettings() {
        val settings = hasText(string(SettingsR.string.settings_title))
        click("own avatar", TAG_OPEN_SETTINGS, next = settings)
        waitFor("settings shown", hasTestTag(TAG_HEADER))
    }

    /** The Settings header showing [name]. */
    private fun header(name: String) = hasTestTag(TAG_HEADER) and (hasText(name) or hasAnyDescendant(hasText(name)))

    /** Taps the top bar's back arrow and waits for [next]. */
    private fun goBack(step: String, next: SemanticsMatcher) {
        click("back to $step", hasContentDescription(string(SettingsR.string.settings_back)) and hasClickAction())
        waitFor(step, next)
    }

    /** The app's own gim-core client (the test runs in the app process). */
    private fun appClient(): GimClient = checkNotNull(app.container.manager.client.value)

    private fun string(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    /**
     * Waits for [condition]; while the activity is not resumed (being recreated, or behind the system permission
     * dialog) Compose reports no hierarchies, which counts as not yet true.
     */
    private fun waitFor(step: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) {
                try {
                    condition()
                } catch (e: IllegalStateException) {
                    if (e.message?.startsWith(NO_COMPOSE_HIERARCHIES) != true) throw e
                    false
                }
            }
        } catch (e: ComposeTimeoutException) {
            failStep(step, e)
        }
    }

    /**
     * Waits for a resumed MainActivity with a Compose hierarchy (in night mode exactly when [night], if given),
     * then keeps that activity's screen on.
     */
    private fun waitForApp(step: String, night: Boolean? = null) {
        waitFor(step) {
            val activity = resumedActivity()
            activity != null && (night == null || isNight(activity.resources.configuration) == night) &&
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty()
        }
        keepScreenOn(instrumentation, checkNotNull(resumedActivity()))
    }

    private fun resumedActivity(): MainActivity? {
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull()
        }
        return activity
    }

    /** Whether the phone itself (not the app's AppCompat override) is in dark mode. */
    private fun systemNight(): Boolean = isNight(Resources.getSystem().configuration)

    private fun isNight(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun waitFor(step: String, matcher: SemanticsMatcher) =
        waitFor(step) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(step: String, text: String) = waitFor(step, hasText(text))

    /** Replaces the text of the field tagged [tag] and waits until the field holds exactly [value]. */
    private fun enterText(field: String, tag: String, value: String) {
        val node = compose.onNodeWithTag(tag)
        node.performTextReplacement(value)
        waitFor("$field field holds '$value'") {
            node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text == value
        }
    }

    /** Clicks the enabled, displayed button tagged [tag] once the layout has settled; see [clickWhenSettled]. */
    private fun click(button: String, tag: String, next: SemanticsMatcher? = null) =
        compose.clickWhenSettled(LOG_TAG, button, tag, timeoutMs, next)

    /** Clicks the single node matching [target] once the layout has settled; see [clickWhenSettled]. */
    private fun click(button: String, target: SemanticsMatcher) = compose.clickWhenSettled(LOG_TAG, button, target, timeoutMs)

    private fun appHasFocus(): Boolean {
        var focused = false
        instrumentation.runOnMainSync { focused = compose.activity.hasWindowFocus() }
        return focused
    }

    /** Fails with [step] after a screenshot and a semantics dump to logcat under [LOG_TAG]. */
    private fun failStep(step: String, cause: Throwable): Nothing {
        saveFailureScreenshot(instrumentation, "$LOG_TAG-$step")
        runCatching {
            compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString().lines().forEach { Log.d(LOG_TAG, it) }
        }
        throw AssertionError("step '$step' timed out after $timeoutMs ms", cause)
    }

    private companion object {
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"
        const val TIMEOUT_MS = 30_000L
        const val LOG_TAG = "SettingsE2eTest"
        const val NO_COMPOSE_HIERARCHIES = "No compose hierarchies found"
        const val ENGLISH = "English"
        val ACCOUNT_ID = Regex("^u[a-z2-7]{54}[aiqy]$")

        // Tags of internal constants in the feature modules (OnboardingTestTags, ChatListTestTags,
        // SettingsTestTags, ProfileTestTags, AppearanceTestTags, LanguageTestTags, HelpTestTags, NewChatTestTags).
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_SKIP_HANDLE = "onboarding_skip_handle"
        const val TAG_OPEN_SETTINGS = "chatlist_settings"
        const val TAG_HEADER = "settings_header"
        const val TAG_APPEARANCE = "settings_appearance"
        const val TAG_LANGUAGE = "settings_language"
        const val TAG_HELP = "settings_help"
        const val TAG_SIGN_OUT = "settings_sign_out"
        const val TAG_SIGN_OUT_CONFIRM = "settings_sign_out_confirm"
        const val TAG_NAME = "settings_profile_name"
        const val TAG_NAME_FIELD = "settings_profile_name_field"
        const val TAG_NAME_SAVE = "settings_profile_name_save"
        const val TAG_USERNAME_ADD = "settings_profile_username_add"
        const val TAG_USERNAME_REMOVE = "settings_profile_username_remove"
        const val TAG_USERNAME_REMOVE_CONFIRM = "settings_profile_username_remove_confirm"
        const val TAG_USERNAME_FIELD = "settings_profile_username_field"
        const val TAG_USERNAME_CLAIM = "settings_profile_username_claim"
        const val TAG_ACCOUNT_ID = "settings_profile_account_id"
        const val TAG_QR_CODE = "settings_profile_qr"
        const val TAG_SYSTEM = "appearance_system"
        const val TAG_DARK = "appearance_dark"
        const val TAG_LANGUAGE_PHONE = "language_phone"
        const val TAG_LANGUAGE_EN = "language_en"
        const val TAG_LICENCES = "help_licences"
        const val TAG_SCAN_QR = "contacts_scan_qr"
        const val TAG_SCANNER = "contacts_scanner"
        const val TAG_CLOSE_SCANNER = "contacts_close_scanner"
    }
}
