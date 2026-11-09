package ru.tinyops.android.bookrecon.ui.book

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import ru.tinyops.android.bookrecon.ui.common.PlaceholderContent
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose

@Suppress("UNUSED_PARAMETER")
@Composable
fun FlibustaBookScreen(
    bookId: String,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVerification: (url: String, purpose: VerificationPurpose) -> Unit,
    verificationResults: Flow<Boolean>,
) {
    PlaceholderContent("Flibusta", bookId)
}
