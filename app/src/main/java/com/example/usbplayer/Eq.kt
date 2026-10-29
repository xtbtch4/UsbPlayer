package com.example.usbplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class Peq(val type: String, val fc: Double, val gain: Double, val q: Double)

/** Параметрический эквалайзер (PK / LSC / HSC), обрабатывает PCM-байты на месте. enc: 1=16, 2=24, 3=32, 4=float. */
class EqFilter {
    private class Chain(val rate: Int, val ch: Int, val coef: Array<DoubleArray>, val z: Array<DoubleArray>, val gain: Double)

    @Volatile private var filters: List<Peq> = emptyList()
    @Volatile private var preDb = 0.0
    @Volatile private var version = 0
    private var chain: Chain? = null
    private var chainVer = -1

    fun configure(f: List<Peq>, preampDb: Double) { filters = f; preDb = preampDb; version++ }
    fun reset() { chain = null }

    private fun build(rate: Int, ch: Int): Chain {
        val coef = filters.filter { it.fc < rate / 2.0 }.map { coeffs(it, rate) }.toTypedArray()
        return Chain(rate, ch, coef, Array(ch) { DoubleArray(coef.size * 2) }, 10.0.pow(preDb / 20.0))
    }

    private fun coeffs(f: Peq, fs: Int): DoubleArray {
        val a = 10.0.pow(f.gain / 40.0)
        val w = 2 * PI * f.fc / fs
        val c = cos(w); val alpha = sin(w) / (2 * f.q); val sa = 2 * sqrt(a) * alpha
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        when (f.type) {
            "LSC" -> {
                b0 = a * ((a + 1) - (a - 1) * c + sa); b1 = 2 * a * ((a - 1) - (a + 1) * c)
                b2 = a * ((a + 1) - (a - 1) * c - sa); a0 = (a + 1) + (a - 1) * c + sa
                a1 = -2 * ((a - 1) + (a + 1) * c); a2 = (a + 1) + (a - 1) * c - sa
            }
            "HSC" -> {
                b0 = a * ((a + 1) + (a - 1) * c + sa); b1 = -2 * a * ((a - 1) + (a + 1) * c)
                b2 = a * ((a + 1) + (a - 1) * c - sa); a0 = (a + 1) - (a - 1) * c + sa
                a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - sa
            }
            else -> {
                b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a
            }
        }
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** Обрабатывает [position, limit) на месте (порядок байт — родной, little-endian на Android). */
    fun process(buf: ByteBuffer, enc: Int, ch: Int, rate: Int) {
        if (ch < 1 || rate <= 0) return
        var c = chain
        if (c == null || chainVer != version || c.rate != rate || c.ch != ch) {
            c = build(rate, ch); chain = c; chainVer = version
        }
        buf.order(ByteOrder.nativeOrder())
        val bps = when (enc) { 1 -> 2; 2 -> 3; else -> 4 }
        val n = c.coef.size
        var i = buf.position()
        val end = buf.limit()
        var ci = 0
        while (i + bps <= end) {
            var x = when (enc) {
                1 -> buf.getShort(i) / 32768.0
                2 -> ((buf.get(i).toInt() and 255) or ((buf.get(i + 1).toInt() and 255) shl 8) or (buf.get(i + 2).toInt() shl 16)) / 8388608.0
                3 -> buf.getInt(i) / 2147483648.0
                else -> buf.getFloat(i).toDouble()
            } * c.gain
            val z = c.z[ci]
            for (s in 0 until n) {
                val k = c.coef[s]
                val y = k[0] * x + z[2 * s]
                z[2 * s] = k[1] * x - k[3] * y + z[2 * s + 1]
                z[2 * s + 1] = k[2] * x - k[4] * y
                x = y
            }
            when (enc) {
                1 -> buf.putShort(i, (x * 32768.0).coerceIn(-32768.0, 32767.0).roundToInt().toShort())
                2 -> {
                    val v = (x * 8388608.0).coerceIn(-8388608.0, 8388607.0).roundToInt()
                    buf.put(i, v.toByte()); buf.put(i + 1, (v shr 8).toByte()); buf.put(i + 2, (v shr 16).toByte())
                }
                3 -> buf.putInt(i, (x * 2147483648.0).coerceIn(-2147483648.0, 2147483647.0).roundToLong().toInt())
                else -> buf.putFloat(i, x.toFloat())
            }
            i += bps
            if (++ci == ch) ci = 0
        }
    }
}

object Eq {
    val filter = EqFilter()
    var enabled by mutableStateOf(false)
    var name by mutableStateOf<String?>(null)
    var filters by mutableStateOf<List<Peq>>(emptyList())
    var preamp by mutableDoubleStateOf(0.0)
    /** true, пока играет DSD (DoP): обработка запрещена */
    @Volatile var dsd = false

    val active get() = enabled && filters.isNotEmpty() && !dsd

    fun apply() = filter.configure(filters, preamp)

    private val reF = Regex(
        """Filter\s*\d*:\s*ON\s+(PK|LSC|LS|HSC|HS)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB(?:\s+Q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE)
    private val reP = Regex("""Preamp:\s*(-?[\d.]+)\s*dB""", RegexOption.IGNORE_CASE)

    /** Разбор файла AutoEQ «ParametricEQ.txt». */
    fun load(text: String, profile: String): Boolean {
        val f = reF.findAll(text).map { m ->
            val t = m.groupValues[1].uppercase().let { if (it == "LS") "LSC" else if (it == "HS") "HSC" else it }
            Peq(t, m.groupValues[2].toDouble(), m.groupValues[3].toDouble(), m.groupValues[4].toDoubleOrNull() ?: 0.707)
        }.toList()
        if (f.isEmpty()) return false
        filters = f
        preamp = reP.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        name = profile; enabled = true
        apply()
        return true
    }
}
