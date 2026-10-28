package com.example.usbplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object Art {
    private val cache = LruCache<String, Bitmap>(120)

    fun load(ctx: Context, t: Track, px: Int): Bitmap? {
        val key = when {
            t.provider != "local" -> "${t.provider}:${t.art}"
            t.albumId > 0 -> "album:${t.albumId}:$px"
            else -> "uri:${t.uri}:$px"
        }
        cache.get(key)?.let { return it }
        val bmp = try {
            if (t.provider == "local") {
                // встроенная обложка или cover.jpg из папки альбома (Android 10+)
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(t.uri, Size(px, px), null) else null
            } else if (t.art.isNotEmpty()) URL(t.art).openStream().use { BitmapFactory.decodeStream(it) }
            else null
        } catch (e: Exception) { null }
        bmp?.let { cache.put(key, it) }
        return bmp
    }
}

@Composable
fun Cover(t: Track?, size: Dp) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<Bitmap?>(null, t?.uri, t?.art) {
        value = if (t == null) null else withContext(Dispatchers.IO) { Art.load(ctx, t, px) }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** Доминантный цвет обложки (с упором на насыщенные пиксели) — для фона экрана плеера. */
fun dominant(b: Bitmap): androidx.compose.ui.graphics.Color {
    val s = Bitmap.createScaledBitmap(b, 16, 16, true)
    var r = 0.0; var g = 0.0; var bl = 0.0; var w = 0.0
    for (x in 0 until 16) for (y in 0 until 16) {
        val p = s.getPixel(x, y)
        val cr = (p shr 16) and 255; val cg = (p shr 8) and 255; val cb = p and 255
        val sat = (maxOf(cr, cg, cb) - minOf(cr, cg, cb)) / 255.0 + 0.05
        r += cr * sat; g += cg * sat; bl += cb * sat; w += sat
    }
    return androidx.compose.ui.graphics.Color((r / w).toInt(), (g / w).toInt(), (bl / w).toInt())
}
