package ru.tinyops.android.bookrecon.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ru.tinyops.android.bookrecon.core.domain.DownloadStatus
import ru.tinyops.android.bookrecon.core.domain.MediaKind
import ru.tinyops.android.bookrecon.core.domain.Source

@Dao
abstract class DownloadDao {
    /**
     * Queue screen: DOWNLOADING, SEEDING, QUEUED by position, then FAILED, then CANCELLED;
     * ties by creation time, oldest first.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM downloads
        WHERE status IN ('DOWNLOADING', 'SEEDING', 'QUEUED', 'FAILED', 'CANCELLED')
        ORDER BY
            CASE status WHEN 'DOWNLOADING' THEN 0 WHEN 'SEEDING' THEN 1 WHEN 'QUEUED' THEN 2 WHEN 'FAILED' THEN 3 ELSE 4 END,
            queuePosition IS NULL, queuePosition, createdAt, id
        """,
    )
    abstract fun observeQueue(): Flow<List<DownloadWithFiles>>

    /**
     * Library downloads (COMPLETED and SEEDING), newest first. [pattern] is a LIKE pattern over [DownloadEntity.searchKey]
     * with `\` as the escape character; [kind] null means both kinds.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM downloads
        WHERE status IN ('COMPLETED', 'SEEDING')
            AND (:kind IS NULL OR kind = :kind)
            AND searchKey LIKE :pattern ESCAPE '\'
        ORDER BY COALESCE(completedAt, createdAt) DESC, id
        """,
    )
    abstract fun observeLibrary(pattern: String, kind: MediaKind?): Flow<List<DownloadWithFiles>>

    @Transaction
    @Query("SELECT * FROM downloads WHERE id = :id")
    abstract suspend fun get(id: String): DownloadWithFiles?

    @Transaction
    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY createdAt, id")
    abstract suspend fun listByStatus(statuses: Collection<DownloadStatus>): List<DownloadWithFiles>

    /** The newest library download (COMPLETED or SEEDING) of one source item. */
    @Transaction
    @Query(
        """
        SELECT * FROM downloads
        WHERE status IN ('COMPLETED', 'SEEDING') AND source = :source AND sourceId = :sourceId
        ORDER BY COALESCE(completedAt, createdAt) DESC, id
        LIMIT 1
        """,
    )
    abstract suspend fun findInLibrary(source: Source, sourceId: String): DownloadWithFiles?

    @Query("SELECT COUNT(*) FROM download_files")
    abstract suspend fun countFiles(): Int

    /** Inserts the row and its files; throws SQLiteConstraintException when the ID exists. */
    @Transaction
    open suspend fun insert(download: DownloadWithFiles) {
        insertRow(download.download)
        insertFiles(download.files)
    }

    /** Replaces the row and its files; false when there is no row with this ID. */
    @Transaction
    open suspend fun replace(download: DownloadWithFiles): Boolean {
        if (updateRow(download.download) == 0) return false
        deleteFiles(download.download.id)
        insertFiles(download.files)
        return true
    }

    /** Deletes the row; its files go with it through the foreign key. */
    @Query("DELETE FROM downloads WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRow(download: DownloadEntity)

    @Update
    protected abstract suspend fun updateRow(download: DownloadEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertFiles(files: List<DownloadFileEntity>)

    @Query("DELETE FROM download_files WHERE downloadId = :id")
    protected abstract suspend fun deleteFiles(id: String)
}
