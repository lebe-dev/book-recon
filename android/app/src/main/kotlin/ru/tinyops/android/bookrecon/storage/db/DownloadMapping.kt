package ru.tinyops.android.bookrecon.storage.db

import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.Download
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import ru.tinyops.android.bookrecon.core.domain.ErrorCode
import ru.tinyops.android.bookrecon.core.domain.TorrentFile

/** Lowercased title and author; the line break keeps a match from spanning both. */
internal fun searchKeyOf(title: String, author: String): String = "$title\n$author".lowercase()

/** The row without files. Peers, speed and [Download.fileMissing] are not stored. */
internal fun Download.toEntity(): DownloadEntity = DownloadEntity(
    id = id,
    kind = kind,
    source = source,
    sourceId = sourceId,
    title = title,
    author = author,
    searchKey = searchKeyOf(title, author),
    format = format,
    targetName = targetName,
    status = status,
    completedBytes = progress.completed,
    totalBytes = progress.total,
    queuePosition = queuePosition,
    size = size,
    errorCode = error?.code?.name,
    errorDetail = error?.detail,
    createdAt = createdAt,
    completedAt = completedAt,
    seedUntil = seedUntil,
    targetPaths = paths,
)

internal fun Download.toRow(): DownloadWithFiles = DownloadWithFiles(
    download = toEntity(),
    files = files.map { DownloadFileEntity(id, it.index, it.path, it.size) },
)

/** The stored download with [Download.fileMissing] left false; the repository fills it. */
internal fun DownloadWithFiles.toDomain(): Download = with(download) {
    Download(
        id = id,
        kind = kind,
        source = source,
        sourceId = sourceId,
        title = title,
        author = author,
        format = format,
        targetName = targetName,
        status = status,
        progress = DownloadProgress(completed = completedBytes, total = totalBytes),
        queuePosition = queuePosition,
        files = files.sortedBy { it.torrentIndex }.map { TorrentFile(it.torrentIndex, it.relativePath, it.size) },
        paths = targetPaths,
        size = size,
        error = restoreError(errorCode, errorDetail),
        createdAt = createdAt,
        completedAt = completedAt,
        seedUntil = seedUntil,
    )
}

private fun restoreError(code: String?, detail: String?): AppError? {
    if (code == null) return null
    val known = ErrorCode.entries.firstOrNull { it.name == code } ?: return AppError.Unknown("unknown error code $code")
    return AppError.restore(known, detail)
}
