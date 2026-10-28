package com.example.usbplayer

import android.hardware.usb.UsbDevice

/** Разбор дескрипторов USB-ЦАПа (UAC1/UAC2) в читаемый отчёт — основа для будущего прямого драйвера. */
object UsbReport {
    fun build(dev: UsbDevice, raw: ByteArray): String {
        val sb = StringBuilder()
        sb.appendLine("${dev.manufacturerName} ${dev.productName}  VID %04x PID %04x".format(dev.vendorId, dev.productId))
        fun u8(o: Int) = raw[o].toInt() and 255
        fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun u24(o: Int) = u16(o) or (u8(o + 2) shl 16)
        var i = 0; var cls = 0; var sub = 0; var uac = 0
        while (i + 2 <= raw.size) {
            val len = u8(i)
            if (len < 2 || i + len > raw.size) break
            when (u8(i + 1)) {
                4 -> if (len >= 9) {
                    cls = u8(i + 5); sub = u8(i + 6)
                    sb.appendLine("IF ${u8(i + 2)} alt ${u8(i + 3)} class $cls/$sub proto ${u8(i + 7)} eps ${u8(i + 4)}")
                }
                5 -> if (len >= 7) {
                    val a = u8(i + 2); val at = u8(i + 3)
                    val kind = when (at and 3) { 1 -> "ISO"; 2 -> "BULK"; 3 -> "INT"; else -> "CTRL" }
                    val sync = when ((at shr 2) and 3) { 0 -> "none"; 1 -> "async"; 2 -> "adaptive"; else -> "sync" }
                    val usage = when ((at shr 4) and 3) { 0 -> "data"; 1 -> "FEEDBACK"; 2 -> "implicit-fb"; else -> "?" }
                    sb.appendLine("   EP 0x%02x %s %s %s %s maxPkt %d interval %d".format(
                        a, if ((a and 0x80) != 0) "IN" else "OUT", kind, sync, usage, u16(i + 4), u8(i + 6)))
                }
                0x24 -> if (cls == 1 && len >= 4) {
                    val st = u8(i + 2)
                    if (sub == 1 && st == 1 && len >= 5) { uac = u16(i + 3); sb.appendLine("   AC header bcdADC %04x".format(uac)) }
                    if (sub == 1 && st == 0x0A && len >= 5) sb.appendLine("   AC clock source id ${u8(i + 3)} attr 0x%02x".format(u8(i + 4)))
                    if (sub == 2 && st == 1) {
                        if (uac >= 0x200 && len >= 11) sb.appendLine("   AS general (UAC2) channels ${u8(i + 10)} formats 0x%08x".format(
                            u16(i + 6) or (u16(i + 8) shl 16)))
                        else if (len >= 7) sb.appendLine("   AS general (UAC1) formatTag 0x%04x".format(u16(i + 5)))
                    }
                    if (sub == 2 && st == 2) {
                        if (uac >= 0x200 && len >= 6) sb.appendLine("   AS format: subslot ${u8(i + 4)} bytes, ${u8(i + 5)} bit")
                        else if (len >= 8) {
                            val n = u8(i + 7)
                            val rates = if (n == 0) "диапазон" else (0 until n).filter { i + 8 + it * 3 + 2 < raw.size }
                                .joinToString { u24(i + 8 + it * 3).toString() }
                            sb.appendLine("   AS format: ch ${u8(i + 4)}, subframe ${u8(i + 5)} bytes, ${u8(i + 6)} bit, rates $rates")
                        }
                    }
                }
            }
            i += len
        }
        sb.appendLine("\nRAW (${raw.size} байт):")
        raw.toList().chunked(32).forEach { row -> sb.appendLine(row.joinToString("") { "%02x".format(it) }) }
        return sb.toString()
    }
}
