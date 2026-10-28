package com.example.usbplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Забирает PCM и отдаёт его прямому USB-драйверу; в AudioTrack вместо звука уходит тишина того же размера,
 * поэтому ExoPlayer продолжает считать позицию, паузу и переходы как обычно.
 */
@OptIn(UnstableApi::class)
class DirectTee : BaseAudioProcessor() {
    private var enc = 0
    private var ch = 2
    private var routing = false
    private var direct: ByteBuffer = ByteBuffer.allocateDirect(1 shl 16)
    private var zeros = ByteArray(0)

    private fun encOf(e: Int) = when (e) {
        C.ENCODING_PCM_16BIT -> 1
        C.ENCODING_PCM_24BIT -> 2
        C.ENCODING_PCM_32BIT -> 3
        C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (UsbDirect.ready && encOf(f.encoding) == 0) throw AudioProcessor.UnhandledAudioFormatException(f)
        return f
    }

    // Активная конфигурация применяется на flush, поэтому ЦАП переключаем здесь, а не в onConfigure
    override fun onFlush() {
        enc = encOf(inputAudioFormat.encoding)
        ch = inputAudioFormat.channelCount
        routing = UsbDirect.ready && enc != 0 && UsbDirect.prepare(inputAudioFormat.sampleRate)
        if (routing) UsbDirect.flush()
    }

    override fun queueInput(input: ByteBuffer) {
        val n = input.remaining()
        val out = replaceOutputBuffer(n)
        if (!UsbDirect.ready) { out.put(input); out.flip(); return }
        if (routing) {
            if (direct.capacity() < n) direct = ByteBuffer.allocateDirect(n)
            direct.clear(); direct.put(input)
            var off = 0
            while (off < n) {
                val w = UsbDirect.write(direct, off, n - off, enc, ch)
                if (w < 0) { UsbDirect.markDead(); break }
                if (w == 0) { try { Thread.sleep(2) } catch (e: InterruptedException) { break } } else off += w
            }
        } else input.position(input.limit())
        if (zeros.size < n) zeros = ByteArray(n)
        out.put(zeros, 0, n); out.flip()
    }
}
