package ru.tinyops.android.bookrecon.core.usecase.search

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.BookResult
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Settings
import ru.tinyops.android.bookrecon.core.domain.Source
import ru.tinyops.android.bookrecon.core.domain.TorrentTopic
import ru.tinyops.android.bookrecon.core.fake.FakeBehavior
import ru.tinyops.android.bookrecon.core.fake.FakeData
import ru.tinyops.android.bookrecon.core.fake.FakeDownloadRepository
import ru.tinyops.android.bookrecon.core.fake.FakeFlibustaSource
import ru.tinyops.android.bookrecon.core.fake.FakeRuTrackerClient
import ru.tinyops.android.bookrecon.core.fake.FakeSettingsRepository
import ru.tinyops.android.bookrecon.core.port.RuTrackerClient
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SearchUseCaseTest {
    private val flibusta = FakeFlibustaSource()
    private val ruTracker = FakeRuTrackerClient()
    private val settings = FakeSettingsRepository(Settings(rutrackerLogin = "reader"), password = "secret")
    private val downloads = FakeDownloadRepository()

    private fun useCase(client: RuTrackerClient = ruTracker) =
        SearchUseCase(flibusta, client, settings, downloads, sourceTimeout = 60.seconds)

    @Test
    fun `book search queries Flibusta and RuTracker books`() = runTest {
        val final = useCase().search("Достоевский", MediaKind.BOOK).toList().last()

        assertTrue(final.isComplete)
        assertEquals(listOf(Source.FLIBUSTA, Source.RUTRACKER), final.sources.keys.toList())
        assertEquals(listOf("22222"), final.resultsOf(Source.FLIBUSTA).map { it.sourceId })
        assertEquals(listOf(FakeData.BOOK_TOPIC_FORMATS), final.resultsOf(Source.RUTRACKER).map { it.sourceId })
        assertEquals(listOf("22222", FakeData.BOOK_TOPIC_FORMATS), final.results.map { it.sourceId })
        assertTrue(final.errors.isEmpty())
        assertNull(final.verification)
    }

    @Test
    fun `RuTracker topic becomes a result with size, seeders and peers`() = runTest {
        val final = useCase().search("Преступление", MediaKind.BOOK).toList().last()

        val topic = FakeData.topics.first { it.topicId == FakeData.BOOK_TOPIC_FORMATS }
        val expected = BookResult(
            source = Source.RUTRACKER,
            sourceId = topic.topicId,
            title = topic.title,
            author = "",
            kind = MediaKind.BOOK,
            size = topic.size,
            seeders = topic.seeders,
            peers = topic.peers,
        )
        assertEquals(listOf(expected), final.resultsOf(Source.RUTRACKER))
    }

    @Test
    fun `audio search queries RuTracker audio only`() = runTest {
        val final = useCase().search("Мастер", MediaKind.AUDIO).toList().last()

        assertEquals(listOf(Source.RUTRACKER), final.sources.keys.toList())
        assertEquals(listOf(FakeData.AUDIO_TOPIC_CD), final.results.map { it.sourceId })
        assertTrue(final.results.all { it.kind == MediaKind.AUDIO })
    }

    @Test
    fun `results arrive per source as each one answers`() = runTest {
        ruTracker.behavior.delay = 5.seconds

        val states = useCase().search("Достоевский", MediaKind.BOOK).toList()

        assertEquals(3, states.size)
        assertEquals(SourceState.Loading, states[0].sourceState(Source.FLIBUSTA))
        assertEquals(SourceState.Loading, states[0].sourceState(Source.RUTRACKER))
        assertTrue(states[0].isLoading)

        assertTrue(states[1].sourceState(Source.FLIBUSTA) is SourceState.Loaded)
        assertEquals(SourceState.Loading, states[1].sourceState(Source.RUTRACKER))
        assertEquals(listOf("22222"), states[1].results.map { it.sourceId })
        assertFalse(states[1].isComplete)

        assertTrue(states[2].isComplete)
        assertFalse(states[2].isLoading)
    }

    @Test
    fun `one failed source keeps the results of the other`() = runTest {
        ruTracker.behavior.error = AppError.Network()

        val final = useCase().search("Достоевский", MediaKind.BOOK).toList().last()

        assertEquals(listOf("22222"), final.results.map { it.sourceId })
        assertEquals(SourceState.Failed(AppError.Network()), final.sourceState(Source.RUTRACKER))
        assertEquals(mapOf(Source.RUTRACKER to AppError.Network()), final.errors)
        assertTrue(final.isComplete)
    }

    @Test
    fun `unexpected exception becomes an Unknown error of that source`() = runTest {
        val broken = object : RuTrackerClient by ruTracker {
            override suspend fun search(query: String, kind: MediaKind, limit: Int): List<TorrentTopic> =
                error("boom")
        }

        val final = useCase(broken).search("Достоевский", MediaKind.BOOK).toList().last()

        assertEquals(listOf("22222"), final.results.map { it.sourceId })
        assertTrue(final.errors.getValue(Source.RUTRACKER) is AppError.Unknown)
    }

    @Test
    fun `slow source times out while the other answers`() = runTest {
        ruTracker.behavior.delay = 2.minutes

        val final = useCase().search("Достоевский", MediaKind.BOOK).toList().last()

        assertEquals(listOf("22222"), final.results.map { it.sourceId })
        assertEquals(SourceState.Failed(AppError.Timeout()), final.sourceState(Source.RUTRACKER))
        assertEquals(60.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `missing RuTracker login skips RuTracker without calling it`() = runTest {
        settings.update { it.copy(rutrackerLogin = "") }
        ruTracker.loggedIn = false

        val final = useCase().search("Достоевский", MediaKind.BOOK).toList().last()

        assertEquals(listOf("22222"), final.results.map { it.sourceId })
        assertEquals(SourceState.Failed(AppError.RuTrackerAuthRequired()), final.sourceState(Source.RUTRACKER))
        assertEquals(0, ruTracker.loginCount)
    }

    @Test
    fun `RuTracker auth error from the client is a source error`() = runTest {
        ruTracker.loggedIn = false
        ruTracker.credentialsSet = false

        val final = useCase().search("Мастер", MediaKind.AUDIO).toList().last()

        assertTrue(final.results.isEmpty())
        assertEquals(mapOf(Source.RUTRACKER to AppError.RuTrackerAuthRequired()), final.errors)
    }

    @Test
    fun `Cloudflare challenge is reported as a verification state`() = runTest {
        val url = "https://rutracker.org/forum/tracker.php"
        ruTracker.behavior.error = AppError.CloudflareChallenge(url)

        val final = useCase().search("Достоевский", MediaKind.BOOK).toList().last()

        val expected = SourceState.VerificationRequired(AppError.CloudflareChallenge(url), url)
        assertEquals(expected, final.sourceState(Source.RUTRACKER))
        assertEquals(Verification(Source.RUTRACKER, AppError.CloudflareChallenge(url), url), final.verification)
        assertTrue(final.errors.isEmpty())
        assertEquals(listOf("22222"), final.results.map { it.sourceId })
    }

    @Test
    fun `RuTracker captcha is reported as a verification state with the login page`() = runTest {
        ruTracker.loggedIn = false
        ruTracker.captcha = true

        val final = useCase().search("Мастер", MediaKind.AUDIO).toList().last()

        val error = AppError.RuTrackerCaptcha(FakeRuTrackerClient.LOGIN_URL)
        assertEquals(Verification(Source.RUTRACKER, error, FakeRuTrackerClient.LOGIN_URL), final.verification)
    }

    @Test
    fun `completed downloads with files on disk are marked`() = runTest {
        val repository = FakeDownloadRepository(FakeData.library(Instant.EPOCH), FakeData::sampleFileExists)
        val useCase = SearchUseCase(flibusta, ruTracker, settings, repository)

        val books = useCase.search("Мастер", MediaKind.BOOK).toList().last()
        val marked = books.results.associate { it.sourceId to it.alreadyDownloaded }
        assertEquals(mapOf("11111" to true, "66666" to false), marked)

        val missing = useCase.search("Замятин", MediaKind.BOOK).toList().last()
        assertEquals(listOf(false), missing.results.map { it.alreadyDownloaded })

        val audio = useCase.search("Пикник", MediaKind.AUDIO).toList().last()
        assertEquals(listOf(FakeData.AUDIO_TOPIC_FLAT to true), audio.results.map { it.sourceId to it.alreadyDownloaded })
    }

    @Test
    fun `seeding downloads with files on disk are marked too`() = runTest {
        val library = FakeData.library(Instant.EPOCH)
        val seeding = library.map {
            if (it.source != Source.RUTRACKER) return@map it
            it.copy(status = DownloadStatus.SEEDING, seedUntil = Instant.EPOCH.plusSeconds(3600))
        }
        val repository = FakeDownloadRepository(seeding, FakeData::sampleFileExists)
        val useCase = SearchUseCase(flibusta, ruTracker, settings, repository)

        val audio = useCase.search("Пикник", MediaKind.AUDIO).toList().last()
        assertEquals(listOf(FakeData.AUDIO_TOPIC_FLAT to true), audio.results.map { it.sourceId to it.alreadyDownloaded })
    }

    @Test
    fun `seeding download with missing files is not marked`() = runTest {
        val sample = FakeData.library(Instant.EPOCH).first { it.source == Source.RUTRACKER }
        val repository = FakeDownloadRepository(
            listOf(sample.copy(status = DownloadStatus.SEEDING, paths = listOf(FakeData.MISSING_SAMPLE_PATH))),
            FakeData::sampleFileExists,
        )
        val useCase = SearchUseCase(flibusta, ruTracker, settings, repository)

        val audio = useCase.search("Пикник", MediaKind.AUDIO).toList().last()
        assertEquals(listOf(false), audio.results.map { it.alreadyDownloaded })
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "a", "  я  "])
    fun `short query is dropped without touching sources`(query: String) = runTest {
        flibusta.behavior.error = AppError.Network()
        ruTracker.behavior.error = AppError.Network()

        assertFalse(SearchUseCase.accepts(query))
        assertTrue(useCase().search(query, MediaKind.BOOK).toList().isEmpty())
    }

    @Test
    fun `query is trimmed and truncated`() = runTest {
        assertTrue(SearchUseCase.accepts(" Мы "))
        assertEquals("Мы", useCase().search("  Мы  ", MediaKind.BOOK).toList().last().query)

        val long = "Мастер" + "x".repeat(300)
        val state = useCase().search(long, MediaKind.BOOK).toList().last()
        assertEquals(SearchUseCase.MAX_QUERY_LENGTH, state.query.length)
    }

    @Test
    fun `each source returns at most the search limit`() = runTest {
        val many = (1..30).map { TorrentTopic("$it", "Книга $it", 1, 1, 0, MediaKind.AUDIO) }
        val client = FakeRuTrackerClient(many)

        val final = useCase(client).search("Книга", MediaKind.AUDIO).toList().last()

        assertEquals(SearchUseCase.SEARCH_LIMIT, final.results.size)
    }
}
