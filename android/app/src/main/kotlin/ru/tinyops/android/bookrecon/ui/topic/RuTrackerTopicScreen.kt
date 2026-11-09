package ru.tinyops.android.bookrecon.ui.topic

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import ru.tinyops.android.bookrecon.ui.common.PlaceholderContent
import ru.tinyops.android.bookrecon.ui.nav.TopicKind
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose

@Suppress("UNUSED_PARAMETER")
@Composable
fun RuTrackerTopicScreen(
    topicId: String,
    kind: TopicKind,
    title: String,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVerification: (url: String, purpose: VerificationPurpose) -> Unit,
    verificationResults: Flow<Boolean>,
) {
    PlaceholderContent(title, topicId, kind.name)
}
