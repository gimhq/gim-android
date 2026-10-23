package im.g.apps.feature.devices

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.designsystem.R as DesignR
import im.g.apps.core.designsystem.theme.GimTheme
import java.util.Locale
import java.util.TimeZone
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DevicesScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val current = DeviceInfo("d-current-0001", CREATED_AT_MS, isCurrent = true, revoked = false)
    private val tablet = DeviceInfo("d-tablet-00002", CREATED_AT_MS, isCurrent = false, revoked = false)
    private val lost = DeviceInfo("d-lost-0000003", 0, isCurrent = false, revoked = true)
    private val listed = DevicesUiState(
        userId = "u-7f3a9c21",
        devices = persistentListOf(current, tablet, lost),
        loading = false,
    )

    private fun show(state: DevicesUiState, actions: DevicesActions = DevicesActions()) =
        rule.setContent { GimTheme { DevicesScreen(state, actions) } }

    @Test
    fun lists_this_device_other_devices_and_revoked_ones() {
        show(listed)

        rule.onNodeWithText("This device").assertExists()
        rule.onNodeWithText("Device d-tablet").assertExists()
        rule.onNodeWithTag(DevicesTestTags.USER_ID).assertTextEquals("u-7f3a9c21")
        assertEquals(2, rule.onAllNodes(hasText("Added ", substring = true)).fetchSemanticsNodes().size)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Device d-lost-0"))
        rule.onNodeWithText("Removed").assertExists()
        // Only the active other device can be revoked.
        assertEquals(1, rule.onAllNodesWithContentDescription("Remove device").fetchSemanticsNodes().size)
    }

    @Test
    fun revoke_button_and_copy_report_their_targets() {
        var revoked: DeviceInfo? = null
        var copied: String? = null
        show(listed, DevicesActions(onRevoke = { revoked = it }, onCopyUserId = { copied = it }))

        rule.onNodeWithContentDescription("Remove device").performClick()
        rule.onNodeWithText("Copy").performClick()

        assertEquals(tablet, revoked)
        assertEquals("u-7f3a9c21", copied)
    }

    @Test
    fun revoke_dialog_confirms_or_cancels() {
        var confirmed = 0
        var dismissed = 0
        show(
            listed.copy(pendingRevoke = tablet),
            DevicesActions(onConfirmRevoke = { confirmed++ }, onDismissRevoke = { dismissed++ }),
        )

        rule.onNodeWithText("Remove this device?").assertExists()
        rule.onNodeWithText("Device d-tablet will", substring = true).assertExists()
        rule.onNode(hasText("Remove") and hasClickAction()).performClick()
        rule.onNodeWithText("Cancel").performClick()

        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun retry_later_error_is_shown_once() {
        var shown = 0
        show(listed.copy(error = DevicesError.RetryLater(30)), DevicesActions(onErrorShown = { shown++ }))

        rule.onNodeWithText("The server is busy. Try again in 30 s.").assertExists()
        rule.mainClock.advanceTimeBy(SNACKBAR_MS)
        rule.waitForIdle()
        assertEquals(1, shown)
    }

    @Test
    fun network_error_without_devices_offers_retry() {
        var refreshed = 0
        show(
            DevicesUiState(userId = "u-1", loading = false, error = DevicesError.Network),
            DevicesActions(onRefresh = { refreshed++ }),
        )

        rule.onNodeWithText("Can't connect to the server. Check your internet and try again.").assertExists()
        rule.onNodeWithText("No devices to show").assertExists()
        rule.onNodeWithText("Try again").performClick()
        rule.onNodeWithContentDescription("Refresh list").performClick()

        assertEquals(2, refreshed)
    }

    @Test
    fun suspension_without_devices_offers_no_retry() {
        show(
            DevicesUiState(
                userId = "u-1",
                loading = false,
                error = DevicesError.Suspended("https://hub-b.example.eu"),
                suspended = true,
            ),
        )

        rule.onNodeWithText(
            ApplicationProvider.getApplicationContext<Context>().getString(DesignR.string.gim_error_user_suspended_on_hub, "https://hub-b.example.eu"),
        ).assertExists()
        rule.onNodeWithText("No devices to show").assertExists()
        rule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun removed_device_can_only_sign_out() {
        var signedOut = 0
        show(listed.copy(removed = true), DevicesActions(onSignedOut = { signedOut++ }))

        rule.onNodeWithText("This device was removed").assertExists()
        rule.onNodeWithText("OK").performClick()

        assertEquals(1, signedOut)
    }

    @Test
    fun created_date_follows_the_locale() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("15.10.2026", formatDeviceDate(CREATED_AT_MS, Locale.GERMANY, utc))
        assertEquals("Oct 15, 2026", formatDeviceDate(CREATED_AT_MS, Locale.US, utc))
    }

    private companion object {
        /** Longer than a short snackbar. */
        const val SNACKBAR_MS = 10_000L
    }
}
