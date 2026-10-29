package com.example.usbplayer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class QAlbum(val id: String, val title: String, val artist: String, val art: String, val year: String, val quality: String, val artistId: Long = 0) {
    /** Заглушка-трек, чтобы переиспользовать загрузку обложек. */
    fun asTrack() = Track(0, title, artist, title, 0, Uri.EMPTY, "qobuz", art = art)
}

/**
 * Qobuz REST API v0.2. Нужны ваши app_id / app_secret и user_auth_token того же приложения
 * (токен привязан к app_id, с чужим app_id сервер ответит 401).
 */
class QobuzProvider {
    private val base = "https://www.qobuz.com/api.json/0.2/"
    @Volatile var appId = Credentials.QOBUZ_APP_ID
    @Volatile var appSecret = Credentials.QOBUZ_APP_SECRET
    @Volatile var token: String? = null
    /** 5 = MP3 320, 6 = FLAC 16/44.1, 7 = FLAC 24/≤96, 27 = FLAC 24/≤192 */
    @Volatile var formatId = 27
    val configured get() = appId.isNotEmpty() && appSecret.isNotEmpty()

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("qobuz", Context.MODE_PRIVATE)
        p.getString("app_id", null)?.let { appId = it }
        p.getString("app_secret", null)?.let { appSecret = it }
        token = p.getString("token", null)
    }

    private fun save(ctx: Context) = ctx.getSharedPreferences("qobuz", Context.MODE_PRIVATE).edit()
        .putString("app_id", appId).putString("app_secret", appSecret).putString("token", token).apply()

    fun logout(ctx: Context) {
        token = null
        ctx.getSharedPreferences("qobuz", Context.MODE_PRIVATE).edit().remove("token").apply()
    }

    private fun get(path: String, params: Map<String, String>): JSONObject {
        val q = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val c = URL("$base$path?$q").openConnection() as HttpURLConnection
        c.setRequestProperty("X-App-Id", appId)
        token?.let { c.setRequestProperty("X-User-Auth-Token", it) }
        try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            if (c.responseCode !in 200..299) throw IOException("Qobuz ${c.responseCode}: $body")
            return JSONObject(body)
        } finally { c.disconnect() }
    }

    /** Вход по готовому user_auth_token: проверяем запросом, требующим авторизации, и запоминаем. */
    fun signInWithToken(ctx: Context, id: String, secret: String, tok: String) {
        require(id.isNotEmpty() && secret.isNotEmpty() && tok.isNotEmpty()) { "Заполните app_id, app_secret и токен" }
        appId = id; appSecret = secret; token = tok
        try {
            get("favorite/getUserFavorites", mapOf("type" to "tracks", "limit" to "1"))
        } catch (e: IOException) {
            val m = e.message.orEmpty()
            when {
                m.startsWith("Qobuz 401") || m.startsWith("Qobuz 403") -> {
                    token = null; throw IOException("Токен или app_id не приняты: $m")
                }
                m.startsWith("Qobuz ") -> {}   // другой ответ сервера: токен проверится при первом воспроизведении
                else -> { token = null; throw e }   // сеть
            }
        }
        save(ctx)
    }

    private fun list(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }

    private fun album(a: JSONObject): QAlbum {
        val img = a.optJSONObject("image")
        val bits = a.optInt("maximum_bit_depth")
        val khz = a.optDouble("maximum_sampling_rate", 0.0)
        val quality = if (bits > 0 && khz > 0) "$bits/" + (if (khz % 1.0 == 0.0) khz.toInt().toString() else khz.toString()) else ""
        return QAlbum(
            a.optString("id"), a.optString("title"), a.optJSONObject("artist")?.optString("name") ?: "",
            img?.optString("large")?.ifEmpty { null } ?: img?.optString("small") ?: "",
            a.optString("release_date_original").take(4), quality,
            a.optJSONObject("artist")?.optLong("id") ?: 0L
        )
    }

    private fun track(t: JSONObject, albumTitle: String? = null, art: String? = null, artist: String? = null, year: Int = 0, albumArtistId: Long = 0): Track {
        val id = t.getLong("id")
        val alb = t.optJSONObject("album")
        return Track(
            id, t.optString("title"),
            t.optJSONObject("performer")?.optString("name") ?: artist ?: "",
            albumTitle ?: alb?.optString("title") ?: "",
            t.optLong("duration") * 1000,
            Uri.parse("qobuz://track/$id"), "qobuz",
            year = year,
            trackNo = t.optInt("media_number", 1) * 1000 + t.optInt("track_number"),
            art = art ?: alb?.optJSONObject("image")?.optString("small") ?: "",
            artistId = t.optJSONObject("performer")?.optLong("id", 0L)?.takeIf { it > 0 } ?: albumArtistId
        )
    }

    /** Подборки главной: new-releases, most-streamed, press-awards, best-sellers, ideal-discography, qobuzissims, album-of-the-week. */
    fun featured(type: String, limit: Int = 24): List<QAlbum> =
        list(get("album/getFeatured", mapOf("type" to type, "limit" to limit.toString()))
            .optJSONObject("albums")?.optJSONArray("items")).map(::album)

    /** Релизы исполнителя (новые сверху). */
    fun artistAlbums(artistId: Long, artistName: String): List<QAlbum> =
        list(get("artist/get", mapOf("artist_id" to artistId.toString(), "extra" to "albums", "limit" to "100"))
            .optJSONObject("albums")?.optJSONArray("items"))
            .map { album(it).let { al -> if (al.artist.isEmpty()) al.copy(artist = artistName) else al } }
            .sortedByDescending { it.year }

    fun albumTracks(id: String): List<Track> {
        val j = get("album/get", mapOf("album_id" to id))
        val a = album(j)
        return list(j.optJSONObject("tracks")?.optJSONArray("items"))
            .map { track(it, a.title, a.art, a.artist, a.year.toIntOrNull() ?: 0, a.artistId) }
    }

    fun search(query: String): List<Track> =
        list(get("track/search", mapOf("query" to query, "limit" to "50")).optJSONObject("tracks")?.optJSONArray("items")).map { track(it) }

    /** Общий поиск по каталогу: (альбомы, треки). */
    fun searchAll(query: String): Pair<List<QAlbum>, List<Track>> {
        val j = get("catalog/search", mapOf("query" to query, "limit" to "30"))
        return list(j.optJSONObject("albums")?.optJSONArray("items")).map(::album) to
            list(j.optJSONObject("tracks")?.optJSONArray("items")).map { track(it) }
    }

    private fun fileUrl(trackId: String, fmt: Int): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val raw = "trackgetFileUrlformat_id${fmt}intentstreamtrack_id$trackId$ts$appSecret"
        val sig = MessageDigest.getInstance("MD5").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        return get("track/getFileUrl", mapOf(
            "format_id" to fmt.toString(), "intent" to "stream",
            "track_id" to trackId, "request_ts" to ts, "request_sig" to sig
        )).getString("url")
    }

    /** Прямая ссылка живёт недолго, поэтому получаем её в момент загрузки трека. При отказе пробуем форматы пониже. */
    fun streamUrl(trackId: String): String {
        var first: IOException? = null
        for (f in listOf(formatId, 27, 7, 6, 5).distinct()) {
            try {
                val u = fileUrl(trackId, f)
                formatId = f
                return u
            } catch (e: IOException) {
                if (first == null) first = e
                val m = e.message.orEmpty()
                if (m.contains("Invalid Request Signature"))
                    throw IOException("Qobuz отклонил подпись запроса: app_secret не подходит к этому app_id")
                if (!(m.startsWith("Qobuz 400") || m.startsWith("Qobuz 401") || m.startsWith("Qobuz 403") || m.startsWith("Qobuz 404"))) throw e
            }
        }
        throw first ?: IOException("Qobuz: не удалось получить ссылку на трек")
    }
}

object Providers { val qobuz = QobuzProvider() }
