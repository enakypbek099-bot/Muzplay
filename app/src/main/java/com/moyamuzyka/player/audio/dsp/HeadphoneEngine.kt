package com.moyamuzyka.player.audio.dsp

import com.moyamuzyka.player.audio.AudioState
import com.moyamuzyka.player.audio.HeadphoneConfig
import com.moyamuzyka.player.audio.HeadphoneProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Наушники: имитация звучания выбранной модели (кривая АЧХ, бас, ширина сцены).
 * Порт buildHP / applyHP из HTML-эталона. Профили (17 моделей) лежат в [HeadphoneConfig.PROFILES].
 *
 * Цепочка точно как в эталоне:
 *   вход ─► Mid/Side: mid = (L + R) / 2 → в оба канала;
 *                     side = (L − R) / 2 → задержка (0.2–1.5 мс) → × width в L и × (−width) в R
 *        ─► 4 фильтра профиля (peaking × 3 + highshelf) ─► lowshelf баса ─► peaking баса
 *        ─► выходной trim ( −(maxEq·0.6 + max(0, bs·k)·0.55 + 1) дБ )
 *
 * k = уровень / 3 (0..5 → 0..1.67), если наушники включены, иначе 0; k масштабирует только бас.
 * Без профиля цепочка прозрачна (width = 1, задержка 0, все gain = 0), поэтому после затухания
 * движок полностью обходится и ничего не меняет в сэмплах.
 *
 * Нужен стерео-вход; для других каналов движок ничего не делает.
 * Отличие от эталона: задержка стороны сглаживается с tau 30 мс (в эталоне 50 мс) — на слух не отличить.
 *
 * Потоки: [update] — из любого потока, [process] и [reset] — из аудио-потока.
 */
class HeadphoneEngine(private val channels: Int, private val sampleRate: Int) {

    private class Settings(val profile: HeadphoneProfile?, val k: Double)

    @Volatile private var pending = Settings(null, 0.0)
    private var applied: Settings = pending
    private var idle = true
    private var settleFrames = 0

    private val stereo = channels == 2

    // Фильтры профиля: 3 peaking + 1 highshelf (так устроены все профили эталона)
    private val slots = arrayOf(
        Biquad(BiquadType.PEAKING, channels, sampleRate),
        Biquad(BiquadType.PEAKING, channels, sampleRate),
        Biquad(BiquadType.PEAKING, channels, sampleRate),
        Biquad(BiquadType.HIGHSHELF, channels, sampleRate)
    )
    private val slotTypes = arrayOf("peaking", "peaking", "peaking", "highshelf")
    private val slotHz = doubleArrayOf(1000.0, 1000.0, 1000.0, 1000.0)
    private val slotQ = doubleArrayOf(1.0, 1.0, 1.0, 1.0)

    private val bassShelf = Biquad(BiquadType.LOWSHELF, channels, sampleRate)
    private val bassPeak = Biquad(BiquadType.PEAKING, channels, sampleRate)
    private var shelfHz = 80.0
    private var peakHz = 130.0
    private var peakQ = 1.0

    private val sideDelay = SmoothDelay(1, sampleRate, MAX_DELAY_S)
    private val outGain = SmoothGain(channels, sampleRate, TAU_S)

    private val k = 1.0 - exp(-1.0 / (TAU_S * sampleRate))
    private var widthC = 1.0
    private var widthT = 1.0

    private var side = FloatArray(0)

    init {
        for (i in slots.indices) slots[i].set(slotHz[i], slotQ[i], 0.0)
        bassShelf.set(shelfHz, 0.7, 0.0)
        bassPeak.set(peakHz, peakQ, 0.0)
        sideDelay.set(0.0, true)
    }

    /** true — профиль выключен и цепочка полностью обойдена. */
    val isBypassed: Boolean get() = idle

