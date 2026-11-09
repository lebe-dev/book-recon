package ru.tinyops.android.bookrecon.storage.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.Format
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source
import java.time.Instant

/**
 * One row per download. Enums are stored by name, so existing names must not change.
 *
 * @property searchKey lowercased title and author for the library search: SQLite folds case
 *   for ASCII only, so Cyrillic is lowercased before it is stored.
 * @property errorCode name of an ErrorCode; kept as text so a removed code reads back as
 *   an unknown error instead of failing the whole query.
 * @property targetPaths absolute paths placed into the roots, as a JSON array.
 */
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["source", "sourceId"]),
        Index(value = ["status"]),
    ],
)
data class DownloadEntity(
    @PrimaryKey val id: String,
    val kind: MediaKind,
    val source: Source,
    val sourceId: String,
    val title: String,
    val author: String,
    val searchKey: String,
    val format: Format?,
    val targetName: String?,
    val status: DownloadStatus,
    val completedBytes: Long,
    val totalBytes: Long,
    val queuePosition: Int?,
    val size: Long,
    val errorCode: String?,
    val errorDetail: String?,
    val createdAt: Instant,
    val completedAt: Instant?,
    val seedUntil: Instant?,
    val targetPaths: List<String>,
)

/** A selected torrent file of a download. */
@Entity(
    tableName = "download_files",
    primaryKeys = ["downloadId", "torrentIndex"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DownloadFileEntity(
    val downloadId: String,
    val torrentIndex: Int,
    val relativePath: String,
    val size: Long,
)

/** A download row with its files. */
data class DownloadWithFiles(
    @Embedded val download: DownloadEntity,
    @Relation(parentColumn = "id", entityColumn = "downloadId")
    val files: List<DownloadFileEntity>,
)
