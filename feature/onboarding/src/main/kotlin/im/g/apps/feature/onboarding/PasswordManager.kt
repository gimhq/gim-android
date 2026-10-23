package im.g.apps.feature.onboarding

import android.content.Context
import android.os.Build
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException

/** How offering the recovery key to a password manager ended. */
enum class PasswordSaveResult { SAVED, CANCELLED, FAILED }

/**
 * Whether the framework Credential Manager can save passwords. It ships with API 34; older releases need Play
 * Services, which the app does not include, so the action is hidden there.
 */
internal fun isPasswordManagerAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * Saves [recoveryKey] under [accountId] in the user's password manager. Best effort: no provider, an unsupported
 * request and every other failure end as [PasswordSaveResult.FAILED]. [context] must be an activity, which hosts
 * the system sheet.
 */
internal suspend fun saveToPasswordManager(context: Context, accountId: String, recoveryKey: String): PasswordSaveResult =
    try {
        CredentialManager.create(context).createCredential(context, CreatePasswordRequest(accountId, recoveryKey))
        PasswordSaveResult.SAVED
    } catch (_: CreateCredentialCancellationException) {
        PasswordSaveResult.CANCELLED
    } catch (_: CreateCredentialException) {
        PasswordSaveResult.FAILED
    }
