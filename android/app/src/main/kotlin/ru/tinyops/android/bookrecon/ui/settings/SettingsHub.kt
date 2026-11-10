package ru.tinyops.android.bookrecon.ui.settings

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import ru.tinyops.android.bookrecon.BuildConfig
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.ui.common.ScreenTitle
import ru.tinyops.android.bookrecon.ui.theme.BookReconTheme

/** A thematic subpage of the settings. */
@Keep
@Serializable
enum class SettingsSection(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    val icon: ImageVector,
) {
    APPEARANCE(R.string.settings_section_appearance, R.string.settings_hub_appearance, Icons.Filled.Star),
    STORAGE(R.string.settings_section_storage, R.string.settings_hub_storage, Icons.Filled.Folder),
    DOWNLOADS(R.string.settings_section_downloads, R.string.settings_hub_downloads, Icons.Filled.Download),
    SOURCES(R.string.settings_hub_sources_title, R.string.settings_hub_sources, Icons.Filled.Search),
    SERVER(R.string.settings_section_server, R.string.settings_hub_server, Icons.Filled.Settings),
    DIAGNOSTICS(R.string.settings_hub_diagnostics_title, R.string.settings_hub_diagnostics, Icons.Filled.Info),
}

/** The settings tab: a list of thematic subpages. */
@Composable
fun SettingsScreen(onOpenSection: (SettingsSection) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ScreenTitle(stringResource(R.string.nav_settings))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                SettingsSection.entries.forEach { section ->
                    ListItem(
                        colors = cardListItemColors(),
                        modifier = Modifier.clickable { onOpenSection(section) },
                        headlineContent = { Text(stringResource(section.title)) },
                        supportingContent = { Text(stringResource(section.summary)) },
                        leadingContent = { Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        )
    }
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun SettingsHubPreview() {
    BookReconTheme {
        Surface { SettingsScreen(onOpenSection = {}) }
    }
}
