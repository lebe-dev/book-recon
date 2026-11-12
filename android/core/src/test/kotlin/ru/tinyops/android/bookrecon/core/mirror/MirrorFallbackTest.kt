package ru.tinyops.android.bookrecon.core.mirror

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.tinyops.android.bookrecon.core.domain.AppError

class MirrorFallbackTest {
    private val memory = InMemoryMirrorMemory()

    private suspend fun MirrorFallback.tried(bases: List<String>, failing: Map<String, AppError>): List<String> {
        val calls = mutableListOf<String>()
        runCatching {
            run(bases) { base ->
                calls += base
                failing[base]?.let { throw it }
                base
            }
        }
        return calls
    }

    @Test
    fun `the mirror that answered goes first next time, also for a new fallback with the same memory`() = runTest {
        val bases = listOf("https://a", "https://b")
        MirrorFallback("Site", memory).tried(bases, mapOf("https://a" to AppError.Network()))

        assertEquals(listOf("https://b"), MirrorFallback("Site", memory).tried(bases, emptyMap()))
    }

    @Test
    fun `a remembered mirror does not override a newly chosen first mirror`() = runTest {
        val fallback = MirrorFallback("Site", memory)
        fallback.tried(listOf("https://a", "https://b"), mapOf("https://a" to AppError.Network()))

        assertEquals(listOf("https://c"), fallback.tried(listOf("https://c", "https://b"), emptyMap()))
    }

    @Test
    fun `sites do not share what they remember`() = runTest {
        val bases = listOf("https://a", "https://b")
        MirrorFallback("One", memory).tried(bases, mapOf("https://a" to AppError.Network()))

        assertEquals(listOf("https://a"), MirrorFallback("Two", memory).tried(bases, emptyMap()))
    }

    @Test
    fun `when every mirror fails, the error of the one that got furthest is thrown`() = runTest {
        val bases = listOf("https://a", "https://b", "https://c")
        val fallback = MirrorFallback("Site", memory)

        val timeout = assertThrows<AppError> {
            fallback.run(bases) { base ->
                throw if (base == "https://b") AppError.Timeout("slow b") else AppError.Network("no $base")
            }
        }
        assertEquals("slow b", timeout.message)

        val down = assertThrows<AppError> {
            fallback.run(bases) { base ->
                throw when (base) {
                    "https://a" -> AppError.Timeout("slow a")
                    "https://c" -> AppError.ServiceDown("c is down")
                    else -> AppError.Network("no b")
                }
            }
        }
        assertEquals("c is down", down.message)
    }

    @Test
    fun `of equal failures the first mirror's one is thrown`() = runTest {
        val error = assertThrows<AppError> {
            MirrorFallback("Site", memory).run(listOf("https://a", "https://b")) { base -> throw AppError.Network("no $base") }
        }
        assertEquals("no https://a", error.message)
    }

    @Test
    fun `every mirror that cannot be reached is reported as trouble, other errors are not`() = runTest {
        val trouble = mutableListOf<Pair<String, String?>>()
        val fallback = MirrorFallback("Site", memory) { base, error -> trouble += base to error.message }

        fallback.run(listOf("https://a", "https://b", "https://c")) { base ->
            if (base != "https://c") throw if (base == "https://a") AppError.Timeout("slow") else AppError.ServiceDown("down")
            base
        }
        assertThrows<AppError.NotFound> { fallback.run(listOf("https://c")) { throw AppError.NotFound("no book") } }

        assertEquals(listOf("https://a" to "slow", "https://b" to "down"), trouble)
    }

    @Test
    fun `the key names the site and the first mirror`() {
        assertEquals("Flibusta https://flibusta.site", MirrorFallback.keyOf(MirrorSites.FLIBUSTA, listOf("https://flibusta.site", "https://flibusta.is")))
        assertEquals(null, MirrorFallback.keyOf(MirrorSites.FLIBUSTA, emptyList()))
    }
}
