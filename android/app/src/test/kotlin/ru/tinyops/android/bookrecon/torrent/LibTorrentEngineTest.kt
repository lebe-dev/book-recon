package ru.tinyops.android.bookrecon.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.libtorrent4j.TorrentInfo
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import java.io.File
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the engine against real libtorrent peers on the loopback interface. Skipped on
 * hosts without a libtorrent4j native library.
 */
class LibTorrentEngineTest {
    @TempDir
    lateinit var dir: File

    // Seeders and leechers run in their own processes, see TestPeer.
    private val testPeers = mutableListOf<TestPeer>()
    private val engines = mutableListOf<LibTorrentEngine>()

    // Files are not piece-aligned, so the pieces at the edges of a.mp3 and CD2/d.mp3 also
    // carry bytes of b.txt, which libtorrent writes to disk.
    private val torrent = TestTorrent(
        "Author - Title",
        listOf(
            TestFile("a.mp3", 100_000),
            TestFile("b.txt", 50_000),
            TestFile("c.txt", 40_000),
            TestFile("CD2/d.mp3", 70_000),
        ),
    )
    private val selected = setOf(0, 3)

    @BeforeEach
    fun native() {
        HostLibTorrent.assumeAvailable()
    }

    @AfterEach
    fun close() {
        engines.forEach { it.close() }
        testPeers.forEach { it.close() }
    }

    @Test
    fun `downloads only the selected files and removes partial neighbours`() = runBlocking {
        val seeder = seeder(torrent)
        val engine = engine(peers = listOf(seeder.address))
        val staging = File(dir, "staging/job-1")

        val updates = withTimeout(60.seconds) { engine.download("job-1", torrent.bytes, selected, staging).toList() }

        val total = 170_000L
        assertEquals(DownloadProgress(total, total, updates.last().peers, updates.last().speedBytesPerSec), updates.last())
        assertTrue(updates.all { it.total == total })
        assertEquals(listOf("Author - Title/CD2/d.mp3", "Author - Title/a.mp3"), filesUnder(staging))
        assertArrayEquals(torrent.files[0].data, File(staging, "Author - Title/a.mp3").readBytes())
        assertArrayEquals(torrent.files[3].data, File(staging, "Author - Title/CD2/d.mp3").readBytes())
        assertFalse(engine.isInSession("job-1"))
        assertFalse(engine.isSessionRunning)
    }

    @Test
    fun `interrupted download continues from resume data after a restart`() = runBlocking {
        val big = TestTorrent("Big", listOf(TestFile("one.mp3", 600_000), TestFile("two.mp3", 600_000)))
        val seeder = seeder(big)
        seeder.limitUpload(100_000)
        val stateDir = File(dir, "state")
        val staging = File(dir, "staging/job-1")

        val first = engine(peers = listOf(seeder.address), stateDir = stateDir)
        val partial = withTimeout(30.seconds) {
            first.download("job-1", big.bytes, setOf(0, 1), staging).first { it.completed > 0 }
        }
        assertTrue(partial.completed < partial.total)
        assertFalse(first.isInSession("job-1"))
        first.close()

        seeder.limitUpload(0)
        val second = engine(peers = listOf(seeder.address), stateDir = stateDir)
        val updates = withTimeout(60.seconds) { second.resume("job-1").toList() }

        assertEquals(1_200_000, updates.last().completed)
        assertArrayEquals(big.files[0].data, File(staging, "Big/one.mp3").readBytes())
        assertArrayEquals(big.files[1].data, File(staging, "Big/two.mp3").readBytes())
    }

    @Test
    fun `same torrent in another job is already downloading`() = runBlocking {
        val engine = engine()
        val running = async { runCatching { engine.download("job-1", torrent.bytes, selected, File(dir, "1")).toList() } }
        waitUntil { engine.isInSession("job-1") }

        val error = runCatching { engine.download("job-2", torrent.bytes, setOf(0), File(dir, "2")).toList() }.exceptionOrNull()

        assertTrue(error is AppError.AlreadyDownloading, "$error")
        engine.cancel("job-1")
        assertTrue(running.await().exceptionOrNull() is AppError.Interrupted)
    }

    @Test
    fun `no peers at all is no seeders`() = runBlocking {
        val engine = engine(stallTimeout = 1.seconds)

        val error = withTimeout(30.seconds) {
            runCatching { engine.download("job-1", torrent.bytes, selected, File(dir, "1")).toList() }.exceptionOrNull()
        }

        assertTrue(error is AppError.NoSeeders, "$error")
        assertFalse(engine.isSessionRunning)
        assertNotNull(engine.store.load("job-1"), "a failed download keeps its state for resume")
    }

