package ru.tinyops.android.bookrecon.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.Flow
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.BookResult
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.core.usecase.search.SearchState
import ru.tinyops.android.bookrecon.core.usecase.search.SourceState
import ru.tinyops.android.bookrecon.di.appContainer
import ru.tinyops.android.bookrecon.ui.nav.TopicKind
import ru.tinyops.android.bookrecon.ui.nav.VerificationPurpose

@Composable
fun SearchScreen(
    onOpenFlibustaBook: (bookId: String) -> Unit,
    onOpenRuTrackerTopic: (topicId: String, kind: TopicKind, title: String) -> Unit,
    onOpenVerification: (url: String, purpose: VerificationPurpose) -> Unit,
    onOpenSettings: () -> Unit,
    verificationResults: Flow<Boolean>,
) {
    val context = LocalContext.current
    val factory = remember(context) {
        val searchUseCase = context.appContainer.search.searchUseCase
        viewModelFactory { initializer { SearchViewModel(searchUseCase, createSavedStateHandle()) } }
    }
    val viewModel: SearchViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(verificationResults) {
        verificationResults.collect(viewModel::onVerificationResult)
    }

    val pending = state.pendingVerification
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        viewModel.onVerificationOpened()
        onOpenVerification(pending.url, pending.purpose)
    }

    SearchContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onKindChange = viewModel::onKindChange,
        onSearch = viewModel::onSearch,
        onRetry = viewModel::onRetry,
        onOpenResult = { result ->
            when (result.source) {
                Source.FLIBUSTA -> onOpenFlibustaBook(result.sourceId)
                Source.RUTRACKER -> onOpenRuTrackerTopic(result.sourceId, result.kind.toTopicKind(), result.title)
            }
        },
        onOpenVerification = { request -> onOpenVerification(request.url, request.purpose) },
        onOpenSettings = onOpenSettings,
    )
}

private fun MediaKind.toTopicKind(): TopicKind = when (this) {
    MediaKind.BOOK -> TopicKind.BOOK
    MediaKind.AUDIO -> TopicKind.AUDIO
}

/** Stateless body of the search screen, also used by previews. */
@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onKindChange: (MediaKind) -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    onOpenResult: (BookResult) -> Unit,
    onOpenVerification: (VerificationRequest) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        onSearch()
    }
    val search = state.search

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(state.query, state.queryTooShort, onQueryChange, submit)
            KindSelector(state.kind, onKindChange)
        }

        if (search?.isLoading == true) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (search == null) {
            CenteredText(stringResource(if (state.kind == MediaKind.AUDIO) R.string.search_hint_audio else R.string.search_hint_books))
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(search.sources.entries.toList(), key = { "status-${it.key}" }) { (source, sourceState) ->
                SourceStatusRow(source, sourceState, onRetry, onOpenVerification, onOpenSettings)
            }
            items(search.results, key = { "result-${it.source}-${it.sourceId}" }) { result ->
                ResultItem(result, onClick = { onOpenResult(result) })
                HorizontalDivider()
            }
            if (search.isComplete && search.results.isEmpty() && search.errors.isEmpty() && search.verification == null) {
                item(key = "nothing-found") {
                    CenteredText(stringResource(R.string.search_nothing_found))
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, tooShort: Boolean, onQueryChange: (String) -> Unit, onSubmit: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.search_field_label)) },
        singleLine = true,
        isError = tooShort,
        supportingText = if (tooShort) {
            { Text(stringResource(R.string.search_query_too_short)) }
        } else {
            null
        },
        leadingIcon = {
            IconButton(onClick = onSubmit) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_action))
            }
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
}

