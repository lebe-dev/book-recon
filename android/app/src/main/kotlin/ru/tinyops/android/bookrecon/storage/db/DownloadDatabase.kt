package ru.tinyops.android.bookrecon.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** Download queue and library. The schema of every version is exported to `app/schemas/`. */
@Database(
    entities = [DownloadEntity::class, DownloadFileEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        const val FILE_NAME = "book-recon.db"

        fun open(context: Context): DownloadDatabase =
            Room.databaseBuilder(context.applicationContext, DownloadDatabase::class.java, FILE_NAME).build()
    }
}
