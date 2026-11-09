package ru.tinyops.android.bookrecon.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestdataTest {
    @Test
    fun `testdata directory is passed to tests`() {
        val dir = System.getProperty("testdata.dir")
        assertEquals("testdata", dir?.let { File(it).name })
    }
}
