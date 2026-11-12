package im.g.apps.testing

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import im.g.apps.feature.onboarding.R as OnboardingR

// Onboarding steps shared by the device tests: the intro pager that opens every fresh onboarding and the recovery
// kit with its check. Timeouts fail through the calling test's `fail`, which dumps the screen.

/** Tags of internal constants in OnboardingTestTags. */
object OnboardingTags {
    const val INTRO_SKIP = "onboarding_intro_skip"
    const val INTRO_NEXT = "onboarding_intro_next"
    const val INTRO_START = "onboarding_intro_start"
    const val CREATE_ACCOUNT = "onboarding_create_account"
    const val KIT_ACCOUNT_ID = "onboarding_kit_account_id"
    const val RECOVERY_CODE = "onboarding_recovery_code"
    const val CODE_CONTINUE = "onboarding_code_continue"
    const val KIT_CHECK = "onboarding_kit_check"
    const val KIT_CHECK_CONFIRM = "onboarding_kit_check_confirm"
    const val SERVER_URL = "onboarding_server_url"
    const val USE_ANOTHER_SERVER = "onboarding_use_another_server"
}

/** What the recovery kit showed: the Account ID and the recovery key as displayed (blocks of four joined by '-'). */
data class RecoveryKit(val accountId: String, val recoveryKey: String)

/** Onboarding's first screen: the intro pager, or Welcome once past it. */
fun AndroidComposeTestRule<*, out ComponentActivity>.onboardingShown(): SemanticsMatcher =
    hasTestTag(OnboardingTags.INTRO_SKIP) or hasText(activity.getString(OnboardingR.string.onboarding_welcome_title))

/** Waits for the intro pager, taps Skip and waits for Welcome. */
fun AndroidComposeTestRule<*, out ComponentActivity>.skipIntro(
    logTag: String,
    timeoutMs: Long,
    fail: (String, Throwable) -> Nothing,
) {
    awaitNode("intro shown", hasTestTag(OnboardingTags.INTRO_SKIP), timeoutMs, fail)
    val welcome = hasTestTag(OnboardingTags.CREATE_ACCOUNT)
    clickWhenSettled(logTag, "skip intro", OnboardingTags.INTRO_SKIP, timeoutMs, next = welcome)
    awaitNode("welcome after the intro", welcome, timeoutMs, fail)
}

/**
 * On Welcome: taps "Use another server" when the build's prefilled hub hides the server field, then waits for the
 * field, so the test can type its own hub.
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.showServerField(
    logTag: String,
    timeoutMs: Long,
    fail: (String, Throwable) -> Nothing,
) {
    val field = hasTestTag(OnboardingTags.SERVER_URL)
    if (onAllNodes(field).fetchSemanticsNodes().isEmpty()) {
        clickWhenSettled(logTag, "use another server", OnboardingTags.USE_ANOTHER_SERVER, timeoutMs, next = field)
    }
    awaitNode("server field shown", field, timeoutMs, fail)
}

/**
 * On the recovery kit: reads the Account ID and recovery key, taps "I saved it", types the key's last four symbols
 * into the check and confirms; onboarding then finishes, so callers wait for the chat list. Never logs the key.
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.completeRecoveryKit(
    logTag: String,
    timeoutMs: Long,
    fail: (String, Throwable) -> Nothing,
): RecoveryKit {
    awaitNode("recovery kit shown", hasTestTag(OnboardingTags.RECOVERY_CODE), timeoutMs, fail)
    val kit = RecoveryKit(textOf(OnboardingTags.KIT_ACCOUNT_ID), textOf(OnboardingTags.RECOVERY_CODE))
    val check = hasTestTag(OnboardingTags.KIT_CHECK)
    clickWhenSettled(logTag, "recovery kit saved", OnboardingTags.CODE_CONTINUE, timeoutMs, next = check)
    awaitNode("recovery check shown", check, timeoutMs, fail)
    onNodeWithTag(OnboardingTags.KIT_CHECK).performTextReplacement(kit.recoveryKey.replace("-", "").takeLast(4))
    val confirm = hasTestTag(OnboardingTags.KIT_CHECK_CONFIRM) and isEnabled()
    awaitNode("recovery check confirm enabled", confirm, timeoutMs, fail)
    clickWhenSettled(logTag, "confirm recovery check", OnboardingTags.KIT_CHECK_CONFIRM, timeoutMs)
    return kit
}

private fun AndroidComposeTestRule<*, out ComponentActivity>.textOf(tag: String): String =
    onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("") { it.text }.orEmpty()

internal fun AndroidComposeTestRule<*, out ComponentActivity>.awaitNode(
    step: String,
    matcher: SemanticsMatcher,
    timeoutMs: Long,
    fail: (String, Throwable) -> Nothing,
) {
    try {
        waitUntil(timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: ComposeTimeoutException) {
        fail(step, e)
    }
}
