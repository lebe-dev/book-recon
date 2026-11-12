package ru.tinyops.android.bookrecon.core.update

/**
 * Version of the app as `major.minor.patch`, the same scheme as the root `VERSION` file and the
 * release tags (`v0.9.1`). Compared part by part, so 0.10.0 is newer than 0.9.9.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""[vV]?(\d{1,4})\.(\d{1,4})(?:\.(\d{1,4}))?""")

        /** Reads `v0.9.1`, `0.9.1` or `0.9`; null for anything else, pre-release suffixes included. */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            return AppVersion(major.toInt(), minor.toInt(), patch.ifEmpty { "0" }.toInt())
        }
    }
}