@Composable
private fun KindSelector(kind: MediaKind, onKindChange: (MediaKind) -> Unit) {
    val options = listOf(MediaKind.BOOK to R.string.search_kind_books, MediaKind.AUDIO to R.string.search_kind_audio)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = option == kind,
                onClick = { onKindChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun SourceStatusRow(
    source: Source,
    state: SourceState,
    onRetry: () -> Unit,
    onOpenVerification: (VerificationRequest) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val name = stringResource(source.label())
    when (state) {
        is SourceState.Loaded -> Unit
        SourceState.Loading -> StatusRow(text = stringResource(R.string.search_source_loading, name)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        is SourceState.Failed -> {
            val error = state.error
            val text = stringResource(R.string.search_source_error, name, stringResource(error.searchMessage()))
            val toSettings = error.leadsToSettings()
            StatusRow(
                text = text,
                color = MaterialTheme.colorScheme.error,
                action = {
                    TextButton(onClick = if (toSettings) onOpenSettings else onRetry) {
                        Text(stringResource(if (toSettings) R.string.search_open_settings else R.string.search_retry))
                    }
                },
            ) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        }
        is SourceState.VerificationRequired -> {
            val request = VerificationRequest(state.url, state.error.verificationPurpose())
            StatusRow(
                text = stringResource(R.string.search_source_error, name, stringResource(state.error.searchMessage())),
                action = {
                    TextButton(onClick = { onOpenVerification(request) }) {
                        Text(stringResource(R.string.search_pass_verification))
                    }
                },
            ) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null)
            }
        }
    }
}

@Composable
private fun StatusRow(
    text: String,
    color: Color = Color.Unspecified,
    action: (@Composable () -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Text(text, modifier = Modifier.weight(1f), color = color, style = MaterialTheme.typography.bodyMedium)
        action?.invoke()
    }
}

@Composable
private fun ResultItem(result: BookResult, onClick: () -> Unit) {
    val colors = if (result.alreadyDownloaded) {
        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        ListItemDefaults.colors()
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = colors,
        overlineContent = { Text(stringResource(result.source.label())) },
        headlineContent = { Text(result.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        supportingContent = { ResultDetails(result) },
        trailingContent = if (result.alreadyDownloaded) {
            { DownloadedMark() }
        } else {
            null
        },
    )
}

@Composable
private fun ResultDetails(result: BookResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (result.author.isNotBlank()) {
            Text(result.author, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (result.formats.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                result.formats.forEach { FormatChip(it.extension.uppercase()) }
            }
        }
        val seeders = result.seeders ?: return@Column
        val stats = stringResource(R.string.search_seeders_peers, seeders, result.peers ?: 0)
        val size = result.size?.takeIf { it > 0 }
        if (size == null) {
            Text(stats)
            return@Column
        }
        val formatted = formatSize(size)
        val sizeText = stringResource(R.string.search_size, formatted.number, stringResource(formatted.unit.label))
        Text(stringResource(R.string.search_size_and_stats, sizeText, stats))
    }
}

@Composable
private fun FormatChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DownloadedMark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.search_downloaded),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CenteredText(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// Previews of the main states, built from the same sample data as the fakes.

private const val PREVIEW_TOPIC_SIZE = 734_003_200L
private const val PREVIEW_CHALLENGE_URL = "https://rutracker.org/forum/tracker.php"

private val previewBooks: List<BookResult> = FakeData.flibustaBooks.take(3).mapIndexed { index, book ->
    BookResult(Source.FLIBUSTA, book.id, book.title, book.author, MediaKind.BOOK, book.formats, alreadyDownloaded = index == 0)
}

private fun previewTopics(kind: MediaKind): List<BookResult> = FakeData.topics.filter { it.kind == kind }.map { topic ->
    BookResult(
        source = Source.RUTRACKER,
        sourceId = topic.topicId,
        title = topic.title,
        author = "",
        kind = topic.kind,
        size = topic.size.takeIf { it > 0 } ?: PREVIEW_TOPIC_SIZE,
        seeders = topic.seeders,
        peers = topic.peers,
    )
}

private fun previewState(query: String, kind: MediaKind, vararg sources: Pair<Source, SourceState>) =
    SearchUiState(query = query, kind = kind, submittedQuery = query, search = SearchState(query, kind, mapOf(*sources)))

@Composable
private fun SearchPreview(state: SearchUiState) {
    MaterialTheme {
        Surface {
            SearchContent(state, {}, {}, {}, {}, {}, {}, {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchIdlePreview() = SearchPreview(SearchUiState())

@Preview(showBackground = true)
@Composable
private fun SearchTooShortPreview() = SearchPreview(SearchUiState(query = "м", queryTooShort = true))

@Preview(showBackground = true)
@Composable
private fun SearchLoadingPreview() = SearchPreview(
    previewState(
        "Булгаков",
        MediaKind.BOOK,
        Source.FLIBUSTA to SourceState.Loaded(previewBooks),
        Source.RUTRACKER to SourceState.Loading,
    ),
)

@Preview(showBackground = true)
@Composable
private fun SearchBooksWithErrorPreview() = SearchPreview(
    previewState(
        "Булгаков",
        MediaKind.BOOK,
        Source.FLIBUSTA to SourceState.Loaded(previewBooks),
        Source.RUTRACKER to SourceState.Failed(AppError.RuTrackerAuthRequired()),
    ),
)

@Preview(showBackground = true)
@Composable
private fun SearchAudioPreview() = SearchPreview(
    previewState("Мастер", MediaKind.AUDIO, Source.RUTRACKER to SourceState.Loaded(previewTopics(MediaKind.AUDIO))),
)

@Preview(showBackground = true)
@Composable
private fun SearchVerificationPreview() = SearchPreview(
    previewState(
        "Мастер",
        MediaKind.AUDIO,
        Source.RUTRACKER to SourceState.VerificationRequired(AppError.CloudflareChallenge(PREVIEW_CHALLENGE_URL), PREVIEW_CHALLENGE_URL),
    ),
)

@Preview(showBackground = true)
@Composable
private fun SearchNothingFoundPreview() = SearchPreview(
    previewState(
        "абвгд",
        MediaKind.BOOK,
        Source.FLIBUSTA to SourceState.Loaded(emptyList()),
        Source.RUTRACKER to SourceState.Loaded(emptyList()),
    ),
)
