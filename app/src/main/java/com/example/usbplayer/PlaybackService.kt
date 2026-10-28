package com.example.usbplayer

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var lastRate: Int? = null

    override fun onCreate() {
        super.onCreate()
        Providers.qobuz.load(this)
        val factory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(Eq.processor))
                .build()
        }.setEnableAudioFloatOutput(true)

        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec ->
            if (spec.uri.scheme == "qobuz")
                spec.withUri(Uri.parse(Providers.qobuz.streamUrl(spec.uri.lastPathSegment!!)))
            else spec
        }
        val extractors = ExtractorsFactory {
            arrayOf<Extractor>(DsdExtractor(), *DefaultExtractorsFactory().createExtractors())
        }
        player = ExoPlayer.Builder(this, factory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val f = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                    ?.getTrackFormat(0) ?: return
                val dsd = f.label?.startsWith("DSD") == true
                lastRate = f.sampleRate.takeIf { it > 0 }
                Eq.dsd = dsd
                AppState.formatInfo = if (dsd) "${f.label} · DoP ${f.sampleRate / 1000.0} kHz"
                else "${f.sampleMimeType?.substringAfter('/')?.uppercase()} · ${f.sampleRate / 1000.0} kHz" +
                    (f.bitrate.takeIf { it > 0 }?.let { " · ${it / 1000} kbps" } ?: "")
                applyRouting()
            }
        })

        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>) = applyRouting()
            override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>) = applyRouting()
        }, null)

        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onAddMediaItems(
                s: MediaSession, c: MediaSession.ControllerInfo, items: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                items.map { it.buildUpon().setUri(it.requestMetadata.mediaUri).build() }.toMutableList()
            )
        }).build()
        AppState.reapply = ::applyRouting
        applyRouting()
    }

    private fun applyRouting() {
        Eq.apply()
        val dev = UsbDac.find(this)
        AppState.dacName = dev?.productName?.toString()
        player.setPreferredAudioDevice(dev)
        // DSD (DoP) всегда требует bit-perfect; EQ и bit-perfect взаимоисключают друг друга
        val want = Eq.dsd || (AppState.bitPerfectWanted && !Eq.active)
        val r = if (dev != null) UsbDac.setBitPerfect(this, dev, lastRate, want, exact = Eq.dsd)
        else UsbDac.BpResult(false, "USB DAC не найден")
        AppState.bitPerfectActive = r.ok
        AppState.bpInfo = r.info
        AppState.mixerMatched = r.matched
        if (Eq.dsd && !AppState.bitPerfectActive) {
            // без bit-perfect DoP превратится в шум — не играем
            player.pause()
            AppState.message = "DSD (DoP) требует USB DAC и bit-perfect (Android 14+ и ЦАП с нужной частотой)."
        } else if (AppState.message?.startsWith("DSD") == true) AppState.message = null
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        AppState.reapply = null
        session?.release(); player.release(); super.onDestroy()
    }
}
