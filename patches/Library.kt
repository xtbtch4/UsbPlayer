package com.example.usbplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val provider: String = "local",
    val genre: String = "",
    val year: Int = 0,
    val trackNo: Int = 0,
    val albumArtist: String = "",
    val art: String = "",
    val albumId: Long = 0L
)

fun Track.albumKey() = "${album.ifEmpty { "Без альбома" }}\u0000${albumArtist.ifEmpty { artist }}"

object Library {
    private val collection: Uri = Uri.parse("content://media/external/audio/media")

    fun load(ctx: Context): List<Track> {
        // Use literal MediaStore column names for compatibility across Android SDK versions.
        val columns = arrayOf(
            "_id", "title", "artist", "album", "duration", "year", "track",
            "album_artist", "genre"
        )
        val out = mutableListOf<Track>()
        ctx.contentResolver.query(
            collection,
            columns,
            "is_music != 0",
            null,
            "title COLLATE NOCASE"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex("_id")
            val titleIndex = cursor.getColumnIndex("title")
            val artistIndex = cursor.getColumnIndex("artist")
            val albumIndex = cursor.getColumnIndex("album")
            val durationIndex = cursor.getColumnIndex("duration")
            val yearIndex = cursor.getColumnIndex("year")
            val trackIndex = cursor.getColumnIndex("track")
            val albumArtistIndex = cursor.getColumnIndex("album_artist")
            val genreIndex = cursor.getColumnIndex("genre")

            while (cursor.moveToNext()) {
                val id = if (idIndex >= 0) cursor.getLong(idIndex) else continue
                out += Track(
                    id = id,
                    title = if (titleIndex >= 0) cursor.getString(titleIndex) ?: "?" else "?",
                    artist = if (artistIndex >= 0) cursor.getString(artistIndex) ?: "Unknown" else "Unknown",
                    album = if (albumIndex >= 0) cursor.getString(albumIndex) ?: "" else "",
                    durationMs = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L,
                    uri = ContentUris.withAppendedId(collection, id),
                    year = if (yearIndex >= 0) cursor.getInt(yearIndex) else 0,
                    trackNo = if (trackIndex >= 0) cursor.getInt(trackIndex) else 0,
                    albumArtist = if (albumArtistIndex >= 0) cursor.getString(albumArtistIndex) ?: "" else "",
                    genre = if (genreIndex >= 0) cursor.getString(genreIndex) ?: "" else ""
                )
            }
        }
        return out
    }
}
