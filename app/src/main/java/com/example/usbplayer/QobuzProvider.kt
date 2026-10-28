package com.example.usbplayer

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

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
        val raw = "trackgetFileUrlformat_id${formatId}intentstreamtrack_id$trackId$ts$appSecret"
        val sig = MessageDigest.getInstance("MD5").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        return get("track/getFileUrl", mapOf(
            "format_id" to formatId.toString(), "intent" to "stream",
            "track_id" to trackId, "request_ts" to ts, "request_sig" to sig
        )).getString("url")
    }
}

object Providers { val qobuz = QobuzProvider() }
