package com.moyamuzyka.player.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/*
 * Файл содержит три простых узла FX-цепочки эталона:
 *  1. Virtualizer   — «Виртуализатор» (расширение стерео по схеме mid/side + задержанный mid).
 *  2. StereoBalance — «Баланс» (аналог StereoPannerNode: Лаборатория → Баланс + Аудио баланс).
 *  3. SmoothGain    — плавный гейн («Усиление» до +20 дБ, «Громкость» (vol/100)^2 и т.д.).
 *
 * Все узлы работают с interleaved float, на месте. Сглаживание параметров повторяет
 * AudioParam.setTargetAtTime(value, now, 0.03) из эталона (экспоненциальное приближение).
 */

/* ======================= ВИРТУАЛИЗАТОР (HTML: buildFX → mid/side, vdl, vw, sg) ======================= */
/**
 * Схема эталона (стерео):
 *   mid  = (L + R) / 2
 *   side = (L − R) / 2
 *   side' = (side + vw · задержка_12мс(mid)) · sg
 *   L' = mid + side',   R' = mid − side'
 * где при степени z = Виртуализатор/100 (0..1):  sg = 1 + 2z,  vw = 0.85 z.
 * При z = 0 звук проходит без изменений (L' = L, R' = R), и обработка полностью пропускается.
 *
 * Стерео-вход обязателен (2 канала).
 */
class Virtualizer(private val sampleRate: Int) {

    private val delay = SmoothDelay(1, sampleRate, 0.05)

    @Volatile private var sgTarget = 1.0
    @Volatile private var vwTarget = 0.0
    private var sgCur = 1.0
    private var vwCur = 0.0
    private val k = 1.0 - exp(-1.0 / (TAU * sampleRate))

    private var mid = FloatArray(0)
    private var wasBypassed = true

    init { delay.set(DELAY_S, true) }

    /**
     * Установить степень. [amount] = FX.vz.v / 100 (0..1), если виртуализатор включён и
     * не заблокирован режимом «Иммерсивный звук»; иначе 0. Можно вызывать из любого потока.
     */
    fun setAmount(amount: Double) {
        val z = amount.coerceIn(0.0, 1.0)
        sgTarget = 1.0 + z * 2.0
        vwTarget = z * 0.85
    }

    fun reset() {
        delay.reset()
        sgCur = sgTarget; vwCur = vwTarget
        wasBypassed = true
    }

    fun process(data: FloatArray, frames: Int) {
        val sgT = sgTarget
        val vwT = vwTarget

        // Выключен и полностью «успокоился» — звук не трогаем
        if (vwT == 0.0 && sgT == 1.0 && vwCur < 1e-6 && abs(sgCur - 1.0) < 1e-6) {
            sgCur = 1.0; vwCur = 0.0
            wasBypassed = true
            return
        }
        if (wasBypassed) { delay.reset(); wasBypassed = false }

        if (mid.size < frames) mid = FloatArray(frames)

        // Проход 1: mid -> линия задержки 12 мс
        var idx = 0
        for (i in 0 until frames) {
            mid[i] = (data[idx] + data[idx + 1]) * 0.5f
            idx += 2
        }
        delay.process(mid, frames)

        // Проход 2: сборка L/R
        idx = 0
        for (i in 0 until frames) {
            sgCur += (sgT - sgCur) * k
            vwCur += (vwT - vwCur) * k
            val l = data[idx].toDouble()
            val r = data[idx + 1].toDouble()
            val m = (l + r) * 0.5
            val s = (l - r) * 0.5
            val sOut = (s + vwCur * mid[i]) * sgCur
            data[idx] = (m + sOut).toFloat()
            data[idx + 1] = (m - sOut).toFloat()
            idx += 2
        }
        if (abs(sgCur - sgT) < 1e-7) sgCur = sgT
        if (abs(vwCur - vwT) < 1e-7) vwCur = vwT
    }

    private companion object {
        /** Задержка mid в виртуализаторе эталона: vdl.delayTime = 0.012 с. */
        const val DELAY_S = 0.012
        const val TAU = 0.03
    }
}

/* ======================= БАЛАНС (HTML: StereoPannerNode, applyLab) ======================= */
/**
 * Стерео-панорама по формулам Web Audio StereoPannerNode (для стерео-входа):
 *   pan <= 0: x = pan + 1,  L' = L + R·cos(x·π/2),  R' = R·sin(x·π/2)
 *   pan  > 0: x = pan,      L' = L·cos(x·π/2),      R' = R + L·sin(x·π/2)
 * pan = 0 — звук без изменений (обработка пропускается).
 * Значение pan берётся из LabState.pan(fxBalance) = clamp((Лаб.Баланс + Аудио баланс)/5, −1, 1).
 */
class StereoBalance(private val sampleRate: Int) {

    @Volatile private var panTarget = 0.0
    private var panCur = 0.0
    private val k = 1.0 - exp(-1.0 / (TAU * sampleRate))

    fun setPan(pan: Double) { panTarget = pan.coerceIn(-1.0, 1.0) }

    fun reset() { panCur = panTarget }

    fun process(data: FloatArray, frames: Int) {
        val target = panTarget
        if (target == 0.0 && abs(panCur) < 1e-7) { panCur = 0.0; return }

        var idx = 0
        for (i in 0 until frames) {
            panCur += (target - panCur) * k
            val l = data[idx]
            val r = data[idx + 1]
            if (panCur <= 0.0) {
                val x = (panCur + 1.0) * PI / 2.0
                data[idx] = (l + r * cos(x)).toFloat()
                data[idx + 1] = (r * sin(x)).toFloat()
            } else {
                val x = panCur * PI / 2.0
                data[idx] = (l * cos(x)).toFloat()
                data[idx + 1] = (r + l * sin(x)).toFloat()
            }
            idx += 2
        }
        if (abs(panCur - target) < 1e-7) panCur = target
    }

    private companion object { const val TAU = 0.03 }
}

/* ======================= ПЛАВНЫЙ ГЕЙН (HTML: GainNode + setTargetAtTime) ======================= */
/**
 * Универсальный гейн с плавным изменением. Используется для:
 *  - «Усиление»: setDb(FxState.gainDb()) — до +20 дБ (линейно ×10) строго по эталону;
 *  - «Громкость»: setLinear(FxState.volumeGain()) = (vol/100)²;
 *  - выходных гейнов (трим наушников, ceiling и т.п.).
 * Работает в float без ограничения сверху: перегрузку отлавливает финальный лимитер.
 */
class SmoothGain(private val channels: Int, private val sampleRate: Int, tauS: Double = 0.03) {

    @Volatile private var target = 1.0
    private var cur = 1.0
    private val k = 1.0 - exp(-1.0 / (tauS * sampleRate))

    fun setLinear(g: Double) { target = g.coerceAtLeast(0.0) }
    fun setDb(db: Double) { target = Math.pow(10.0, db / 20.0) }
    fun setImmediate(g: Double) { target = g.coerceAtLeast(0.0); cur = target }

    fun process(data: FloatArray, frames: Int) {
        val t = target
        if (t == 1.0 && cur == 1.0) return

        var idx = 0
        for (i in 0 until frames) {
            cur += (t - cur) * k
            val g = cur.toFloat()
            for (c in 0 until channels) data[idx + c] *= g
            idx += channels
        }
        if (abs(cur - t) < 1e-7) cur = t
    }
}