    @Test
    fun `cancel stops the torrent and forgets the job`() = runBlocking {
        val engine = engine()
        val running = async { runCatching { engine.download("job-1", torrent.bytes, selected, File(dir, "1")).toList() } }
        waitUntil { engine.isInSession("job-1") }

        engine.cancel("job-1")

        assertTrue(running.await().exceptionOrNull() is AppError.Interrupted)
        assertFalse(engine.isInSession("job-1"))
        assertFalse(engine.isSessionRunning)
        assertNull(engine.store.load("job-1"))
        assertThrows(AppError.NotFound::class.java) { runBlocking { engine.resume("job-1").toList() } }
        Unit
    }

    @Test
    fun `cancelling the collector detaches the torrent and keeps resume state`() = runBlocking {
        val engine = engine()
        val running = async { engine.download("job-1", torrent.bytes, selected, File(dir, "1")).toList() }
        waitUntil { engine.isInSession("job-1") }

        running.cancel()
        runCatching { running.await() }.exceptionOrNull().let { assertTrue(it is CancellationException, "$it") }

        waitUntil { !engine.isInSession("job-1") }
        assertNotNull(engine.store.load("job-1"))
    }

    @Test
    fun `completed torrent moves storage and seeds for a limited time`() = runBlocking {
        val seeder = seeder(torrent)
        val engine = engine(peers = listOf(seeder.address))
        val staging = File(dir, "staging/job-1")
        withTimeout(60.seconds) { engine.download("job-1", torrent.bytes, selected, staging).toList() }

        val target = File(dir, "library")
        withTimeout(30.seconds) { engine.moveStorage("job-1", target) }

        assertEquals(listOf("Author - Title/CD2/d.mp3", "Author - Title/a.mp3"), filesUnder(target))
        assertEquals(emptyList<String>(), filesUnder(staging))
        assertFalse(engine.isInSession("job-1"))

        engine.seed("job-1", 2.seconds)
        assertTrue(engine.isInSession("job-1"))
        engine.pauseSeeding("job-1")
        assertFalse(engine.isInSession("job-1"))
        engine.resumeSeeding("job-1")
        assertTrue(engine.isInSession("job-1"))

        waitUntil(10.seconds) { !engine.isInSession("job-1") }
        assertFalse(engine.isSessionRunning)
        assertEquals(listOf("Author - Title/CD2/d.mp3", "Author - Title/a.mp3"), filesUnder(target))
    }

    @Test
    fun `move to a folder with the same files fails`() = runBlocking {
        val seeder = seeder(torrent)
        val engine = engine(peers = listOf(seeder.address))
        withTimeout(60.seconds) { engine.download("job-1", torrent.bytes, selected, File(dir, "staging")).toList() }
        val target = File(dir, "library")
        File(target, "Author - Title").mkdirs()
        File(target, "Author - Title/a.mp3").writeText("someone else's file")

        val error = runCatching { withTimeout(30.seconds) { engine.moveStorage("job-1", target) } }.exceptionOrNull()

        assertTrue(error is AppError.StorageFailed, "$error")
        assertEquals("someone else's file", File(target, "Author - Title/a.mp3").readText())
        assertTrue(File(dir, "staging/Author - Title/a.mp3").exists())
        assertFalse(engine.isSessionRunning)
    }

    @Test
    fun `files moved to the library keep seeding from there after a restart`() = runBlocking {
        // Piece-aligned files: every piece of a selected file is complete, so all can be served.
        val aligned = TestTorrent(
            "Root",
            listOf(TestFile("a.mp3", 3 * PIECE), TestFile("skip.txt", 2 * PIECE), TestFile("CD2/d.mp3", 4 * PIECE)),
        )
        val seeder = seeder(aligned)
        val stateDir = File(dir, "state")
        val staging = File(dir, "staging/job-1")
        val first = engine(peers = listOf(seeder.address), stateDir = stateDir)
        withTimeout(60.seconds) { first.download("job-1", aligned.bytes, setOf(0, 2), staging).toList() }
        seeder.close()
        testPeers -= seeder

        val a = File(dir, "library/Автор - Книга/a.mp3")
        val d = File(dir, "library/Автор - Книга/CD2/d.mp3")
        withTimeout(30.seconds) { first.moveFiles("job-1", mapOf(0 to a, 2 to d)) }

        assertArrayEquals(aligned.files[0].data, a.readBytes())
        assertArrayEquals(aligned.files[2].data, d.readBytes())
        assertEquals(emptyList<String>(), filesUnder(staging))
        assertFalse(first.isInSession("job-1"))
        first.close()

        val second = engine(stateDir = stateDir)
        second.seed("job-1", 60.seconds)
        val leeched = File(dir, "leeched")
        val leecher = TestPeer.leech(aligned, leeched, InetSocketAddress("127.0.0.1", second.listenPort), setOf(0, 2))
        testPeers += leecher
        leecher.awaitComplete(60.seconds)

        assertArrayEquals(aligned.files[0].data, File(leeched, "Root/a.mp3").readBytes())
        assertArrayEquals(aligned.files[2].data, File(leeched, "Root/CD2/d.mp3").readBytes())
        assertArrayEquals(aligned.files[0].data, a.readBytes())
    }

