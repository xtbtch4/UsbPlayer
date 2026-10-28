package com.example.usbplayer

import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Официальный Qobuz REST API v0.2 (нужны app_id/app_secret от Qobuz и подписка). */
class QobuzProvider {
    private val base = "https://www.qobuz.com/api.json/0.2/"
    @Volatile var token: String? = null
    /** 5 = MP3 320, 6 = FLAC 16/44.1, 7 = FLAC 24/≤96, 27 = FLAC 24/≤192 */
    @Volatile var formatId = 27
    val configured get() = Credentials.QOBUZ_APP_ID.isNotEmpty()

    private fun get(path: String, params: Map<String, String>): JSONObject {
        val q = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val c = URL("$base$path?$q").openConnection() as HttpURLConnection
        c.setRequestProperty("X-App-Id", Credentials.QOBUZ_APP_ID)
        token?.let { c.setRequestProperty("X-User-Auth-Token", it) }
        try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            if (c.responseCode !in 200..299) throw IOException("Qobuz ${c.responseCode}: $body")
            return JSONObject(body)
        } finally { c.disconnect() }
    }

    fun login(user: String, pass: String): Boolean {
        val j = get("user/login", mapOf("username" to user, "password" to pass))
        token = j.optString("user_auth_token").ifEmpty { null }
        return token != null
    }

    fun search(query: String): List<Track> {
        val items = get("track/search", mapOf("query" to query, "limit" to "50"))
            .optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it) }.map { t ->
            val id = t.getLong("id")
            Track(
                id, t.optString("title"),
                t.optJSONObject("performer")?.optString("name") ?: "",
                t.optJSONObject("album")?.optString("title") ?: "",
                t.optLong("duration") * 1000,
                Uri.parse("qobuz://track/$id"), "qobuz",
                art = t.optJSONObject("album")?.optJSONObject("image")?.optString("small") ?: ""
            )
        }
    }

    /** Прямая ссылка живёт недолго, поэтому получаем её в момент загрузки трека. */
    fun streamUrl(trackId: String): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val raw = "trackgetFileUrlformat_id${formatId}intentstreamtrack_id$trackId$ts${Credentials.QOBUZ_APP_SECRET}"
        val sig = MessageDigest.getInstance("MD5").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        return get("track/getFileUrl", mapOf(
            "format_id" to formatId.toString(), "intent" to "stream",
            "track_id" to trackId, "request_ts" to ts, "request_sig" to sig
        )).getString("url")
    }
}

object Providers { val qobuz = QobuzProvider() }
