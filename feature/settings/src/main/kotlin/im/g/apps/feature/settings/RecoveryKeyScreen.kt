package im.g.apps.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.explainer.AccountExplainerPoint
import im.g.apps.core.designsystem.explainer.AccountExplainerPointContent
import im.g.apps.core.designsystem.explainer.AccountExplainerTestTags
import im.g.apps.core.designsystem.theme.GimTheme

/** "Your recovery key": what the key is (the explainer's recovery-key point) and why to keep the recovery kit safe. */
@Composable
fun RecoveryKeyScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackScaffold(title = stringResource(R.string.recovery_key_title), onBack = onBack, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountExplainerPointContent(
                point = AccountExplainerPoint.RECOVERY_KEY,
                modifier = Modifier.testTag(AccountExplainerTestTags.point(AccountExplainerPoint.RECOVERY_KEY)),
            )
            listOf(R.string.recovery_key_together, R.string.recovery_key_once, R.string.recovery_key_keep_safe).forEach {
                Text(text = stringResource(it), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@GimPreviews
@Composable
private fun RecoveryKeyScreenPreview() {
    GimTheme { RecoveryKeyScreen(onBack = {}) }
}
