package ru.tinyops.android.bookrecon.storage.files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

class TreeUrisTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks | /storage/emulated/0/Audiobooks",
            "content://com.android.externalstorage.documents/tree/primary%3A | /storage/emulated/0",
            "content://com.android.externalstorage.documents/tree/primary%3ABooks%2FFiction | /storage/emulated/0/Books/Fiction",
            "content://com.android.externalstorage.documents/tree/primary%3ABooks%2F | /storage/emulated/0/Books",
            "content://com.android.externalstorage.documents/tree/1234-ABCD%3AAudio | /storage/1234-ABCD/Audio",
            "content://com.android.externalstorage.documents/tree/1234-ABCD%3A | /storage/1234-ABCD",
            "content://com.android.externalstorage.documents/tree/1234-ABCD%3AMedia%2FAudio%20books | /storage/1234-ABCD/Media/Audio books",
            "content://com.android.externalstorage.documents/tree/primary%3A%D0%9A%D0%BD%D0%B8%D0%B3%D0%B8 | /storage/emulated/0/Книги",
            "content://com.android.externalstorage.documents/tree/primary%3AC%2B%2B | /storage/emulated/0/C++",
            "content://com.android.externalstorage.documents/tree/home%3ANotes | /storage/emulated/0/Documents/Notes",
            "content://com.android.externalstorage.documents/tree/home%3A | /storage/emulated/0/Documents",
            "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2FSub | /storage/emulated/0/Books",
            "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FBooks | /storage/emulated/0/Download/Books",
        ],
    )
    fun convertsTreeUri(uri: String, path: String) {
        assertEquals(path, TreeUris.pathOf(uri))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "not a uri",
            "file:///storage/emulated/0/Books",
            // A single document, not a tree.
            "content://com.android.externalstorage.documents/document/primary%3ABooks",
            // Other providers have opaque document IDs.
            "content://com.android.providers.downloads.documents/tree/downloads",
            "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3D2",
            "content://com.android.providers.media.documents/tree/audio_root",
            // No volume or a malformed document ID.
            "content://com.android.externalstorage.documents/tree/",
            "content://com.android.externalstorage.documents/tree/Books",
            "content://com.android.externalstorage.documents/tree/%3ABooks",
            "content://com.android.externalstorage.documents/tree/primary%3A..%2Fother",
            "content://com.android.externalstorage.documents/tree/bad%2Fvolume%3ABooks",
            "content://com.android.providers.downloads.documents/tree/raw%3Arelative%2Fpath",
            "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2F..%2Fdata",
        ],
    )
    fun rejectsWhatIsNotAFolderOnSharedStorage(uri: String) {
        assertNull(TreeUris.pathOf(uri))
    }

    @Test
    fun primaryVolumeFollowsTheCurrentUser() {
        assertEquals(
            "/storage/emulated/10/Audiobooks",
            TreeUris.pathOf(
                "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks",
                primaryRoot = "/storage/emulated/10",
            ),
        )
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks | Audiobooks",
            "content://com.android.externalstorage.documents/tree/primary%3ABooks%2FFiction | Books/Fiction",
            "content://com.android.externalstorage.documents/tree/primary%3A%D0%9A%D0%BD%D0%B8%D0%B3%D0%B8 | Книги",
            "content://com.android.externalstorage.documents/tree/home%3ANotes | Documents/Notes",
            "content://com.android.externalstorage.documents/tree/1234-ABCD%3AAudio | 1234-ABCD:/Audio",
        ],
    )
    fun labelsTreeUri(uri: String, label: String) {
        assertEquals(label, TreeUris.label(uri))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "content://com.android.externalstorage.documents/tree/primary%3A",
            "content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FBooks",
            "content://com.android.providers.media.documents/tree/images",
            "/storage/emulated/0/Books",
            "",
        ],
    )
    fun unsupportedTreesHaveNoLabelAndAreNotSupported(uri: String) {
        assertNull(TreeUris.label(uri))
        assertFalse(TreeUris.isSupported(uri))
    }

    @Test
    fun supportsFoldersOfExternalStorage() {
        assertTrue(TreeUris.isSupported("content://com.android.externalstorage.documents/tree/primary%3ABooks"))
        assertTrue(TreeUris.isSupported("content://com.android.externalstorage.documents/tree/1234-ABCD%3AAudio"))
    }
}
