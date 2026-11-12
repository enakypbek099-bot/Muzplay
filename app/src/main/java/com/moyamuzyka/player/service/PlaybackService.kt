package com.moyamuzyka.player.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.moyamuzyka.player.audio.AudioState
import com.moyamuzyka.player.audio.dsp.HeadphoneBass
import com.moyamuzyka.player.playback.PlayerFactory
import com.moyamuzyka.player.playback.PlayerHolder

/**
 * Единое хранилище настроек звука. Живёт в процессе приложения, поэтому UI и сервис
 * (оба в одном процессе) видят один и тот же [state].
 *
 * Как пользоваться из UI:
 *   AudioStateStore.state.eq.gains[0] = 6.0
 *   AudioStateStore.commit()          // применить к звуку (вызывать в том же потоке, где менялось состояние)
 *
 * Пока сервис не запущен, commit() ничего не делает; при запуске сервис сам применяет текущее состояние.
 */
object AudioStateStore {
    val state = AudioState()

    @Volatile private var applier: ((AudioState) -> Unit)? = null

    internal fun attach(apply: (AudioState) -> Unit) {
        applier = apply
        apply(state)
    }

    internal fun detach() {
        applier = null
    }

    fun commit() {
        HeadphoneBass.sync(state)      // профиль наушников выбирает свой бас-режим (hpBass в эталоне)
        applier?.invoke(state)
    }
}

/**
 * Фоновое воспроизведение: владеет плеером и MediaSession. Уведомление с кнопками, экран блокировки
 * и Bluetooth-управление создаёт Media3 автоматически (MediaSessionService + MediaSession).
 * Звук идёт через DspAudioSink, а настройки применяются через [AudioStateStore].
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var holder: PlayerHolder? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val h = PlayerFactory.create(this)
        holder = h

        val builder = MediaSession.Builder(this, h.player)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        session = builder.build()

        AudioStateStore.attach { h.applyState(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Приложение смахнули из недавних: если музыка не играет, сервис останавливается. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        AudioStateStore.detach()
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        holder = null
        super.onDestroy()
    }
}
