package ru.tinyops.android.bookrecon.core.rutracker

import java.io.File

/** RuTracker pages saved from the live site, stored in windows-1251 as served. */
internal object Fixtures {
    private val dir = File(requireNotNull(System.getProperty("testdata.dir")) { "testdata.dir is not set" }, "rutracker")

    fun bytes(name: String): ByteArray = File(dir, name).readBytes()

    fun text(name: String): String = String(bytes(name), RuTrackerParser.CHARSET)
}
