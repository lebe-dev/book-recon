package ru.tinyops.android.bookrecon.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AppErrorTest {
    private val samples: List<AppError> = listOf(
        AppError.Network(),
        AppError.ServiceDown(),
        AppError.Timeout(),
        AppError.NotFound(),
        AppError.ParseError(),
        AppError.FormatNotAvailable(Format.DJVU),
        AppError.BookUnavailable(),
        AppError.Validation("query is too short"),
        AppError.CloudflareChallenge("https://rutracker.org/forum/tracker.php"),
        AppError.RuTrackerAuthRequired(),
        AppError.RuTrackerLoginFailed(),
        AppError.RuTrackerCaptcha("https://rutracker.org/forum/login.php"),
        AppError.TorrentTooLarge(),
        AppError.NoSeeders(),
        AppError.Stalled(),
        AppError.AlreadyDownloading(),
        AppError.SelectionEmpty(),
        AppError.NoMatchingFiles(MediaKind.AUDIO),
        AppError.StorageNotGranted(),
        AppError.RootNotConfigured(MediaKind.AUDIO),
        AppError.StorageFailed(),
        AppError.Interrupted(),
        AppError.Unknown(),
    )

    @Test
    fun `every code has a sample`() {
        assertEquals(ErrorCode.entries.toSet(), samples.map { it.code }.toSet())
    }

    @Test
    fun `restore rebuilds the same error`() {
        samples.forEach { error ->
            val restored = AppError.restore(error.code, error.detail)
            assertEquals(error, restored)
            assertEquals(error::class, restored::class)
        }
    }

    @Test
    fun `restore with a broken detail falls back to unknown`() {
        assertEquals(ErrorCode.UNKNOWN, AppError.restore(ErrorCode.ROOT_NOT_CONFIGURED, "VIDEO").code)
        assertEquals(ErrorCode.UNKNOWN, AppError.restore(ErrorCode.CLOUDFLARE_CHALLENGE, null).code)
    }

    @Test
    fun `format lookup ignores case and dot`() {
        assertEquals(Format.FB2, Format.fromExtension(".FB2"))
        assertNull(Format.fromExtension("doc"))
    }
}