    /** Применить состояние (профиль и уровень берутся из AudioState, правило иммерсивного звука учтено). */
    fun update(state: AudioState) {
        val p = state.headphoneActive
        val level = if (p != null) state.hp.k() else 0.0
        val cur = pending
        if (cur.profile === p && cur.k == level) return
        pending = Settings(p, level)
    }

    fun reset() {
        for (s in slots) s.reset()
        bassShelf.reset(); bassPeak.reset()
        sideDelay.reset()
    }

    fun process(data: FloatArray, frames: Int) {
        if (!stereo) return
        val s = pending
        if (s !== applied) apply(s)
        if (idle) return

        if (side.size < frames) side = FloatArray(frames)
        val sd = side

        // Mid/Side: mid в оба канала, side отдельно
        var idx = 0
        for (i in 0 until frames) {
            val l = data[idx]
            val r = data[idx + 1]
            val mid = (l + r) * 0.5f
            sd[i] = (l - r) * 0.5f
            data[idx] = mid
            data[idx + 1] = mid
            idx += 2
        }
        sideDelay.process(sd, frames)

        idx = 0
        for (i in 0 until frames) {
            widthC += (widthT - widthC) * k
            val w = widthC
            val delayed = sd[i]
            data[idx] = (data[idx] + w * delayed).toFloat()
            data[idx + 1] = (data[idx + 1] - w * delayed).toFloat()
            idx += 2
        }
        if (abs(widthC - widthT) < SNAP) widthC = widthT

        for (f in slots) f.process(data, frames)
        bassShelf.process(data, frames)
        bassPeak.process(data, frames)
        outGain.process(data, frames)

        if (applied.profile == null) {
            settleFrames -= frames
            if (settleFrames <= 0 && widthC == 1.0) idle = true
        }
    }

    private fun apply(s: Settings) {
        val wasIdle = idle
        applied = s
        val p = s.profile

        if (p != null) {
            idle = false
            if (wasIdle) reset()
        } else if (!wasIdle) {
            settleFrames = max(1, (SETTLE_S * sampleRate).toInt())
        }

        for (i in slots.indices) {
            val f = p?.eq?.getOrNull(i)
            if (f != null && f.type == slotTypes[i]) {
                slotHz[i] = f.freqHz
                slotQ[i] = f.q
                slots[i].setTarget(f.freqHz, f.q, f.gainDb, TAU_S)
            } else {
                slots[i].setTarget(slotHz[i], slotQ[i], 0.0, TAU_S)
            }
        }

        if (p != null) {
            shelfHz = p.shelfHz
            peakHz = p.peakHz
            peakQ = p.peakQ
        }
        bassShelf.setTarget(shelfHz, 0.7, if (p != null) p.shelfDb * s.k else 0.0, TAU_S)
        bassPeak.setTarget(peakHz, peakQ, if (p != null) p.peakDb * s.k else 0.0, TAU_S)

        widthT = p?.width ?: 1.0
        sideDelay.set(p?.delayS ?: 0.0, false)
        outGain.setDb(HeadphoneConfig.outputTrimDb(p, s.k))
    }

    private companion object {
        const val TAU_S = 0.04
        const val MAX_DELAY_S = 0.01
        const val SETTLE_S = 0.8
        const val SNAP = 1e-6
    }
}

/**
 * Связь профиля наушников с режимом баса (hpBass из эталона): включённый профиль выбирает свой
 * бас-режим, а при выключении профиля (или включении «Усиления») снимает его, если пользователь
 * не сменил режим сам.
 */
object HeadphoneBass {
    fun sync(state: AudioState) {
        val p = state.headphoneActive
        val wanted = if (p != null && state.hp.on && !state.fx.ampOn()) p.bassMode else null
        if (wanted != null) {
            state.bass.mode = wanted
            state.hp.linkedBassMode = wanted
        } else {
            val linked = state.hp.linkedBassMode
            if (linked != null && state.bass.mode == linked) {
                state.bass.mode = null
                state.hp.linkedBassMode = null
            }
        }
    }
}