    @Test
    fun `moving files onto existing ones or unselected files fails`() = runBlocking {
        val seeder = seeder(torrent)
        val engine = engine(peers = listOf(seeder.address))
        val staging = File(dir, "staging")
        withTimeout(60.seconds) { engine.download("job-1", torrent.bytes, selected, staging).toList() }
        val taken = File(dir, "library/a.mp3")
        taken.parentFile.mkdirs()
        taken.writeText("someone else's file")

        val exists = runCatching { engine.moveFiles("job-1", mapOf(0 to taken, 3 to File(dir, "library/d.mp3"))) }
        val unselected = runCatching { engine.moveFiles("job-1", mapOf(1 to File(dir, "library/b.txt"))) }

        assertTrue(exists.exceptionOrNull() is AppError.StorageFailed, "${exists.exceptionOrNull()}")
        assertTrue(unselected.exceptionOrNull() is AppError.Validation, "${unselected.exceptionOrNull()}")
        assertEquals("someone else's file", taken.readText())
        assertEquals(listOf("Author - Title/CD2/d.mp3", "Author - Title/a.mp3"), filesUnder(staging))
        assertThrows(AppError.NotFound::class.java) { runBlocking { engine.moveFiles("nope", mapOf(0 to taken)) } }
        assertFalse(engine.isSessionRunning)
    }

    @Test
    fun `bad selections and unknown jobs are rejected`() = runBlocking {
        val engine = engine()

        assertThrows(AppError.SelectionEmpty::class.java) {
            runBlocking { engine.download("job-1", torrent.bytes, emptySet(), dir).toList() }
        }
        assertThrows(AppError.Validation::class.java) {
            runBlocking { engine.download("job-1", torrent.bytes, setOf(9), dir).toList() }
        }
        assertThrows(AppError.ParseError::class.java) {
            runBlocking { engine.download("job-1", "junk".toByteArray(), setOf(0), dir).toList() }
        }
        assertThrows(AppError.NotFound::class.java) { runBlocking { engine.resume("nope").toList() } }
        assertThrows(AppError.NotFound::class.java) { runBlocking { engine.seed("nope", 1.seconds) } }
        assertThrows(AppError.NotFound::class.java) { runBlocking { engine.moveStorage("nope", dir) } }
        engine.cancel("nope")
        assertFalse(engine.isSessionRunning)
    }

    @Test
    fun `parser indexes match libtorrent including padding files`() {
        val padded = TestTorrent(
            "Padded",
            listOf(
                TestFile("a.mp3", 1000),
                TestFile(listOf(".pad", "15384"), ByteArray(15384), attr = "p"),
                TestFile("b.mp3", 2000),
            ),
        )
        val parsed = TorrentParser.parse(padded.bytes)
        val native = TorrentInfo(padded.bytes)

        assertEquals(native.infoHash().toHex(), parsed.infoHash)
        parsed.files.forEach { file ->
            assertEquals(file.path, native.files().filePath(file.index).replace(File.separatorChar, '/'))
            assertEquals(file.size, native.files().fileSize(file.index))
        }
        assertTrue(native.files().padFileAt(1))
    }

    private companion object {
        const val PIECE = 16 * 1024
    }

    private fun engine(
        peers: List<InetSocketAddress> = emptyList(),
        stallTimeout: Duration = 60.seconds,
        stateDir: File = File(dir, "state"),
    ): LibTorrentEngine = LibTorrentEngine(
        stateDir,
        LibTorrentEngine.Config(
            pollInterval = 100.milliseconds,
            stallTimeout = stallTimeout,
            resumeSaveInterval = 200.milliseconds,
            localOnly = true,
            peers = peers,
        ),
        logger = PrintTorrentLogger,
    ).also { engines += it }

    private fun seeder(torrent: TestTorrent): TestPeer {
        val seedDir = File(dir, "seed-${testPeers.size}")
        torrent.writeTo(seedDir)
        return TestPeer.seed(torrent, seedDir).also { testPeers += it }
    }

    private fun filesUnder(root: File): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

    private suspend fun waitUntil(timeout: Duration = 10.seconds, condition: () -> Boolean) {
        withTimeout(timeout) {
            while (!condition()) delay(20)
        }
    }

    private object PrintTorrentLogger : TorrentLogger {
        override fun info(message: String) = println("I torrent: $message")

        override fun warn(message: String, error: Throwable?) = println("W torrent: $message ${error ?: ""}")
    }
}
