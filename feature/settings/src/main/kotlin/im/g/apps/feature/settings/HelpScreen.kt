package im.g.apps.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.explainer.AccountExplainer
import im.g.apps.core.designsystem.theme.GimTheme

internal object HelpTestTags {
    const val LICENCES = "help_licences"
}

/** A bundled library and its licence, as listed on [LicencesScreen]. */
internal data class Library(@StringRes val name: Int, @StringRes val licence: Int)

internal val bundledLibraries = listOf(
    Library(R.string.library_androidx, R.string.licence_apache_2),
    Library(R.string.library_kotlin, R.string.licence_apache_2),
    Library(R.string.library_coil, R.string.licence_apache_2),
    Library(R.string.library_jna, R.string.licence_apache_2_lgpl_2_1),
    Library(R.string.library_zxing, R.string.licence_apache_2),
    Library(R.string.library_gim_core, R.string.licence_gim_core),
)

/** "Help and about": how gim works, the app and SDK versions, and the open-source licences. */
@Composable
fun HelpScreen(
    appVersion: String,
    sdkVersion: String,
    onBack: () -> Unit,
    onLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackScaffold(title = stringResource(R.string.help_title), onBack = onBack, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionHeader(stringResource(R.string.help_how_gim_works))
            AccountExplainer(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            SettingsSectionHeader(stringResource(R.string.help_section_about))
            SettingsRow(title = stringResource(R.string.help_app_version), onClick = null, value = appVersion)
            SettingsRow(title = stringResource(R.string.help_sdk_version), onClick = null, value = sdkVersion)
            SettingsRow(
                title = stringResource(R.string.help_licences),
                onClick = onLicences,
                modifier = Modifier.testTag(HelpTestTags.LICENCES),
            )
        }
    }
}

/** The libraries bundled in the app, each with its licence. */
@Composable
fun LicencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackScaffold(title = stringResource(R.string.licences_title), onBack = onBack, modifier = modifier) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            items(bundledLibraries, key = { it.name }) { library ->
                SettingsRow(title = stringResource(library.name), onClick = null, value = stringResource(library.licence))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
        content = content,
    )
}

@GimPreviews
@Composable
private fun HelpScreenPreview() {
    GimTheme { HelpScreen(appVersion = "0.1.0", sdkVersion = "0.1.0+c1b7503", onBack = {}, onLicences = {}) }
}

@GimPreviews
@Composable
private fun LicencesScreenPreview() {
    GimTheme { LicencesScreen(onBack = {}) }
}
