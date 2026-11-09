package ru.tinyops.android.bookrecon.core.rutracker

import ru.tinyops.android.bookrecon.core.domain.MediaKind

/**
 * Forum IDs searched for each [MediaKind], taken from the forum list of `tracker.php`
 * (the `f[]` select) in November 2026.
 *
 * `tracker.php?f=` matches the listed forums only: the ID of a parent forum does not include
 * its subforums (a parent alone finds nothing), so every leaf forum is listed. IDs go comma
 * separated in one `f` parameter.
 */
object RuTrackerForums {
    /**
     * The "Аудиокниги" group without its parent forums (radio plays and memoirs, fantasy,
     * fiction, religion, other literature) plus "Аудиокниги (AAC, ALAC)" from the Apple group.
     * Audiobooks in foreign languages are left out.
     */
    val AUDIO: List<Int> = listOf(
        574, 1036, 400, 2388, 2387, 661, 2348, 695, 399, 402, 467, 490, 499, 2137, 2127, 2325,
        2342, 530, 2152, 1350, 403, 1279, 716, 2165, 401, 1909,
    )

    /**
     * The "Книги и журналы" group: fiction and non-fiction, including periodicals and book
     * collections. Left out: sheet music (919), comics and manga (862), multimedia editions
     * (2114) and scan processing (1411), which hold no readable books.
     */
    val BOOKS: List<Int> = listOf(
        2157, 765, 2019, 31, 1427, 2422, 2195, 2521, 2223, 2447, 39, 2086, 745, 1689, 2336, 2337,
        1353, 1400, 1415, 2046, 2189, 2190, 2443, 1477, 669, 2196, 2056, 1436, 2191, 2477, 1684,
        2446, 2524, 2525, 995, 2022, 2471, 2375, 764, 1685, 1688, 2472, 1687, 1349, 1967, 1341,
        2049, 1681, 2319, 2434, 1683, 2444, 2427, 2452, 2445, 2435, 667, 2436, 1335, 2453, 2320,
        1801, 2024, 2026, 2192, 2027, 295, 2028, 2029, 1325, 2386, 2031, 2030, 2526, 2527, 2254,
        2376, 2054, 770, 2476, 2494, 1528, 2032, 2099, 2021, 2437, 1337, 1447, 2468, 2469, 2470,
        2215, 2216, 2546, 2217, 2218, 2252, 2543, 2515, 2516, 2517, 2518, 2519, 2520, 1696, 2253,
        1412, 1446, 753, 2037, 2224, 2194, 2418, 1410, 2034, 2433, 1961, 2432, 565, 1523, 1575,
        1520, 2424, 769, 2043, 2042, 2041, 2044, 2039, 2045, 2080, 2047, 2193, 1037, 1422, 1423,
        1424, 1445, 1425, 1426, 1428, 1429, 1430, 1431, 1433, 1432, 2202, 1238, 2055, 754, 2133,
        2130, 2313, 2528, 2129, 2141, 2314, 2132, 2212, 2131,
    )

    fun forKind(kind: MediaKind): List<Int> = when (kind) {
        MediaKind.AUDIO -> AUDIO
        MediaKind.BOOK -> BOOKS
    }
}
