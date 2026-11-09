package ru.tinyops.android.bookrecon.core.fake

import app.cash.turbine.test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.AppError
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class FakeTorrentEngineTest {
    @TempDir
    lateinit var tmp: File

    private val engine = FakeTorrentEngine(steps = 4, stepDelay = 10.milliseconds)
    private val bytes = FakeData.torrentBytes(FakeData.AUDIO_TOPIC_CD)

    @Test
    fun `parses fake torrent with CD folders`() {
        val info = engine.parse(bytes)

        assertTrue(info.files.any { it.path.contains("/CD1/") })
        assertTrue(info.files.any { it.path.contains("/CD2/") })
        assertThrows<AppError.ParseError> { engine.parse("garbage".toByteArray()) }
    }

    @Test
    fun `downloads only selected files into staging`() = runTest {
        val info = engine.parse(bytes)
        val selected = setOf(0, 3)

        val progress = engine.download("job", bytes, selected, tmp).toList()

        val total = info.files.filter { it.index in selected }.sumOf { it.size }
        assertEquals(total, progress.last().completed)
        assertEquals(total, progress.last().total)
        info.files.forEach { file ->
            assertEquals(file.index in selected, File(tmp, file.path).exists(), file.path)
        }
        assertEquals(FakeTorrentEngine.JobState.DONE, engine.state("job"))
    }

    @Test
    fun `same torrent cannot run twice`() = runTest {
        engine.download("first", bytes, setOf(0), tmp).test {
            awaitItem()
            assertThrows<AppError.AlreadyDownloading> {
                engine.download("second", bytes, setOf(0), File(tmp, "other")).toList()
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(FakeTorrentEngine.JobState.PAUSED, engine.state("first"))
    }

    @Test
    fun `failure midway is reported and resume continues`() = runTest {
        engine.failAtStep = AppError.Stalled()

        assertThrows<AppError.Stalled> { engine.download("job", bytes, setOf(0), tmp).toList() }
        assertFalse(File(tmp, engine.parse(bytes).files[0].path).exists())

        engine.resume("job").toList()
        assertEquals(FakeTorrentEngine.JobState.DONE, engine.state("job"))
        assertThrows<AppError.NotFound> { engine.resume("unknown") }
    }

    @Test
    fun `seeding can be paused and data moved`() = runTest {
        engine.download("job", bytes, setOf(0), tmp).toList()
        engine.seed("job", 2.hours)
        engine.pauseSeeding("job")
        assertEquals(FakeTorrentEngine.JobState.SEEDING_PAUSED, engine.state("job"))
        engine.resumeSeeding("job")
        assertEquals(FakeTorrentEngine.JobState.SEEDING, engine.state("job"))
        assertEquals(2.hours, engine.seedDuration("job"))

        val target = File(tmp, "moved")
        engine.moveStorage("job", target)

        assertTrue(File(target, engine.parse(bytes).files[0].path).exists())
    }
}
