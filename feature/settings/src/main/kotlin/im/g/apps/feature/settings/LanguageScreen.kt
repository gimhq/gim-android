package im.g.apps.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme

/** Test tags; the device E2E tests repeat these literals. */
internal object LanguageTestTags {
    const val PHONE = "language_phone"

    fun option(tag: String) = "language_$tag"
}

@Composable
fun LanguageRoute(viewModel: LanguageViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LanguageScreen(state = state, onSelect = viewModel::select, onBack = onBack, modifier = modifier)
}

/** "Language": the phone language, or one of the app's languages, each named in its own language. */
@Composable
fun LanguageScreen(
    state: LanguageUiState,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackScaffold(title = stringResource(R.string.language_title), onBack = onBack, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            SettingsRadioRow(
                title = stringResource(R.string.language_phone),
                selected = state.selected == null,
                onClick = { onSelect(null) },
                modifier = Modifier.testTag(LanguageTestTags.PHONE),
            )
            state.options.forEach {
                SettingsRadioRow(
                    title = it.name,
                    selected = it.tag == state.selected,
                    onClick = { onSelect(it.tag) },
                    modifier = Modifier.testTag(LanguageTestTags.option(it.tag)),
                )
            }
        }
    }
}

@GimPreviews
@Composable
private fun LanguageScreenPreview() {
    GimTheme {
        LanguageScreen(
            state = LanguageUiState(options = listOf(LanguageOption("en", "English")), selected = null),
            onSelect = {},
            onBack = {},
        )
    }
}
