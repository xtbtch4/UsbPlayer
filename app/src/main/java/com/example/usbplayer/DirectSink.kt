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
 * Обёртка над DefaultAudioSink. Видит PCM в том виде, в каком его отдал декодер (16/24/32 бит или float).
 *
 * Прямой USB включён и поток запущен: обёртка сама служит аудио-выводом. Звук уходит в драйвер, часы плеера
 * (позиция, конец потока, пауза) считаются по тому, сколько кадров реально забрал ЦАП, а подача данных
 * притормаживается, когда в буфере уже есть ~0,6 с запаса. Так ЦАП — ведущие часы, и рассинхрона с часами телефона нет.
 *
 * Иначе: если включён EQ, обрабатывается копия буфера; иначе буфер уходит в DefaultAudioSink как есть.
 */
@OptIn(UnstableApi::class)
class DirectSink(delegate: AudioSink) : ForwardingAudioSink(delegate) {
    private var enc = 0
    private var ch = 2
    private var rate = 0
    private var routing = false
    private var tried = -1
    private var held: ByteBuffer? = null           // исходный буфер, который delegate принял не целиком
    private var pending: ByteBuffer? = null        // буфер, который сейчас «доедает» delegate (тишина или копия после EQ)
    private var zero: ByteBuffer = ByteBuffer.allocate(1 shl 15).order(ByteOrder.LITTLE_ENDIAN)
    private var direct: ByteBuffer = ByteBuffer.allocateDirect(1 shl 16)

    // собственные часы прямого режима
    private var startSet = false
    private var startPts = 0L
    private var framesPushed = 0L
    private var needResync = false
    private var ended = false
    private var consumedBase = 0L

    private fun encOf(e: Int) = when (e) {
        C.ENCODING_PCM_16BIT -> 1
        C.ENCODING_PCM_24BIT -> 2
        C.ENCODING_PCM_32BIT -> 3
        C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private fun srcBytesPerSample() = when (enc) { 1 -> 2; 2 -> 3; else -> 4 }

    /** Разрядность для выбора альтернативной настройки ЦАП: целочисленный вывод декодера точен, для float берём формат дорожки. */
    private fun bits() = when (enc) {
        1 -> 16
        2 -> 24
        else -> UsbDirect.sourceBits.takeIf { it > 0 } ?: 32
    }

    private fun resetClock() {
        startSet = false; startPts = 0L; framesPushed = 0L; needResync = false; ended = false; consumedBase = 0L
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        enc = if (inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW) encOf(inputFormat.pcmEncoding) else 0
        ch = inputFormat.channelCount
        rate = inputFormat.sampleRate
        pending = null; held = null
        Eq.filter.reset()
        routing = UsbDirect.ready && enc != 0 && UsbDirect.prepare(rate, bits())
        tried = UsbDirect.generation
        resetClock()
        if (routing) UsbDirect.setEos(false)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)   // AudioTrack создаётся лениво, в прямом режиме его не будет
    }

    override fun flush() {
        super.flush()
        pending = null; held = null
        Eq.filter.reset()
        resetClock()
        if (routing) {
            UsbDirect.flush()
            consumedBase = UsbDirect.consumedBytes()
            UsbDirect.setEos(false)
        }
    }

    override fun reset() {
        super.reset()
        pending = null; held = null; routing = false; tried = -1
        resetClock()
    }

    override fun play() { super.play(); UsbDirect.setPlaying(true) }
    override fun pause() { super.pause(); UsbDirect.setPlaying(false) }
    override fun handleDiscontinuity() { super.handleDiscontinuity(); needResync = true }

    override fun playToEndOfStream() {
        if (routing) { ended = true; UsbDirect.setEos(true) } else super.playToEndOfStream()
    }

    override fun isEnded(): Boolean = if (routing) ended && UsbDirect.buffered() == 0 else super.isEnded()

    override fun hasPendingData(): Boolean = if (routing) UsbDirect.buffered() > 0 else super.hasPendingData()

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!routing) return super.getCurrentPositionUs(sourceEnded)
        if (!startSet || rate <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        val frames = ((UsbDirect.consumedBytes() - consumedBase) / 8).coerceAtLeast(0L)
        return startPts + frames * 1_000_000L / rate
    }

    private fun silence(n: Int): ByteBuffer {
        if (zero.capacity() < n) zero = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        zero.clear(); zero.limit(n)
        return zero
    }

    private fun eqCopy(src: ByteBuffer): ByteBuffer {
        val copy = ByteBuffer.allocate(src.remaining()).order(ByteOrder.LITTLE_ENDIAN)
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

    private fun handleDirect(buffer: ByteBuffer, ptUs: Long): Boolean {
        if (!buffer.hasRemaining()) return true
        // ЦАП задаёт темп: не опережаем его больше чем на ~0,6 с, ExoPlayer повторит вызов позже
        if (UsbDirect.buffered() / 8L > rate * 6L / 10L) return false
        if (!startSet) { startPts = ptUs; startSet = true; framesPushed = 0L; needResync = false }
        else if (needResync) {
            startPts += ptUs - (startPts + framesPushed * 1_000_000L / rate)
            needResync = false
        }
        val n = buffer.remaining()
        val src = if (Eq.active) eqCopy(buffer) else buffer
        if (!pushToUsb(src)) { routing = false; return false }   // устройство пропало: дальше обычный путь
        framesPushed += n / (srcBytesPerSample() * ch)
        buffer.position(buffer.limit())
        return true
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (enc == 0) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (pending == null && held == null) {
            if (!UsbDirect.ready) { routing = false; tried = -1 }
            else if (!routing && tried != UsbDirect.generation) {
                // драйвер включили, когда трек уже играл: запускаем поток на лету
                tried = UsbDirect.generation
                routing = UsbDirect.prepare(rate, bits())
                if (routing) { resetClock(); UsbDirect.flush(); consumedBase = UsbDirect.consumedBytes(); UsbDirect.setEos(false) }
            }
        }
        if (routing && UsbDirect.ready) return handleDirect(buffer, presentationTimeUs)

        if (held != null) {
            if (held === buffer) {
                val done = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                if (done) held = null
                return done
            }
            held = null
        }
        var p = pending
        if (p == null) {   // новый входной буфер
            val n = buffer.remaining()
            p = when {
                UsbDirect.ready -> silence(n)     // драйвер включён, но поток не стартовал (частота не поддерживается): молчим
                Eq.active -> eqCopy(buffer)
                else -> {
                    val done = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                    held = if (done) null else buffer
                    return done
                }
            }
            pending = p
        }
        val done = super.handleBuffer(p, presentationTimeUs, encodedAccessUnitCount)
        if (done) { pending = null; buffer.position(buffer.limit()) }
        return done
    }
}
