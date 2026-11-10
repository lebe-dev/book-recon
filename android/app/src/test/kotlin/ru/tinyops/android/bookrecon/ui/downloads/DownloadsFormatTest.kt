package ru.tinyops.android.bookrecon.ui.downloads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import java.time.Instant
import java.time.ZoneId

class DownloadsFormatTest {
    private val moscow = ZoneId.of("Europe/Moscow")

    @Test
    fun `seeding that ends today shows only the time`() {
        val now = Instant.parse("2026-11-09T07:00:00Z")
        val until = Instant.parse("2026-11-09T15:05:00Z")

        assertEquals(SeedUntil.Today("18:05"), seedUntil(until, now, moscow))
    }

    @Test
    fun `seeding that ends after midnight says tomorrow`() {
        val now = Instant.parse("2026-11-09T19:00:00Z")
        val until = Instant.parse("2026-11-09T23:30:00Z")

        assertEquals(SeedUntil.Tomorrow("02:30"), seedUntil(until, now, moscow))
    }

    @Test
    fun `seeding that ends later shows the date`() {
        val now = Instant.parse("2026-11-09T07:00:00Z")
        val until = Instant.parse("2026-11-12T09:00:00Z")

        assertEquals(SeedUntil.Later("12.11", "12:00"), seedUntil(until, now, moscow))
    }

    @Test
    fun `an overdue deadline is still shown as today`() {
        val now = Instant.parse("2026-11-09T12:00:00Z")
        val until = Instant.parse("2026-11-09T11:00:00Z")

        assertEquals(SeedUntil.Today("14:00"), seedUntil(until, now, moscow))
    }

    @Test
    fun `the time zone decides the day`() {
        val now = Instant.parse("2026-11-09T20:00:00Z")
        val until = Instant.parse("2026-11-09T22:00:00Z")

        assertEquals(SeedUntil.Today("22:00"), seedUntil(until, now, ZoneId.of("UTC")))
        assertEquals(SeedUntil.Tomorrow("01:00"), seedUntil(until, now, moscow))
    }

    @Test
    fun `storage and login errors are fixed in settings, others are not`() {
        assertEquals(true, AppError.StorageNotGranted().fixableInSettings())
        assertEquals(true, AppError.RootNotConfigured(MediaKind.AUDIO).fixableInSettings())
        assertEquals(true, AppError.RuTrackerAuthRequired().fixableInSettings())
        assertEquals(true, AppError.RuTrackerLoginFailed().fixableInSettings())
        assertEquals(false, AppError.NoSeeders().fixableInSettings())
        assertEquals(false, AppError.Stalled().fixableInSettings())
        assertEquals(false, AppError.Network().fixableInSettings())
    }
}
