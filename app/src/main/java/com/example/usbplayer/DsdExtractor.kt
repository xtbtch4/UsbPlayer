package com.example.usbplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ConstantBitrateSeekMap
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DSF / DFF (DSDIFF, без DST) -> DoP (DSD over PCM): 24 бит = маркер(0x05/0xFA) | 16 бит DSD.
 * Выдаёт PCM 24-bit с частотой DSD/16 (DSD64 -> 176.4 кГц). Нужен bit-perfect вывод на ЦАП.
 */
@OptIn(UnstableApi::class)
class DsdExtractor : Extractor {
    private lateinit var out: ExtractorOutput
    private lateinit var track: TrackOutput
    private var started = false
    private var isDsf = false
    private var ch = 2
    private var dsdRate = 2_822_400
    private var lsbFirst = false
    private var blockSize = 4096
    private var dataStart = 0L
    private var dataEnd = 0L
    private var pos = 0L
    private var frames = 0L
    private val rev = ByteArray(256) { i ->
        var v = i; var r = 0
        repeat(8) { r = (r shl 1) or (v and 1); v = v shr 1 }
        r.toByte()
    }

    override fun sniff(input: ExtractorInput): Boolean = try {
        val b = ByteArray(4); input.peekFully(b, 0, 4)
        String(b, Charsets.US_ASCII).let { it == "DSD " || it == "FRM8" }
    } catch (e: Exception) { false }

    override fun init(output: ExtractorOutput) { out = output }

    private fun parseHeader(input: ExtractorInput) {
        val magic = ByteArray(4); input.peekFully(magic, 0, 4)
        isDsf = String(magic, Charsets.US_ASCII) == "DSD "
        if (isDsf) {
            val h = ByteArray(92); input.readFully(h, 0, 92)
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            ch = b.getInt(52); dsdRate = b.getInt(56)
            lsbFirst = b.getInt(60) == 1; blockSize = b.getInt(72)
            dataStart = 92; dataEnd = 92 + b.getLong(84) - 12
            pos = 92
        } else {
            fun rd(n: Int): ByteBuffer { val a = ByteArray(n); input.readFully(a, 0, n); pos += n; return ByteBuffer.wrap(a) }
            fun skip(n: Long) { input.skipFully(n.toInt()); pos += n }
            pos = 0; rd(16)
            while (true) {
                val hd = rd(12)
                val id = String(hd.array(), 0, 4, Charsets.US_ASCII); val size = hd.getLong(4)
                when (id) {
                    "PROP" -> {
                        rd(4); var left = size - 4
                        while (left > 0) {
                            val sh = rd(12)
                            val sid = String(sh.array(), 0, 4, Charsets.US_ASCII); val ssz = sh.getLong(4)
                            val pad = ssz + (ssz and 1)
                            when (sid) {
                                "FS  " -> { dsdRate = rd(4).getInt(); skip(pad - 4) }
                                "CHNL" -> { ch = rd(2).getShort().toInt(); skip(pad - 2) }
                                "CMPR" -> {
                                    if (String(rd(4).array(), Charsets.US_ASCII) != "DSD ")
                                        throw ParserException.createForUnsupportedContainerFeature("DST-сжатие не поддерживается")
                                    skip(pad - 4)
                                }
                                else -> skip(pad)
                            }
                            left -= 12 + pad
                        }
                    }
                    "DSD " -> { dataStart = pos; dataEnd = pos + size; break }
                    else -> skip(size + (size and 1))
                }
            }
        }
        if (input.length != C.LENGTH_UNSET.toLong() && dataEnd > input.length) dataEnd = input.length
        track = out.track(0, C.TRACK_TYPE_AUDIO)
        track.format(
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(ch)
                .setSampleRate(dsdRate / 16).setPcmEncoding(C.ENCODING_PCM_24BIT)
                .setLabel("DSD${dsdRate / 44100}").setMaxInputSize(blockSize * ch * 2).build()
        )
        out.seekMap(ConstantBitrateSeekMap(dataEnd, dataStart, dsdRate * ch, if (isDsf) blockSize * ch else 2 * ch))
        out.endTracks()
    }

    override fun read(input: ExtractorInput, holder: PositionHolder): Int {
        if (!started) { parseHeader(input); started = true }
        val remaining = dataEnd - pos
        val per: Int
        if (isDsf) {
            if (remaining < blockSize.toLong() * ch) return Extractor.RESULT_END_OF_INPUT
            per = blockSize
        } else {
            per = (minOf(4096L, remaining / ch).toInt()) and 1.inv()
            if (per < 2) return Extractor.RESULT_END_OF_INPUT
        }
        val raw = ByteArray(per * ch)
        input.readFully(raw, 0, raw.size)
        pos += raw.size
        val n = per / 2
        val o = ByteArray(n * ch * 3)
        var w = 0
        for (f in 0 until n) {
            val marker = if (((frames + f) and 1L) == 0L) 0x05 else 0xFA
            for (c in 0 until ch) {
                val i1 = if (isDsf) c * per + 2 * f else (2 * f) * ch + c
                val i2 = if (isDsf) i1 + 1 else (2 * f + 1) * ch + c
                var b1 = raw[i1]; var b2 = raw[i2]
                if (lsbFirst) { b1 = rev[b1.toInt() and 0xFF]; b2 = rev[b2.toInt() and 0xFF] }
                o[w++] = b2; o[w++] = b1; o[w++] = marker.toByte()   // 24-bit little-endian
            }
        }
        track.sampleData(ParsableByteArray(o), o.size)
        track.sampleMetadata(frames * 1_000_000L / (dsdRate / 16), C.BUFFER_FLAG_KEY_FRAME, o.size, 0, null)
        frames += n
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        if (started) { pos = position.coerceAtLeast(dataStart); frames = timeUs * (dsdRate / 16) / 1_000_000L }
        else { pos = 0; frames = 0 }
    }

    override fun release() {}
}
