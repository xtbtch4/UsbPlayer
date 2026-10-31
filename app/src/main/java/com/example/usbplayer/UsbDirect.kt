package com.example.usbplayer

import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Прямой драйвер USB Audio Class 2 (async + feedback). Делает: захват интерфейсов, выбор частоты через
 * clock source, выбор альтернативной настройки (максимальная разрядность) и запуск нативного потока.
 */
object UsbDirect {
    init { System.loadLibrary("usbaudio") }

    var ready by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    @Volatile var active = false; private set
    @Volatile var name = ""; private set
    /** Растёт при каждом открытии/закрытии устройства: по нему конвейер понимает, что пора (пере)запустить поток. */
    @Volatile var generation = 0; private set

    /** Аппаратная громкость ЦАП (Feature Unit). Системная громкость в прямом режиме не действует. */
    var hasVolume by mutableStateOf(false); private set
    var volume by mutableStateOf(0.3f); private set
    private var fuId = 0
    private var volChannels: List<Int> = emptyList()
    private var volMin = -90 * 256
    private var volMax = 0
    private var volRes = 256
    private var prefs: SharedPreferences? = null
    private val ctlLock = Any()
    private val vexec = Executors.newSingleThreadExecutor()

    private var conn: UsbDeviceConnection? = null
    private var acIf = 0
    private var clockId = 0
    private var zeroAlt: UsbInterface? = null
    private class Alt(val iface: UsbInterface, val bits: Int, val sub: Int, val maxPkt: Int, val dataEp: Int, val fbEp: Int)
    private var alts: List<Alt> = emptyList()
    private var curAlt: Alt? = null
    /** Разрядность исходного файла (из формата дорожки), 0 — неизвестна. Выставляется при смене трека. */
    @Volatile var sourceBits = 0
    private var rates: List<Int> = emptyList()
    private var curRate = 0
    @Volatile private var playingHint = false

    private external fun nativeStart(fd: Int, dataEp: Int, fbEp: Int, maxPkt: Int, rate: Int, subslot: Int): Boolean
    private external fun nativeStop()
    private external fun nativeWrite(buf: ByteBuffer, off: Int, len: Int, enc: Int, ch: Int): Int
    private external fun nativeSetPlaying(p: Boolean)
    private external fun nativeFlush()
    private external fun nativeInfo(): String
    private external fun nativeBuffered(): Int
    private external fun nativeConsumedBytes(): Long
    private external fun nativeSetEos(e: Boolean)

