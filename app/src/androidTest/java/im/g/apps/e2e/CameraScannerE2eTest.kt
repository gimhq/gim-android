package im.g.apps.e2e

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
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
import im.g.apps.GimApplication
import im.g.apps.MainActivity
import im.g.apps.testing.awaitServerReachable
import im.g.apps.testing.clickWhenSettled
import im.g.apps.testing.completeRecoveryKit
import im.g.apps.testing.dismissSystemDialogs
import im.g.apps.testing.keepScreenOn
import im.g.apps.testing.saveFailureScreenshot
import im.g.apps.testing.skipIntro
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import im.g.apps.feature.chatlist.R as ChatListR
import im.g.apps.feature.linking.R as LinkingR
import im.g.apps.feature.onboarding.R as OnboardingR

/**
 * The link-device scanner with the real CameraX pipeline on a phone: the CAMERA permission is granted (by
 * scripts/run-real-device-tests.sh via `pm grant`), the scanner opens the back camera, configures its streams
 * (preview + YUV analysis) and keeps running without falling back to "no camera", and leaving the screen releases
 * the camera. Decoding a physical QR code needs a person holding one up and is not automated; LinkDeviceE2eTest
 * covers the QR payload end to end.
 *
 * Skipped unless the instrumentation argument `realCamera` is `true`: emulators run without a camera.
 */
