package com.example.usbplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class Track(
    val id: Long, val title: String, val artist: String, val album: String,
    val durationMs: Long, val uri: Uri, val provider: String = "local",
    val genre: String = "", val year: Int = 0, val trackNo: Int = 0, val albumArtist: String = ""
)

fun Track.albumKey() = "${album.ifEmpty { "Без альбома" }}\u0000${albumArtist.ifEmpty { artist }}"

object Library {
    fun load(ctx: Context): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK
        )
        val tagsExtra = Build.VERSION.SDK_INT >= 30
        if (tagsExtra) {
            columns += "album_artist"
            columns += "genre"
        }
        val out = mutableListOf<Track>()
        ctx.contentResolver.query(
            collection, columns.toTypedArray(), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                out += Track(
                    id = id,
                    title = cursor.getString(1) ?: "?",
                    artist = cursor.getString(2) ?: "Unknown",
                    album = cursor.getString(3) ?: "",
                    durationMs = cursor.getLong(4),
                    uri = ContentUris.withAppendedId(collection, id),
                    year = cursor.getInt(5),
                    trackNo = cursor.getInt(6),
                    albumArtist = if (tagsExtra) cursor.getString(7) ?: "" else "",
                    genre = if (tagsExtra) cursor.getString(8) ?: "" else ""
                )
            }
        }
        return out
    }
}