    private fun clockId(raw: ByteArray): Int {
        var i = 0; var cls = 0; var sub = 0
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 255
            if (len < 2 || i + len > raw.size) break
            val t = raw[i + 1].toInt() and 255
            if (t == 4 && len >= 9) { cls = raw[i + 5].toInt() and 255; sub = raw[i + 6].toInt() and 255 }
            if (t == 0x24 && cls == 1 && sub == 1 && len >= 4 && (raw[i + 2].toInt() and 255) == 0x0A)
                return raw[i + 3].toInt() and 255
            i += len
        }
        return 0
    }

    /** (номер интерфейса, alt) -> (байт на отсчёт, разрядность) из UAC2 Format Type. */
    private fun formatMap(raw: ByteArray): Map<Int, Pair<Int, Int>> {
        val m = HashMap<Int, Pair<Int, Int>>()
        var i = 0; var cls = 0; var sub = 0; var itf = 0; var alt = 0
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 255
            if (len < 2 || i + len > raw.size) break
            val t = raw[i + 1].toInt() and 255
            if (t == 4 && len >= 9) {
                itf = raw[i + 2].toInt() and 255; alt = raw[i + 3].toInt() and 255
                cls = raw[i + 5].toInt() and 255; sub = raw[i + 6].toInt() and 255
            }
            if (t == 0x24 && cls == 1 && sub == 2 && len >= 6 && (raw[i + 2].toInt() and 255) == 2)
                m[(itf shl 8) or alt] = (raw[i + 4].toInt() and 255) to (raw[i + 5].toInt() and 255)
            i += len
        }
        return m
    }

    /** Альтернативная настройка под разрядность источника: точное совпадение, иначе ближайшая выше, иначе самая широкая. */
    private fun pickAlt(bits: Int): Alt? =
        alts.firstOrNull { it.bits == bits } ?: alts.filter { it.bits >= bits }.minByOrNull { it.bits } ?: alts.maxByOrNull { it.bits }

    private fun fmt(r: Int) = if (r % 1000 == 0) "${r / 1000}" else "%.1f".format(r / 1000.0)

    @Synchronized fun open(ctx: Context, d: UsbDevice): Boolean {
        close()
        val um = ctx.getSystemService(UsbManager::class.java)
        val c = um.openDevice(d) ?: return fail("не удалось открыть устройство")
        val raw = c.rawDescriptors
        clockId = clockId(raw)
        val fmts = formatMap(raw)
        var ac: UsbInterface? = null; var zero: UsbInterface? = null
        val list = mutableListOf<Alt>()
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
            if (f.interfaceSubclass == 1) ac = f
            if (f.interfaceSubclass == 2) {
                if (f.endpointCount == 0) { zero = f; continue }
                val eps = (0 until f.endpointCount).map { f.getEndpoint(it) }
                val out = eps.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && it.direction == UsbConstants.USB_DIR_OUT }
                    ?: continue
                val fb = eps.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && it.direction == UsbConstants.USB_DIR_IN }
                val fm = fmts[(f.id shl 8) or f.alternateSetting] ?: continue
                list += Alt(f, fm.second, fm.first, out.maxPacketSize and 0x7FF, out.address, fb?.address ?: 0)
            }
        }
        if (ac == null || list.isEmpty() || clockId == 0) { c.close(); return fail("не найден аудио-интерфейс или clock source") }
        val zi: UsbInterface = zero ?: list[0].iface
        acIf = ac.id; zeroAlt = zi; alts = list
        if (!c.claimInterface(ac, true) || !c.claimInterface(zi, true)) { c.close(); return fail("не удалось захватить интерфейсы") }
        conn = c
        rates = queryRates(c)
        initVolume(c, raw, ctx)
        name = d.productName ?: "USB DAC"
        generation++
        ready = true
        status = "Прямой USB: $name · ${rates.joinToString(" ") { fmt(it) }} кГц"
        return true
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun s16(b: ByteArray, o: Int) = u16(b, o).toShort().toInt()

    /** Ищет Feature Unit с регулятором громкости (единицы 1/256 дБ), снимает mute и выставляет сохранённый уровень. */
    private fun initVolume(c: UsbDeviceConnection, raw: ByteArray, ctx: Context) {
        prefs = ctx.getSharedPreferences("usb", Context.MODE_PRIVATE)
        volume = prefs?.getFloat("volume", 0.3f) ?: 0.3f   // по умолчанию тихо: прямой режим не слушает системную громкость
        hasVolume = false
        var i = 0; var cls = 0; var sub = 0
        var unit = 0; var master = 0L; val chans = mutableListOf<Long>()
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 255
            if (len < 2 || i + len > raw.size) break
            val t = raw[i + 1].toInt() and 255
            if (t == 4 && len >= 9) { cls = raw[i + 5].toInt() and 255; sub = raw[i + 6].toInt() and 255 }
            if (t == 0x24 && cls == 1 && sub == 1 && len >= 10 && (raw[i + 2].toInt() and 255) == 0x06 && unit == 0) {
                unit = raw[i + 3].toInt() and 255
                for (k in 0 until (len - 6) / 4) {
                    var v = 0L
                    for (b in 0..3) v = v or ((raw[i + 5 + 4 * k + b].toLong() and 255) shl (8 * b))
                    if (k == 0) master = v else chans += v
                }
            }
            i += len
        }
        if (unit == 0) return
        fuId = unit
        val masterVol = ((master shr 2) and 3L) != 0L
        volChannels = if (masterVol) listOf(0)
        else chans.indices.filter { ((chans[it] shr 2) and 3L) != 0L }.map { it + 1 }
        if (volChannels.isEmpty()) return
        val idx = (fuId shl 8) or acIf
        synchronized(ctlLock) {
            if ((master and 3L) != 0L)   // снять mute
                c.controlTransfer(0x21, 0x01, (0x01 shl 8) or 0, idx, byteArrayOf(0), 1, 300)
            val hdr = ByteArray(2)
            if (c.controlTransfer(0xA1, 0x02, (0x02 shl 8) or volChannels[0], idx, hdr, 2, 300) == 2) {
                val n = u16(hdr, 0)
                if (n >= 1) {
                    val buf = ByteArray(2 + 6 * n)
                    if (c.controlTransfer(0xA1, 0x02, (0x02 shl 8) or volChannels[0], idx, buf, buf.size, 300) == buf.size) {
                        volMin = (0 until n).minOf { s16(buf, 2 + 6 * it) }
                        volMax = (0 until n).maxOf { s16(buf, 4 + 6 * it) }
                        volRes = u16(buf, 6).coerceAtLeast(1)
                    }
                }
            }
        }
        hasVolume = true
        applyVolume()
    }

    private fun applyVolume() {
        val c = conn ?: return
        if (!hasVolume) return
        val spanDb = (volMax - volMin) / 256.0
        val useDb = minOf(60.0, spanDb)
        var tgt = if (volume <= 0.001f) volMin else (volMax - (1 - volume) * useDb * 256).toInt()
        tgt = tgt.coerceIn(volMin, volMax)
        if (volRes > 1) tgt = volMin + ((tgt - volMin) / volRes) * volRes
        val data = byteArrayOf(tgt.toByte(), (tgt shr 8).toByte())
        synchronized(ctlLock) {
            for (ch in volChannels)
                c.controlTransfer(0x21, 0x01, (0x02 shl 8) or ch, (fuId shl 8) or acIf, data, 2, 200)
        }
    }

    /** Громкость 0..1 (шкала линейна по дБ в пределах 60 дБ от максимума). */
    fun changeVolume(f: Float) {
        volume = f.coerceIn(0f, 1f)
        prefs?.edit()?.putFloat("volume", volume)?.apply()
        vexec.execute { applyVolume() }
    }

    private fun queryRates(c: UsbDeviceConnection): List<Int> {
        val std = listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)
        val idx = (clockId shl 8) or acIf
        val hdr = ByteArray(2)
        if (c.controlTransfer(0xA1, 0x02, 0x0100, idx, hdr, 2, 500) != 2) return std
        val n = (hdr[0].toInt() and 255) or ((hdr[1].toInt() and 255) shl 8)
        val buf = ByteArray(2 + 12 * n)
        if (n == 0 || c.controlTransfer(0xA1, 0x02, 0x0100, idx, buf, buf.size, 500) != buf.size) return std
        fun u32(o: Int) = (buf[o].toInt() and 255) or ((buf[o + 1].toInt() and 255) shl 8) or
            ((buf[o + 2].toInt() and 255) shl 16) or ((buf[o + 3].toInt() and 255) shl 24)
        val out = sortedSetOf<Int>()
        for (k in 0 until n) {
            val mn = u32(2 + 12 * k); val mx = u32(6 + 12 * k); val res = u32(10 + 12 * k)
            if (mn == mx) out += mn
            else std.filter { it in mn..mx && (res == 0 || (it - mn) % res == 0) }.forEach { out += it }
        }
        return out.toList().ifEmpty { std }
    }

    /** Переключает ЦАП на частоту трека и (пере)запускает поток. Вызывается из аудио-конвейера. */
    @Synchronized fun prepare(rate: Int, srcBits: Int): Boolean {
        val c = conn ?: return false
        val alt = pickAlt(srcBits) ?: return false
        if (active && rate == curRate && alt === curAlt) return true
        if (rate !in rates) { status = "ЦАП не поддерживает $rate Гц в прямом режиме"; return false }
        if (active) waitDrain()   // дослушать хвост предыдущего трека
        val za = zeroAlt ?: return false
        stopStream()
        c.setInterface(za)
        val d = ByteArray(4) { ((rate shr (8 * it)) and 255).toByte() }
        val rateOk = synchronized(ctlLock) { c.controlTransfer(0x21, 0x01, 0x0100, (clockId shl 8) or acIf, d, 4, 500) == 4 }
        if (!rateOk) {
            status = "не удалось задать частоту $rate Гц"; return false
        }
        try { Thread.sleep(80) } catch (e: InterruptedException) {}
        if (!c.setInterface(alt.iface)) { status = "не удалось включить альтернативную настройку"; return false }
        if (!nativeStart(c.fileDescriptor, alt.dataEp, alt.fbEp, alt.maxPkt, rate, alt.sub)) { status = "не удалось запустить поток"; return false }
        nativeSetPlaying(playingHint)
        curRate = rate; curAlt = alt; active = true
        status = "Прямой USB: $name · ${fmt(rate)} кГц · ${alt.bits} бит"
        return true
    }

    private fun waitDrain() {
        val t0 = System.currentTimeMillis()
        while (playingHint && nativeBuffered() > 0 && System.currentTimeMillis() - t0 < 2500) {
            try { Thread.sleep(10) } catch (e: InterruptedException) { break }
        }
    }

    private fun stopStream() {
        if (active) { nativeStop(); zeroAlt?.let { z -> conn?.setInterface(z) }; active = false; curRate = 0; curAlt = null }
    }

    fun write(buf: ByteBuffer, off: Int, len: Int, enc: Int, ch: Int): Int =
        if (!ready || !active) -1 else nativeWrite(buf, off, len, enc, ch)

    fun setPlaying(p: Boolean) { playingHint = p; if (active) nativeSetPlaying(p) }
    fun flush() { if (active) nativeFlush() }
    /** Байт, уже отправленных в ЦАП с момента запуска потока (кольцо хранит кадры по 8 байт). */
    fun consumedBytes(): Long = if (active) nativeConsumedBytes() else 0L
    fun buffered(): Int = if (active) nativeBuffered() else 0
    fun setEos(e: Boolean) { if (active) nativeSetEos(e) }
    fun info(): String = if (!ready) "" else if (!active) "поток не запущен, ждёт данные трека" else nativeInfo()

    @Synchronized fun markDead() {
        stopStream(); generation++; ready = false; status = "ЦАП отключён"
    }

    @Synchronized fun close() {
        stopStream()
        conn?.let { c ->
            zeroAlt?.let { c.releaseInterface(it) }
            c.close()
        }
        conn = null; generation++; ready = false; hasVolume = false; status = ""
    }

    private fun fail(msg: String): Boolean { status = "Прямой USB: $msg"; return false }
}
