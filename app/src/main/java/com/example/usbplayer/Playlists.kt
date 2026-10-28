package com.example.usbplayer

import android.content.Context

/** Локальные плейлисты: имя -> список MediaStore id (SharedPreferences). */
class Playlists(ctx: Context) {
    private val p = ctx.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    fun names(): List<String> = p.all.keys.sorted()
    fun get(name: String): List<Long> =
        p.getString(name, "")!!.split(",").filter { it.isNotEmpty() }.map { it.toLong() }
    private fun put(name: String, ids: List<Long>) = p.edit().putString(name, ids.joinToString(",")).apply()
    fun create(name: String) { if (name.isNotBlank() && !p.contains(name.trim())) put(name.trim(), emptyList()) }
    fun delete(name: String) = p.edit().remove(name).apply()
    fun add(name: String, id: Long) { val l = get(name); if (id !in l) put(name, l + id) }
    fun remove(name: String, id: Long) = put(name, get(name) - id)
}
