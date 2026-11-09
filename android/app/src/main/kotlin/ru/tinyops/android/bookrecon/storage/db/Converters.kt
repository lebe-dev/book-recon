package ru.tinyops.android.bookrecon.storage.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant

/** Room converters: instants as epoch milliseconds, path lists as JSON arrays. */
class Converters {
    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun pathsToJson(value: List<String>): String = Json.encodeToString(pathsSerializer, value)

    @TypeConverter
    fun jsonToPaths(value: String): List<String> = Json.decodeFromString(pathsSerializer, value)

    private companion object {
        val pathsSerializer = ListSerializer(String.serializer())
    }
}
