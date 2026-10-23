package im.g.apps.feature.onboarding

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.core.bridge.fake.FakePlatformHooks
import im.g.apps.core.designsystem.explainer.accountIntroPoints
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The intro, Welcome, kit, kit check, restore and username steps of [OnboardingScreen]. */
@RunWith(AndroidJUnit4::class)
class OnboardingStepsTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fakeScope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = fakeScope.cancel()

    private fun show(state: OnboardingUiState, callbacks: OnboardingCallbacks = testCallbacks()) {
        rule.setContent { GimTheme { OnboardingScreen(state, callbacks) } }
    }

    private fun introTitle(page: Int) = context.getString(accountIntroPoints[page].title)

    @Test
    fun intro_next_walks_three_pages_and_get_started_finishes() {
        var finished = 0
        show(OnboardingUiState(), testCallbacks(onFinishIntro = { finished++ }))

        rule.onNodeWithText(introTitle(0)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Page 1 of 3").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.INTRO_START).assertDoesNotExist()
        rule.onNodeWithTag(OnboardingTestTags.INTRO_NEXT).performClick()
        rule.onNodeWithText(introTitle(1)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Page 2 of 3").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.INTRO_SKIP).assertIsDisplayed()
        rule.onNodeWithTag(OnboardingTestTags.INTRO_NEXT).performClick()
        rule.onNodeWithText(introTitle(2)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Page 3 of 3").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.INTRO_NEXT).assertDoesNotExist()
        rule.onNodeWithTag(OnboardingTestTags.INTRO_SKIP).assertIsDisplayed()
        assertEquals(0, finished)

        rule.onNodeWithTag(OnboardingTestTags.INTRO_START).performClick()
        assertEquals(1, finished)
    }

    @Test
    fun intro_skip_finishes_from_the_first_page() {
        var finished = 0
        show(OnboardingUiState(), testCallbacks(onFinishIntro = { finished++ }))
        rule.onNodeWithTag(OnboardingTestTags.INTRO_SKIP).performClick()
        assertEquals(1, finished)
    }

    @Test
    fun welcome_with_a_default_server_hides_the_field_until_asked() {
        var asked = 0
        show(
            OnboardingUiState.initial("https://gim.example.eu").copy(step = OnboardingStep.WELCOME),
            testCallbacks(onUseAnotherServer = { asked++ }),
        )
        rule.onNodeWithText("Welcome to gim").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).assertDoesNotExist()
        rule.onNodeWithTag(OnboardingTestTags.CREATE_ACCOUNT).assertIsEnabled()
        rule.onNodeWithTag(OnboardingTestTags.USE_ANOTHER_SERVER).performScrollTo().performClick()
        assertEquals(1, asked)
    }

    @Test
    fun welcome_without_a_default_server_shows_the_field() {
        show(OnboardingUiState(step = OnboardingStep.WELCOME))
        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).assertExists()
        rule.onNodeWithText("Server address").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.USE_ANOTHER_SERVER).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34])
    fun kit_shows_the_id_and_key_and_offers_copy_password_manager_and_file() {
        val copied = mutableListOf<String>()
        val saved = mutableListOf<Pair<String, String>>()
        val shared = mutableListOf<String>()
        var continued = 0
        show(
            KIT_STATE,
            testCallbacks(
                onCopyRecoveryKit = { copied += it },
                onSaveToPasswordManager = { id, key ->
                    saved += id to key
                    PasswordSaveResult.SAVED
                },
                onShareRecoveryKit = { shared += it },
                onContinueFromKit = { continued++ },
            ),
        )
        rule.onNodeWithText("Your recovery kit").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.KIT_ACCOUNT_ID).assertTextContains(USER_ID)
        rule.onNodeWithContentDescription("Account ID $USER_ID").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.RECOVERY_CODE).assertTextContains(RECOVERY_CODE)
        rule.onNodeWithContentDescription("Recovery key $RECOVERY_CODE").assertExists()

        rule.onNodeWithTag(OnboardingTestTags.KIT_COPY).performScrollTo().performClick()
        rule.onNodeWithText("Copied").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.KIT_SAVE_PASSWORD).performScrollTo().performClick()
        rule.onNodeWithTag(OnboardingTestTags.KIT_SHARE).performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(1, copied.size)
        assertEquals(ParsedRecoveryKit(USER_ID, RECOVERY_SYMBOLS), parseRecoveryKit(copied.single()))
        assertEquals(listOf(USER_ID to RECOVERY_CODE), saved)
        assertEquals(1, shared.size)
        assertEquals(ParsedRecoveryKit(USER_ID, RECOVERY_SYMBOLS), parseRecoveryKit(shared.single()))
        rule.onNodeWithText(PASSWORD_FAILED).assertDoesNotExist()

        rule.onNodeWithTag(OnboardingTestTags.CODE_CONTINUE).performScrollTo().performClick()
        assertEquals(1, continued)
    }

    @Test
    @Config(sdk = [34])
    fun kit_password_manager_failure_shows_a_calm_message_and_cancel_shows_nothing() {
        var result = PasswordSaveResult.CANCELLED
        show(KIT_STATE, testCallbacks(onSaveToPasswordManager = { _, _ -> result }))
        rule.onNodeWithTag(OnboardingTestTags.KIT_SAVE_PASSWORD).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(PASSWORD_FAILED).assertDoesNotExist()

        result = PasswordSaveResult.FAILED
        rule.onNodeWithTag(OnboardingTestTags.KIT_SAVE_PASSWORD).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(PASSWORD_FAILED).assertExists()
    }

    @Test
    @Config(sdk = [33])
    fun kit_hides_the_password_manager_below_api_34() {
        show(KIT_STATE)
        rule.onNodeWithTag(OnboardingTestTags.KIT_SAVE_PASSWORD).assertDoesNotExist()
        rule.onNodeWithTag(OnboardingTestTags.KIT_COPY).assertExists()
        rule.onNodeWithTag(OnboardingTestTags.KIT_SHARE).assertExists()
    }

    @Test
    fun kit_check_enables_confirm_after_four_characters_and_shows_a_mismatch() {
        var state by mutableStateOf(
            OnboardingUiState(step = OnboardingStep.RECOVERY_CHECK, recoveryCode = RECOVERY_CODE, accountId = USER_ID),
        )
        val callbacks = testCallbacks(onKitCheckChange = { state = state.copy(kitCheck = it, kitCheckError = null) })
        rule.setContent { GimTheme { OnboardingScreen(state, callbacks) } }

        rule.onNodeWithText("Type the last 4 characters of your recovery key", substring = true).assertExists()
        rule.onNodeWithTag(OnboardingTestTags.KIT_CHECK_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.KIT_CHECK).performTextInput("V7")
        rule.onNodeWithTag(OnboardingTestTags.KIT_CHECK_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.KIT_CHECK).performTextInput("AB")
        rule.onNodeWithTag(OnboardingTestTags.KIT_CHECK_CONFIRM).assertIsEnabled()

        state = state.copy(kitCheckError = FieldError.KitCheckMismatch)
        rule.onNodeWithText("That doesn’t match. Look at your recovery key and try again.").assertExists()
    }

    @Test
    fun restore_paste_fills_both_fields_and_garbage_is_not_recognized() {
        val manager = GimClientManager(
            FakeGimClientFactory { _, _ ->
                FakeGimClient(fakeScope, outboxDelays = null).apply {
                    addRecoverableUser(UserProfile(USER_ID, "", "Sofia Costa"), RECOVERY_CODE)
                }
            },
            FakePlatformHooks(),
            InMemoryServerUrlStore(),
        )
        val vm = OnboardingViewModel(manager, "https://gim.example.eu")
        rule.setContent { GimTheme { OnboardingRoute(viewModel = vm, onFinished = {}, onShareRecoveryKit = {}) } }
        rule.onNodeWithTag(OnboardingTestTags.INTRO_SKIP).performClick()
        rule.onNodeWithTag(OnboardingTestTags.RESTORE).performScrollTo().performClick()

        rule.onNodeWithText("Paste your recovery kit").assertExists()
        rule.onNodeWithText("Or type them yourself").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_KIT).performTextInput("hello there")
        rule.onNodeWithText(
            "We couldn’t find an Account ID or recovery key here. Paste the whole kit, or type them below.",
        ).assertExists()
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_SUBMIT).assertIsNotEnabled()

        val kit = formatRecoveryKit(USER_ID, RECOVERY_CODE, RecoveryKitLabels("gim", "Account ID", "Recovery key", ""))
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_KIT).performTextReplacement(kit)
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_USER_ID).assertTextContains(USER_ID)
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_CODE).assertTextContains(RECOVERY_CODE)
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_SUBMIT).performScrollTo().assertIsEnabled()
    }

    @Test
    fun restore_explains_the_kit_errors() {
        show(OnboardingUiState(step = OnboardingStep.RESTORE, restoreKitError = FieldError.KitIncomplete))
        rule.onNodeWithText("Part of the kit is missing. Fill in the empty field below.").assertExists()
        rule.onNodeWithText("under Devices", substring = true).assertDoesNotExist()
    }

    @Test
    fun username_explains_what_it_is_for() {
        show(OnboardingUiState(step = OnboardingStep.HANDLE))
        rule.onNodeWithText("Choose a username").assertExists()
        rule.onNodeWithText("Friends can find you with it.", substring = true).assertExists()
        rule.onNodeWithText("Username").assertExists()
    }

    private companion object {
        const val USER_ID = "u" + "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuv" + "q"
        const val RECOVERY_CODE = "7BQ2-K9MX-4TVA-0HZR-PC63-W8NE-1SJD-5GFY-QK2M-V7R"
        val RECOVERY_SYMBOLS = RECOVERY_CODE.replace("-", "")
        const val PASSWORD_FAILED = "Couldn’t save it to a password manager. Copy it or save it as a file instead."
        val KIT_STATE = OnboardingUiState(
            step = OnboardingStep.RECOVERY_KIT,
            recoveryCode = RECOVERY_CODE,
            accountId = USER_ID,
        )
    }
}
