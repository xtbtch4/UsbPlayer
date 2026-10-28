package com.example.usbplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Обёртка над DefaultAudioSink. Видит PCM в том виде, в каком его отдал декодер (16/24/32 бит или float):
 *  - прямой USB включён: звук уходит в драйвер, а настоящему sink отдаётся тишина (позиция, пауза и переходы работают как обычно);
 *  - иначе, если включён EQ: обрабатывается копия буфера;
 *  - иначе: буфер уходит как есть.
 */
@OptIn(UnstableApi::class)
class DirectSink(delegate: AudioSink) : ForwardingAudioSink(delegate) {
    private var enc = 0
    private var ch = 2
    private var rate = 0
    private var routing = false
    private var tried = -1
    private var pending: ByteBuffer? = null
    private var zero: ByteBuffer = ByteBuffer.allocate(1 shl 15)
    private var direct: ByteBuffer = ByteBuffer.allocateDirect(1 shl 16)

    private fun encOf(e: Int) = when (e) {
        C.ENCODING_PCM_16BIT -> 1
        C.ENCODING_PCM_24BIT -> 2
        C.ENCODING_PCM_32BIT -> 3
        C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        enc = if (inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW) encOf(inputFormat.pcmEncoding) else 0
        ch = inputFormat.channelCount
        rate = inputFormat.sampleRate
        pending = null
        Eq.filter.reset()
        routing = UsbDirect.ready && enc != 0 && UsbDirect.prepare(rate)
        tried = UsbDirect.generation
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun flush() {
        super.flush()
        pending = null
        Eq.filter.reset()
        if (routing) UsbDirect.flush()
    }

    override fun reset() {
        super.reset()
        pending = null; routing = false; tried = -1
    }

    private fun silence(n: Int): ByteBuffer {
        if (zero.capacity() < n) zero = ByteBuffer.allocate(n)
        zero.clear(); zero.limit(n)
        return zero
    }

    private fun eqCopy(src: ByteBuffer): ByteBuffer {
        val copy = ByteBuffer.allocate(src.remaining()).order(ByteOrder.nativeOrder())
        copy.put(src.duplicate()); copy.flip()
        Eq.filter.process(copy, enc, ch, rate)
        return copy
    }

    private fun pushToUsb(b: ByteBuffer): Boolean {
        val n = b.remaining()
        if (direct.capacity() < n) direct = ByteBuffer.allocateDirect(n)
        direct.clear(); direct.put(b.duplicate())
        var off = 0
        while (off < n && UsbDirect.ready) {
            val w = UsbDirect.write(direct, off, n - off, enc, ch)
            if (w < 0) { UsbDirect.markDead(); return false }
            if (w == 0) { try { Thread.sleep(2) } catch (e: InterruptedException) { return false } } else off += w
        }
        return true
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (enc == 0) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        var p = pending
        if (p == null) {
            if (!UsbDirect.ready) { routing = false; tried = -1 }
            else if (!routing && tried != UsbDirect.generation) {
                tried = UsbDirect.generation
                routing = UsbDirect.prepare(rate)
                if (routing) UsbDirect.flush()
            }
            val n = buffer.remaining()
            p = when {
                routing -> {
                    val src = if (Eq.active) eqCopy(buffer) else buffer
                    if (!pushToUsb(src)) routing = false
                    silence(n)
                }
                UsbDirect.ready -> silence(n)
                Eq.active -> eqCopy(buffer)
                else -> return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
            pending = p
        }
        val done = super.handleBuffer(p, presentationTimeUs, encodedAccessUnitCount)
        if (done) { pending = null; buffer.position(buffer.limit()) }
        return done
    }
}