@RunWith(AndroidJUnit4::class)
class CameraScannerE2eTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val serverUrl = args.getString("gimServerUrl") ?: DEFAULT_SERVER_URL
    private val timeoutMs = TIMEOUT_MS * (args.getString("timeoutScale")?.toLongOrNull()?.coerceAtLeast(1) ?: 1)
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
    private val cameraManager by lazy { instrumentation.targetContext.getSystemService(CameraManager::class.java) }

    /** Back cameras currently opened by some client, from [CameraManager.AvailabilityCallback]. */
    private val openBackCameras = ConcurrentHashMap.newKeySet<String>()
    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            if (cameraId in backCameras) openBackCameras += cameraId
        }

        override fun onCameraAvailable(cameraId: String) {
            openBackCameras -= cameraId
        }
    }
    private val backCameras by lazy {
        cameraManager.cameraIdList.filter {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }.toSet()
    }
    private var registered = false

    @Before
    fun setUp() {
        assumeTrue("needs a real camera (instrumentation argument realCamera=true)", args.getString("realCamera") == "true")
    }

    @After
    fun tearDown() {
        if (registered) cameraManager.unregisterAvailabilityCallback(availability)
        if (args.getString("realCamera") == "true") {
            runBlocking { (instrumentation.targetContext.applicationContext as GimApplication).container.signOut() }
        }
    }

    @Test
    fun scannerBindsBackCameraAndReleasesIt() {
        keepScreenOn(instrumentation, compose.activity)
        val context = instrumentation.targetContext
        assertEquals(
            "CAMERA must be granted before the test (pm grant ${context.packageName} android.permission.CAMERA)",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA),
        )
        assertTrue("device reports no back camera", backCameras.isNotEmpty())
        awaitServerReachable(context, serverUrl)
        if (!dismissSystemDialogs(instrumentation, timeoutMs) { appHasFocus() }) {
            Log.w(LOG_TAG, "app window still has no focus; continuing")
        }

        // Onboarding: skip the intro, create identity, pass the recovery kit check, skip the handle -> chat list.
        compose.skipIntro(LOG_TAG, timeoutMs, ::failStep)
        waitFor("onboarding shown", hasText(string(OnboardingR.string.onboarding_welcome_title)))
        enterText("hub address", TAG_SERVER_URL, serverUrl)
        click("create new account", TAG_CREATE_ACCOUNT, next = hasTestTag(TAG_DISPLAY_NAME))
        waitFor("display name step", hasTestTag(TAG_DISPLAY_NAME))
        enterText("display name", TAG_DISPLAY_NAME, "Camera $suffix")
        click("create account", TAG_SUBMIT)
        compose.completeRecoveryKit(LOG_TAG, timeoutMs, ::failStep)
        waitFor("handle step", hasTestTag(TAG_SKIP_HANDLE))
        click("skip handle", TAG_SKIP_HANDLE)
        waitFor("chat list", hasText(string(ChatListR.string.chatlist_title)))

        cameraManager.registerAvailabilityCallback(availability, Handler(Looper.getMainLooper()))
        registered = true
        waitFor("back cameras idle before the scanner opens") { openBackCameras.isEmpty() }

        // Chat list overflow -> Link a new device: the scanner is shown and opens a back camera.
        compose.onNodeWithContentDescription(string(ChatListR.string.chatlist_more_options)).performClick()
        waitFor("overflow menu", hasText(string(ChatListR.string.chatlist_link_device)))
        compose.onNodeWithText(string(ChatListR.string.chatlist_link_device)).performClick()
        waitFor("scanner shown", hasTestTag(TAG_SCANNER))
        waitFor("back camera opened by the scanner") { openBackCameras.isNotEmpty() }
        Log.i(LOG_TAG, "back camera(s) open: $openBackCameras")

        // CameraX configured the session: the camera service lists this app as the client with an output stream
        // per use case (preview + image analysis).
        val streams = waitForValue("camera streams configured") { cameraStreams(context.packageName).takeIf { it >= 2 } }
        Log.i(LOG_TAG, "camera client ${context.packageName} has $streams output streams")

        // The pipeline keeps running: no "no camera" fallback, the scanner stays, the camera stays open.
        SystemClock.sleep(RUN_MS)
        compose.waitForIdle()
        assertTrue(
            "scanner fell back to 'no camera'",
            compose.onAllNodes(hasText(string(LinkingR.string.linking_camera_unavailable))).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag(TAG_SCANNER).assertIsDisplayed()
        assertTrue("back camera closed while the scanner is shown", openBackCameras.isNotEmpty())

        // Leaving the screen unbinds the use cases and releases the camera.
        compose.onNodeWithContentDescription(string(LinkingR.string.linking_back)).performClick()
        waitFor("chat list after leaving the scanner", hasText(string(ChatListR.string.chatlist_title)))
        waitFor("back camera released") { openBackCameras.isEmpty() }
    }

    /**
     * Number of configured output streams of the camera device [packageName] has open, per `dumpsys media.camera`
     * (run as the shell user through UiAutomation), or 0 when the package has no open camera.
     */
    private fun cameraStreams(packageName: String): Int {
        val dump = shell("dumpsys media.camera")
        val client = dump.indexOf("Client package: $packageName")
        if (client < 0) return 0
        // Each open device section lists its streams as "Stream[<n>]: Output" (Camera3Device dump).
        val section = dump.substring(client).let { rest ->
            val next = rest.indexOf("Client package:", startIndex = 1)
            if (next > 0) rest.substring(0, next) else rest
        }
        val count = STREAM.findAll(section).count()
        if (count == 0) Log.d(LOG_TAG, "camera client section without streams:\n${section.take(4000)}")
        return count
    }

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun string(@StringRes id: Int): String = compose.activity.getString(id)

    private fun waitFor(step: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            failStep(step, e)
        }
    }

    private fun waitFor(step: String, matcher: SemanticsMatcher) =
        waitFor(step) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun <T : Any> waitForValue(step: String, poll: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            poll()?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) failStep(step, AssertionError("no value within $timeoutMs ms"))
            SystemClock.sleep(POLL_MS)
        }
    }

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
        const val POLL_MS = 500L
        const val RUN_MS = 3_000L
        const val LOG_TAG = "CameraScannerE2eTest"
        val STREAM = Regex("Stream\\[\\d+]: Output")

        // Tags of internal constants in the feature modules (OnboardingTestTags, LinkDeviceTestTags).
        const val TAG_SERVER_URL = "onboarding_server_url"
        const val TAG_CREATE_ACCOUNT = "onboarding_create_account"
        const val TAG_DISPLAY_NAME = "onboarding_display_name"
        const val TAG_SUBMIT = "onboarding_submit"
        const val TAG_SKIP_HANDLE = "onboarding_skip_handle"
        const val TAG_SCANNER = "linking_scanner"
    }
}
