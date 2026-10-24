package im.g.apps

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.navigation.GimNavHost
import im.g.apps.navigation.resolveStartDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import im.g.apps.feature.chat.R as ChatR
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.onboarding.R as OnboardingR
import im.g.apps.feature.settings.R as SettingsR

/**
 * Key screens rendered under each shipped locale: text comes from that locale's resources (plus one literal
 * per locale), plurals use its rules, and times use its formatter.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class)
class LocaleRenderingTest(
    private val qualifiers: String,
    private val settingsTitle: String,
    private val skip: String,
    private val threeUnread: String,
) {
    private val compose = createComposeRule()

    /** Applies the qualifiers before the compose rule launches its activity. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    RuntimeEnvironment.setQualifiers(qualifiers)
                    base.evaluate()
                }
            }
        },
    ).around(compose)

    private val scope = CoroutineScope(Job())

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun launch(container: AppContainer) {
        val start = runBlocking { resolveStartDestination(container.manager) }
        compose.setContent { GimTheme { GimNavHost(container, start) } }
    }

    /** A demo container whose fake client's clock is fixed at [now]. */
    private fun demo(now: Long) = testContainer(
        scope = scope,
        factory = FakeGimClientFactory { _, _ -> FakeGimClient.withDemoData(scope, clock = { now }, outboxDelays = null) },
    )

    /** [text] is shown as text or as part of a description somewhere on screen. */
    private fun assertShown(text: String) {
        val matcher: SemanticsMatcher = hasText(text, substring = true) or hasContentDescription(text, substring = true)
        assertTrue("\"$text\" not shown", compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun onboarding_first_screen_uses_the_locale() {
        val demo = FakeGimClientFactory { _, _ -> FakeGimClient.withDemoData(scope, outboxDelays = null) }
        launch(
            testContainer(
                storedUrl = null,
                scope = scope,
                factory = GimClientFactory { config, hooks -> DemoAccountClient(demo.create(config, hooks), registered = false) },
            ),
        )

        compose.waitUntilAtLeastOneExists(hasTestTag("onboarding_intro_skip"), TIMEOUT)
        assertEquals(skip, context.getString(OnboardingR.string.onboarding_intro_skip))
        compose.onNodeWithText(skip).assertExists()
        compose.onNodeWithText(context.getString(OnboardingR.string.onboarding_intro_next)).assertExists()
    }

    @Test
    fun chat_list_and_timeline_use_the_locale_plurals_and_formatters() {
        val now = System.currentTimeMillis()
        launch(demo(now))
        val res = context.resources
        val locale = res.configuration.locales[0]

        compose.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        // The group has three unread messages and its last event was two minutes ago; the direct chat 25.
        val unread = res.getQuantityString(ChatListR.plurals.chatlist_unread_messages, 3, 3)
        assertEquals(threeUnread, unread)
        assertShown(unread)
        assertShown(res.getQuantityString(ChatListR.plurals.chatlist_time_minutes, 2, 2))
        assertShown(res.getQuantityString(ChatListR.plurals.chatlist_time_minutes, 25, 25))

        compose.onNodeWithText(DemoData.lukas.displayName).performClick()
        compose.waitUntilAtLeastOneExists(hasText("See you then!"), TIMEOUT)
        val last = Instant.ofEpochMilli(now - 25 * MINUTE).atZone(ZoneId.systemDefault())
        assertShown(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(last))
        val chip = if (last.toLocalDate() == LocalDate.now()) ChatR.string.chat_today else ChatR.string.chat_yesterday
        assertShown(context.getString(chip))
    }

    @Test
    fun settings_home_uses_the_locale() {
        launch(demo(System.currentTimeMillis()))

        compose.waitUntilAtLeastOneExists(hasText("Aegean Crew"), TIMEOUT)
        compose.onNodeWithTag("chatlist_settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText(settingsTitle), TIMEOUT)
        assertEquals(settingsTitle, context.getString(SettingsR.string.settings_title))
        assertShown(context.getString(SettingsR.string.settings_linked_devices))
    }

    companion object {
        private const val TIMEOUT = 5_000L
        private const val MINUTE = 60_000L

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun locales() = listOf(
            arrayOf("en", "Settings", "Skip", "3 unread messages"),
            arrayOf("zh-rCN", "设置", "跳过", "3 条未读消息"),
            arrayOf("zh-rTW", "設定", "略過", "3 則未讀訊息"),
            arrayOf("es", "Ajustes", "Omitir", "3 mensajes sin leer"),
        )
    }
}
