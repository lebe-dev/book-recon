package ru.tinyops.android.bookrecon.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.Flow
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.ui.common.PlaceholderContent
import ru.tinyops.android.bookrecon.ui.nav.TopicKind
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose

@Suppress("UNUSED_PARAMETER")
@Composable
fun SearchScreen(
    onOpenFlibustaBook: (bookId: String) -> Unit,
    onOpenRuTrackerTopic: (topicId: String, kind: TopicKind, title: String) -> Unit,
    onOpenVerification: (url: String, purpose: VerificationPurpose) -> Unit,
    onOpenSettings: () -> Unit,
    verificationResults: Flow<Boolean>,
) {
    PlaceholderContent(stringResource(R.string.nav_search))
}
