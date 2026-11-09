package ru.tinyops.android.bookrecon.torrent

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.tinyops.android.bookrecon.core.domain.AppError
import java.io.File

class JobStoreTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `job survives a new store instance`() {
        val staging = File(dir, "staging dir")
        JobStore(dir).create(StoredJob("job-1", byteArrayOf(1, 2, 3), setOf(4, 0, 2), staging))

        val job = JobStore(dir).load("job-1")!!

        assertArrayEquals(byteArrayOf(1, 2, 3), job.torrent)
        assertEquals(setOf(0, 2, 4), job.indexes)
        assertEquals(staging.absoluteFile, job.stagingDir)
        assertNull(JobStore(dir).loadResume("job-1"))
    }

    @Test
    fun `resume data is replaced and removed with the job`() {
        val store = JobStore(dir)
        store.create(StoredJob("job-1", byteArrayOf(1), setOf(0), dir))

        store.saveResume("job-1", byteArrayOf(7))
        store.saveResume("job-1", byteArrayOf(8, 9))
        assertArrayEquals(byteArrayOf(8, 9), store.loadResume("job-1"))

        store.delete("job-1")
        assertNull(store.load("job-1"))
        assertNull(store.loadResume("job-1"))
        assertFalse(File(dir, "job-1").exists())
    }

    @Test
    fun `resume data is not written for a deleted job`() {
        val store = JobStore(dir)

        store.saveResume("gone", byteArrayOf(1))

        assertNull(store.loadResume("gone"))
        assertFalse(File(dir, "gone").exists())
    }

    @Test
    fun `unknown and broken jobs load as null`() {
        val store = JobStore(dir)
        assertNull(store.load("missing"))

        File(dir, "broken").mkdirs()
        File(dir, "broken/job.properties").writeText("indexes=x")
        assertNull(store.load("broken"))
    }

    @Test
    fun `job ids that escape the folder are rejected`() {
        val store = JobStore(dir)

        listOf("", ".", "..", "../x", "a/b", "a\\b").forEach { id ->
            assertThrows(AppError.Validation::class.java, { store.load(id) }, id)
        }
    }
}
