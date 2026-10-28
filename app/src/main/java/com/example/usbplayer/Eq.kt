package com.example.usbplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Peq(val type: String, val fc: Double, val gain: Double, val q: Double)

/** Параметрический эквалайзер (PK / LSC / HSC) как AudioProcessor Media3. */
@OptIn(UnstableApi::class)
class EqProcessor : BaseAudioProcessor() {
    private class Chain(val coef: Array<DoubleArray>, val z: Array<DoubleArray>, val gain: Double)

    @Volatile private var chain: Chain? = null
    @Volatile private var filters: List<Peq> = emptyList()
    @Volatile private var preDb = 0.0
    @Volatile private var on = false
    private var rate = 0
    private var channels = 0
    private var float = false

    fun configure(f: List<Peq>, preampDb: Double, active: Boolean) {
        filters = f; preDb = preampDb; on = active; rebuild()
    }

    private fun rebuild() {
        if (rate == 0 || channels == 0) { chain = null; return }
        val coef = filters.filter { it.fc < rate / 2.0 }.map { coeffs(it, rate) }.toTypedArray()
        chain = Chain(coef, Array(channels) { DoubleArray(coef.size * 2) }, 10.0.pow(preDb / 20.0))
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

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT && f.encoding != C.ENCODING_PCM_FLOAT)
            throw AudioProcessor.UnhandledAudioFormatException(f)
        rate = f.sampleRate; channels = f.channelCount; float = f.encoding == C.ENCODING_PCM_FLOAT
        rebuild()
        return f
    }

    override fun onFlush() = rebuild()

    override fun queueInput(input: ByteBuffer) {
        val out = replaceOutputBuffer(input.remaining())
        val ch = chain
        if (!on || ch == null) { out.put(input); out.flip(); return }
        input.order(ByteOrder.nativeOrder())
        var ci = 0
        val n = ch.coef.size
        while (input.hasRemaining()) {
            var x = (if (float) input.getFloat().toDouble() else input.getShort() / 32768.0) * ch.gain
            val z = ch.z[ci]
            for (s in 0 until n) {
                val k = ch.coef[s]
                val y = k[0] * x + z[2 * s]
                z[2 * s] = k[1] * x - k[3] * y + z[2 * s + 1]
                z[2 * s + 1] = k[2] * x - k[4] * y
                x = y
            }
            if (float) out.putFloat(x.toFloat())
            else out.putShort((x * 32768.0).coerceIn(-32768.0, 32767.0).toInt().toShort())
            if (++ci == channels) ci = 0
        }
        out.flip()
    }
}

@OptIn(UnstableApi::class)
object Eq {
    val processor = EqProcessor()
    var enabled by mutableStateOf(false)
    var name by mutableStateOf<String?>(null)
    var filters by mutableStateOf<List<Peq>>(emptyList())
    var preamp by mutableDoubleStateOf(0.0)
    /** true, пока играет DSD (DoP): обработка запрещена */
    @Volatile var dsd = false

    val active get() = enabled && filters.isNotEmpty() && !dsd

    fun apply() = processor.configure(filters, preamp, active)

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
