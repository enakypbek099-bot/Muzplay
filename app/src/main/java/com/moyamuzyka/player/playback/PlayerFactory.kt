package com.moyamuzyka.player.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import com.moyamuzyka.player.audio.AudioState
import com.moyamuzyka.player.audio.DspAudioSink

/**
 * Плеер и его DSP-приёмник вместе. Создаётся через [PlayerFactory.create].
 * [applyState] передаёт в звук любое изменение настроек (EQ, бас, эффекты, лимитер и т. д.).
 */
@UnstableApi
class PlayerHolder(val player: ExoPlayer, private val sink: DspAudioSink) {

    private val handler = Handler(player.applicationLooper)

    /**
     * Применить состояние: DSP (из любого потока) и скорость воспроизведения («Скорость» из Лаборатории,
     * тон сохраняется). Команды плеера выполняются в его потоке приложения.
     */
    fun applyState(state: AudioState) {
        sink.updateState(state)
        val rate = state.lab.playbackRate()
        handler.post {
            if (player.playbackParameters.speed != rate) {
                player.playbackParameters = PlaybackParameters(rate, 1f)
            }
        }
    }
}

/**
 * Сборка ExoPlayer под музыку (16/24-bit FLAC, MP3, AAC).
 *
 * Как достигается чистый 24-bit звук:
 *  - Media3 спрашивает у приёмника, поддерживается ли PCM_FLOAT напрямую, и если да, просит
 *    системный декодер (в том числе FLAC) выдавать float. 24-bit FLAC доходит до DSP без урезания;
 *  - DspAudioSink считает цепочку в float/Double и отдаёт в AudioTrack 32-bit float.
 *
 * Буфер: для локальных файлов большой запас не нужен. 10–30 секунд хватает, а RAM экономится
 * (FLAC 24/96 — около 4–5 Мбит/с, то есть до 20 МБ на максимальный буфер).
 *
 * Расширения декодеров (libflac / ffmpeg) здесь не подключены: используются системные кодеки
 * с резервным переходом на другой декодер при сбое.
 */
@UnstableApi
object PlayerFactory {

    private const val MIN_BUFFER_MS = 10_000
    private const val MAX_BUFFER_MS = 30_000
    private const val START_BUFFER_MS = 1_000
    private const val REBUFFER_MS = 2_500

    fun create(context: Context): PlayerHolder {
        val appContext = context.applicationContext
        val sink = DspAudioSink.create(appContext)

        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            // Все стандартные аудиорендереры используют наш приёмник с DSP
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = sink
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            setEnableDecoderFallback(true)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(appContext, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { it.setSeekParameters(SeekParameters.EXACT) }

        return PlayerHolder(player, sink)
    }
}
