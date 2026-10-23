package im.g.apps.core.designsystem.explainer

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.R
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme

/** One idea of the "How gim works" account explainer, in reading order. */
enum class AccountExplainerPoint(@StringRes val title: Int, @StringRes val body: Int) {
    NO_PHONE_NUMBER(R.string.explainer_no_phone_number_title, R.string.explainer_no_phone_number_body),
    ON_YOUR_DEVICES(R.string.explainer_on_your_devices_title, R.string.explainer_on_your_devices_body),
    LOCKED_MESSAGES(R.string.explainer_locked_messages_title, R.string.explainer_locked_messages_body),
    ACCOUNT_ID(R.string.explainer_account_id_title, R.string.explainer_account_id_body),
    RECOVERY_KEY(R.string.explainer_recovery_key_title, R.string.explainer_recovery_key_body),
}

/** The points shown as onboarding intro pages: the first three, in order. */
val accountIntroPoints: List<AccountExplainerPoint> = AccountExplainerPoint.entries.take(3)

object AccountExplainerTestTags {
    const val ROOT = "account_explainer"

    fun point(point: AccountExplainerPoint): String = "account_explainer_point_${point.name.lowercase()}"
}

/** A point's title and body, read by TalkBack as one node with the title as a heading. */
@Composable
fun AccountExplainerPointContent(point: AccountExplainerPoint, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(point.title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(point.body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The full "How gim works" explainer: an intro line and every [AccountExplainerPoint].
 * Has no scrolling of its own; the host screen scrolls and shows the title.
 */
@Composable
fun AccountExplainer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(AccountExplainerTestTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.explainer_intro), style = MaterialTheme.typography.bodyLarge)
        AccountExplainerPoint.entries.forEach { point ->
            AccountExplainerPointContent(
                point = point,
                modifier = Modifier.testTag(AccountExplainerTestTags.point(point)),
            )
        }
    }
}

@GimPreviews
@Composable
private fun AccountExplainerPreview() {
    GimTheme {
        Surface {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.explainer_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                AccountExplainer()
            }
        }
    }
}

@GimPreviews
@Composable
private fun AccountExplainerPointPreview() {
    GimTheme {
        Surface {
            AccountExplainerPointContent(
                point = AccountExplainerPoint.RECOVERY_KEY,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
