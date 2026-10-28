package com.example.usbplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class Track(
    val id: Long, val title: String, val artist: String, val album: String,
    val durationMs: Long, val uri: Uri, val provider: String = "local",
    val genre: String = "", val year: Int = 0, val trackNo: Int = 0, val albumArtist: String = "",
    val albumId: Long = 0, val art: String = ""
)

fun Track.albumKey() = "${album.ifEmpty { "Без альбома" }}\u0000${albumArtist.ifEmpty { artist }}"

object Library {
    fun load(ctx: Context): List<Track> {
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.ALBUM_ID
        )
        val tagsExtra = Build.VERSION.SDK_INT >= 30
        if (tagsExtra) { cols += MediaStore.Audio.AudioColumns.ALBUM_ARTIST; cols += MediaStore.Audio.AudioColumns.GENRE }
        val out = mutableListOf<Track>()
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Track(
                    id, c.getString(1) ?: "?", c.getString(2) ?: "Unknown", c.getString(3) ?: "",
                    c.getLong(4), ContentUris.withAppendedId(m.EXTERNAL_CONTENT_URI, id),
                    year = c.getInt(5), trackNo = c.getInt(6),
                    albumId = c.getLong(7),
                    albumArtist = if (tagsExtra) c.getString(8) ?: "" else "",
                    genre = if (tagsExtra) c.getString(9) ?: "" else ""
                )
            }
        }
        return out
    }
}
