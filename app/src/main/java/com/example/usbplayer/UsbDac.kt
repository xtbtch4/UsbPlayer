package com.example.usbplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AppState {
    var dacName by mutableStateOf<String?>(null)
    var bitPerfectWanted by mutableStateOf(true)
    var bitPerfectActive by mutableStateOf(false)
    var formatInfo by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var bpInfo by mutableStateOf("")
    var mixerMatched by mutableStateOf(false)
    var reapply: (() -> Unit)? = null
}

object UsbDac {
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun find(ctx: Context): AudioDeviceInfo? =
        ctx.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    class BpResult(val ok: Boolean, val info: String, val matched: Boolean = false)

    private fun enc(e: Int) = when (e) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_PCM_FLOAT -> "Float"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM32"
        else -> "enc$e"
    }

    private fun rank(order: List<Int>, m: AudioMixerAttributes) = order.indexOf(m.format.encoding).let { if (it < 0) 99 else it }

    /** exact = true (DSD/DoP): допустима только точно совпадающая частота. */
    fun setBitPerfect(ctx: Context, dev: AudioDeviceInfo, wantRate: Int?, on: Boolean, exact: Boolean = false): BpResult {
        if (Build.VERSION.SDK_INT < 34) return BpResult(false, "нужен Android 14+")        val am = ctx.getSystemService(AudioManager::class.java)
        return try {
            if (!on) {
                am.clearPreferredMixerAttributes(attrs, dev)
                return BpResult(false, "выключен переключателем")
            }
            val all = am.getSupportedMixerAttributes(dev)
            val stats = "режимов ${all.size}, поведения ${all.groupingBy { it.mixerBehavior }.eachCount()}, " +
                "частоты ${all.map { it.format.sampleRate }.distinct().sorted()}"
            val bp = all.filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            if (bp.isNotEmpty()) {
                val order = listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_24BIT_PACKED)
                val pick = bp.filter { it.format.sampleRate == wantRate }.minByOrNull { rank(order, it) }
                    ?: if (exact) null else bp.maxByOrNull { it.format.sampleRate }
                if (pick == null) return BpResult(false, "нет режима $wantRate Гц; есть: " +
                    bp.joinToString { "${it.format.sampleRate}/${enc(it.format.encoding)}" })
                val ok = am.setPreferredMixerAttributes(attrs, dev, pick)
                return BpResult(ok, "${pick.format.sampleRate} Гц ${enc(pick.format.encoding)}" +
                    if (ok) "" else " — система отклонила запрос")
            }
            if (exact) return BpResult(false, "прошивка не даёт bit-perfect ($stats)")
            // Запасной путь: bit-perfect в прошивке нет, но можно задать микшеру частоту трека (без SRC)
            val stereo = all.filter { it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO }.ifEmpty { all }
            val order = listOf(AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
            val pick = stereo.filter { it.format.sampleRate == wantRate }.minByOrNull { rank(order, it) }
                ?: return BpResult(false, "прошивка не даёт bit-perfect ($stats)")
            val ok = am.setPreferredMixerAttributes(attrs, dev, pick)
            if (ok) BpResult(false, "bit-perfect в прошивке нет; микшер выставлен на ${pick.format.sampleRate} Гц " +
                "${enc(pick.format.encoding)} (без ресемплинга, громкость держите на 100%)", true)
            else BpResult(false, "bit-perfect в прошивке нет ($stats); микшер не принял ${pick.format.sampleRate}/${enc(pick.format.encoding)}")
        } catch (e: Exception) { BpResult(false, "ошибка ${e.javaClass.simpleName}: ${e.message}") }
    }
}
