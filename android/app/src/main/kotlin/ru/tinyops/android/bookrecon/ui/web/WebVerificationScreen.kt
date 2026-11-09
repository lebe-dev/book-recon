package ru.tinyops.android.bookrecon.ui.web

import androidx.compose.runtime.Composable
import ru.tinyops.android.bookrecon.ui.common.PlaceholderContent
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose

/** Calls [onDone] with true once the check passed, or with false when the user gave up. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun WebVerificationScreen(
    url: String,
    purpose: VerificationPurpose,
    onDone: (passed: Boolean) -> Unit,
) {
    PlaceholderContent(purpose.name, url)
}
