package com.example.usbplayer

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer

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

    private var conn: UsbDeviceConnection? = null
    private var acIf = 0
    private var clockId = 0
    private var zeroAlt: UsbInterface? = null
    private var bestAlt: UsbInterface? = null
    private var dataEp = 0
    private var fbEp = 0
    private var maxPkt = 0
    private var rates: List<Int> = emptyList()
    private var curRate = 0
    @Volatile private var playingHint = false

    private external fun nativeStart(fd: Int, dataEp: Int, fbEp: Int, maxPkt: Int, rate: Int): Boolean
    private external fun nativeStop()
    private external fun nativeWrite(buf: ByteBuffer, off: Int, len: Int, enc: Int, ch: Int): Int
    private external fun nativeSetPlaying(p: Boolean)
    private external fun nativeFlush()
    private external fun nativeInfo(): String
    private external fun nativeBuffered(): Int

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

    private fun fmt(r: Int) = if (r % 1000 == 0) "${r / 1000}" else "%.1f".format(r / 1000.0)

    @Synchronized fun open(ctx: Context, d: UsbDevice): Boolean {
        close()
        val um = ctx.getSystemService(UsbManager::class.java)
        val c = um.openDevice(d) ?: return fail("не удалось открыть устройство")
        val raw = c.rawDescriptors
        clockId = clockId(raw)
        var ac: UsbInterface? = null; var zero: UsbInterface? = null; var best: UsbInterface? = null
        var bestPkt = 0; var bestFb = 0
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
                if ((out.maxPacketSize and 0x7FF) > bestPkt) {
                    best = f; bestPkt = out.maxPacketSize and 0x7FF; dataEp = out.address; bestFb = fb?.address ?: 0
                }
            }
        }
        if (ac == null || best == null || clockId == 0) { c.close(); return fail("не найден аудио-интерфейс или clock source") }
        val zi: UsbInterface = zero ?: best
        acIf = ac.id; zeroAlt = zi; bestAlt = best; maxPkt = bestPkt; fbEp = bestFb
        if (!c.claimInterface(ac, true) || !c.claimInterface(zi, true)) { c.close(); return fail("не удалось захватить интерфейсы") }
        conn = c
        rates = queryRates(c)
        name = d.productName ?: "USB DAC"
        generation++
        ready = true
        status = "Прямой USB: $name · ${rates.joinToString(" ") { fmt(it) }} кГц"
        return true
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
    @Synchronized fun prepare(rate: Int): Boolean {
        val c = conn ?: return false
        if (active && rate == curRate) return true
        if (rate !in rates) { status = "ЦАП не поддерживает $rate Гц в прямом режиме"; return false }
        if (active && rate != curRate) waitDrain()   // дослушать хвост предыдущего трека
        val za = zeroAlt ?: return false
        val ba = bestAlt ?: return false
        stopStream()
        c.setInterface(za)
        val d = ByteArray(4) { ((rate shr (8 * it)) and 255).toByte() }
        if (c.controlTransfer(0x21, 0x01, 0x0100, (clockId shl 8) or acIf, d, 4, 500) != 4) {
            status = "не удалось задать частоту $rate Гц"; return false
        }
        try { Thread.sleep(80) } catch (e: InterruptedException) {}
        if (!c.setInterface(ba)) { status = "не удалось включить альтернативную настройку"; return false }
        if (!nativeStart(c.fileDescriptor, dataEp, fbEp, maxPkt, rate)) { status = "не удалось запустить поток"; return false }
        nativeSetPlaying(playingHint)
        curRate = rate; active = true
        status = "Прямой USB: $name · ${fmt(rate)} кГц · 32 бит"
        return true
    }

    private fun waitDrain() {
        val t0 = System.currentTimeMillis()
        while (playingHint && nativeBuffered() > 0 && System.currentTimeMillis() - t0 < 2500) {
            try { Thread.sleep(10) } catch (e: InterruptedException) { break }
        }
    }

    private fun stopStream() {
        if (active) { nativeStop(); zeroAlt?.let { z -> conn?.setInterface(z) }; active = false; curRate = 0 }
    }

    fun write(buf: ByteBuffer, off: Int, len: Int, enc: Int, ch: Int): Int =
        if (!ready || !active) -1 else nativeWrite(buf, off, len, enc, ch)

    fun setPlaying(p: Boolean) { playingHint = p; if (active) nativeSetPlaying(p) }
    fun flush() { if (active) nativeFlush() }
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
        conn = null; generation++; ready = false; status = ""
    }

    private fun fail(msg: String): Boolean { status = "Прямой USB: $msg"; return false }
}
