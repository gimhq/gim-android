package im.g.apps.core.designsystem.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import im.g.apps.core.designsystem.R

/** The "account suspended" message of every screen: names [hub] when the user has several hubs, else "this hub". */
fun userSuspendedMessage(context: Context, hub: String?): String =
    if (hub == null) {
        context.getString(R.string.gim_error_user_suspended)
    } else {
        context.getString(R.string.gim_error_user_suspended_on_hub, hub)
    }

@Composable
fun userSuspendedMessage(hub: String?): String =
    if (hub == null) {
        stringResource(R.string.gim_error_user_suspended)
    } else {
        stringResource(R.string.gim_error_user_suspended_on_hub, hub)
    }
