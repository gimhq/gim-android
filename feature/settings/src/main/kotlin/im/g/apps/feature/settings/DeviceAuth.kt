package im.g.apps.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Asks the user to confirm it is them before something sensitive is shown. */
fun interface DeviceAuthenticator {
    fun authenticate(title: String, subtitle: String, onResult: (DeviceAuthResult) -> Unit)
}

/**
 * [DeviceAuthenticator] with the system [BiometricPrompt]: a strong biometric or the device screen lock (PIN, pattern,
 * password). Android 9 and 10 do not combine strong biometrics with the screen lock, so there any biometric the
 * system offers or the screen lock is accepted.
 */
class BiometricDeviceAuthenticator(private val activity: FragmentActivity) : DeviceAuthenticator {
    override fun authenticate(title: String, subtitle: String, onResult: (DeviceAuthResult) -> Unit) {
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        }
        when (BiometricManager.from(activity).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> return onResult(DeviceAuthResult.NO_LOCK)
            else -> Unit // Let the prompt report anything else.
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onResult(DeviceAuthResult.SUCCESS)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED,
                        -> DeviceAuthResult.CANCELLED
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        -> DeviceAuthResult.NO_LOCK
                        else -> DeviceAuthResult.FAILED
                    },
                )
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}

/**
 * A [BiometricDeviceAuthenticator] for the activity of [context], looked up when authentication starts; the app's
 * activity is a [FragmentActivity].
 */
fun systemDeviceAuthenticator(context: Context) = DeviceAuthenticator { title, subtitle, onResult ->
    val activity = context.findActivity() as? FragmentActivity
    if (activity == null) {
        onResult(DeviceAuthResult.FAILED)
    } else {
        BiometricDeviceAuthenticator(activity).authenticate(title, subtitle, onResult)
    }
}

/** Blocks screenshots, screen recording and the recents preview of the window while in composition. */
@Composable
internal fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
