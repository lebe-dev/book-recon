package ru.tinyops.android.bookrecon.log

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.port.Clock

class AppLogTest {
    @TempDir
    lateinit var dir: File

    private val executor = Executors.newSingleThreadExecutor()
    private var now = Instant.parse("2026-11-10T09:30:15.250Z")
    private val mirrored = mutableListOf<LogEntry>()

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun appLog(maxFileBytes: Long = 512 * 1024, maxEntries: Int = 1000) = AppLog(
        dir = dir,
        clock = Clock { now },
        zone = ZoneOffset.UTC,
        maxFileBytes = maxFileBytes,
        maxEntries = maxEntries,
        mirror = { mirrored += it },
        executor = executor,
    )

    @Test
    fun infoAndWarningsAreRecordedByDefaultButDebugIsNot() {
        val log = appLog()

        log.debug("Net") { "request started" }
        log.info("Net", "request done")
        log.warn("Net", "slow answer")
        log.error("Net", "request failed")

        assertEquals(
            listOf(LogLevel.INFO to "request done", LogLevel.WARN to "slow answer", LogLevel.ERROR to "request failed"),
            log.entries.value.map { it.level to it.message },
        )
    }

    @Test
    fun debugLevelCanBeSwitchedOnAndOff() {
        val log = appLog()

        log.setLevels(info = true, debug = true)
        log.debug("Net") { "details" }
        log.setLevels(info = true, debug = false)
        log.debug("Net") { "dropped" }

        assertEquals(listOf("details"), log.entries.value.map { it.message })
    }

    @Test
    fun infoLevelOffSilencesInfoWarningsAndErrors() {
        val log = appLog()

        log.setLevels(info = false, debug = true)
        log.info("Net", "a")
        log.warn("Net", "b")
        log.error("Net", "c")
        log.debug("Net") { "d" }

        assertEquals(listOf("d"), log.entries.value.map { it.message })
    }

    @Test
    fun skippedMessagesAreNotBuilt() {
        val log = appLog()
        var built = false

        log.debug("Net") {
            built = true
            "expensive"
        }

        assertFalse(built)
    }

    @Test
    fun entryCarriesTimeTagAndErrorSummary() {
        val log = appLog()

        log.warn("Queue", "cannot move file", IllegalStateException("disk full"))

        val entry = log.entries.value.single()
        assertEquals(now, entry.time)
        assertEquals("Queue", entry.tag)
        assertEquals("cannot move file: IllegalStateException: disk full", entry.message)
    }

    @Test
    fun linesAreFormattedForReading() {
        val log = appLog()

        log.info("Queue", "started")

        assertEquals("2026-11-10 09:30:15.250 I/Queue: started", log.text().trim())
    }

    @Test
    fun multilineMessageStaysOnOneLine() {
        val log = appLog()

        log.info("Queue", "first\nsecond")
        log.flush()

        assertEquals(1, log.text().trim().lines().size)
        assertEquals("first\nsecond", appLog().entries.value.single().message)
    }

    @Test
    fun entriesSurviveRestart() {
        appLog().apply {
            info("Queue", "one")
            warn("Queue", "two")
            flush()
        }

        val reopened = appLog()

        assertEquals(listOf("one", "two"), reopened.entries.value.map { it.message })
        assertEquals(LogLevel.WARN, reopened.entries.value.last().level)
    }

    @Test
    fun memoryKeepsOnlyTheNewestEntries() {
        val log = appLog(maxEntries = 3)

        repeat(5) { log.info("T", "m$it") }

        assertEquals(listOf("m2", "m3", "m4"), log.entries.value.map { it.message })
        assertEquals(5, log.text().trim().lines().size)
    }

    @Test
    fun fileRotatesAndKeepsOnePreviousGeneration() {
        val log = appLog(maxFileBytes = 200)

        repeat(40) { log.info("T", "message number $it") }
        val lines = log.text().trim().lines()

        assertTrue(File(dir, "app.log.1").exists())
        assertTrue(File(dir, "app.log").length() <= 200 + 80)
        assertTrue(lines.size < 40)
        assertTrue(lines.last().endsWith("message number 39"))
    }

    @Test
    fun clearRemovesEverything() {
        val log = appLog()
        log.info("T", "x")

        log.clear()

        assertTrue(log.entries.value.isEmpty())
        assertEquals("", log.text())
        assertTrue(appLog().entries.value.isEmpty())
    }

    @Test
    fun recordedEntriesAreMirroredToLogcat() {
        val log = appLog()

        log.info("T", "shown")
        log.debug("T") { "not recorded" }

        assertEquals(listOf("shown"), mirrored.map { it.message })
    }

    @Test
    fun corruptedLinesAreIgnoredOnLoad() {
        File(dir, "app.log").writeText("garbage\n2026-11-10 09:30:15.250 I/T: ok\n\n")

        assertEquals(listOf("ok"), appLog().entries.value.map { it.message })
    }

    @Test
    fun parsingRejectsUnknownFormat() {
        assertNull(parseLogEntry("hello", ZoneOffset.UTC))
        assertNull(parseLogEntry("2026-11-10 09:30:15.250 X/T: a", ZoneOffset.UTC))
    }
}
