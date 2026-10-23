package im.g.apps.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme

/** Test tags; the device E2E tests repeat these literals. */
internal object AppearanceTestTags {
    const val SYSTEM = "appearance_system"
    const val LIGHT = "appearance_light"
    const val DARK = "appearance_dark"
}

/** The label of [mode], as shown on [AppearanceScreen] and the Settings row. */
@StringRes
internal fun ThemeMode.label(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.appearance_system
    ThemeMode.LIGHT -> R.string.appearance_light
    ThemeMode.DARK -> R.string.appearance_dark
}

private fun ThemeMode.testTag(): String = when (this) {
    ThemeMode.SYSTEM -> AppearanceTestTags.SYSTEM
    ThemeMode.LIGHT -> AppearanceTestTags.LIGHT
    ThemeMode.DARK -> AppearanceTestTags.DARK
}

@Composable
fun AppearanceRoute(viewModel: AppearanceViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    AppearanceScreen(mode = mode, onSelect = viewModel::select, onBack = onBack, modifier = modifier)
}

/** "Appearance": follow the phone, light or dark. */
@Composable
fun AppearanceScreen(
    mode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackScaffold(title = stringResource(R.string.appearance_title), onBack = onBack, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .selectableGroup(),
        ) {
            ThemeMode.entries.forEach {
                SettingsRadioRow(
                    title = stringResource(it.label()),
                    selected = it == mode,
                    onClick = { onSelect(it) },
                    modifier = Modifier.testTag(it.testTag()),
                )
            }
        }
    }
}

@GimPreviews
@Composable
private fun AppearanceScreenPreview() {
    GimTheme { AppearanceScreen(mode = ThemeMode.SYSTEM, onSelect = {}, onBack = {}) }
}
