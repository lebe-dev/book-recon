package ru.tinyops.android.bookrecon.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.Flow
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.ui.common.PlaceholderContent
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose

@Suppress("UNUSED_PARAMETER")
@Composable
fun SettingsScreen(
    onOpenVerification: (url: String, purpose: VerificationPurpose) -> Unit,
    verificationResults: Flow<Boolean>,
) {
    PlaceholderContent(stringResource(R.string.nav_settings))
}
