package com.moyamuzyka.player.audio.dsp

import com.moyamuzyka.player.audio.AudioMath
import com.moyamuzyka.player.audio.LimiterParams
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

private const val LN10_OVER_20 = 0.1151292546497023   // ln(10) / 20
private const val SILENCE_DB = -180.0

/**
 * Компрессор с мягким коленом (soft knee).
 *
 * Параметры те же, что в HTML-эталоне: порог (threshold), колено (knee), ratio,
 * attack, release (+ makeup для Hi-Res компрессора). Внутри — собственный алгоритм
 * вместо DynamicsCompressorNode:
 *  - детектор по пику, связанный по каналам (stereo-link), стерео-образ не «плывёт»;
 *  - расчёт усиления в логарифмической области (дБ);
 *  - сглаживание атаки/релиза по экспоненте, без жёсткого makeup-gain и без «накачки».
 *
 * Используется для бас-режимов (Глубокий бас, Хард бас, Динамический бас),
 * бас-лимитера и Hi-Res компрессора. Формат: interleaved float, обработка на месте.
 */
class Compressor(private val channels: Int, private val sampleRate: Int) {

    var thresholdDb = 0.0; private set
    var kneeDb = 0.0; private set
    var ratio = 1.0; private set
    var makeupDb = 0.0; private set

    private var attackCoef = 0.0
    private var releaseCoef = 0.0

    /** Текущее сглаженное ослабление в дБ (<= 0). Для индикатора, как lim.reduction в эталоне. */
    var gainReductionDb = 0.0; private set

    init { configure(0.0, 0.0, 1.0, 0.01, 0.1, 0.0) }

    fun configure(
        thresholdDb: Double, kneeDb: Double, ratio: Double,
        attackS: Double, releaseS: Double, makeupDb: Double = 0.0
    ) {
        this.thresholdDb = thresholdDb
        this.kneeDb = max(0.0, kneeDb)
        this.ratio = max(1.0, ratio)
        this.makeupDb = makeupDb
        attackCoef = timeToCoef(attackS)
        releaseCoef = timeToCoef(releaseS)
    }

    /** Удобная перегрузка под данные из AudioParams (BassComp). */
    fun configure(c: com.moyamuzyka.player.audio.BassComp, makeupDb: Double = 0.0) =
        configure(c.thrDb, c.kneeDb, c.ratio, c.attackS, c.releaseS, makeupDb)

    fun reset() { gainReductionDb = 0.0 }

    private fun timeToCoef(t: Double): Double = exp(-1.0 / (max(t, 1e-5) * sampleRate))

    /** Статическая кривая: возвращает ослабление в дБ (<= 0) для входного уровня xDb. */
    private fun computeGainDb(xDb: Double): Double {
        val over = xDb - thresholdDb
        val w = kneeDb
        val slope = 1.0 / ratio - 1.0
        if (over <= -w / 2.0) return 0.0
        if (over >= w / 2.0) return slope * over
        val t = over + w / 2.0
        return slope * t * t / (2.0 * w)
    }

    fun process(data: FloatArray, frames: Int) {
        // Быстрый выход: компрессор нейтрален
        if (ratio <= 1.0 && makeupDb == 0.0) { gainReductionDb = 0.0; return }

        val ch = channels
        var idx = 0
        var gr = gainReductionDb
        for (i in 0 until frames) {
            var peak = 0.0
            for (c in 0 until ch) {
                val a = abs(data[idx + c].toDouble())
                if (a > peak) peak = a
            }
            val xDb = if (peak < 1e-9) SILENCE_DB else 20.0 * log10(peak)
            val target = computeGainDb(xDb)
            val coef = if (target < gr) attackCoef else releaseCoef
            gr = coef * gr + (1.0 - coef) * target
            if (abs(gr) < 1e-9) gr = 0.0
            val g = exp((gr + makeupDb) * LN10_OVER_20)
            for (c in 0 until ch) data[idx + c] = (data[idx + c] * g).toFloat()
            idx += ch
        }
        gainReductionDb = gr
    }
}

/**
 * Лимитер с упреждением (lookahead) — настоящий пиковый «кирпичная стена».
 *
 * Логика цепочки повторяет эталон (HIRES.lim + applyLim + hrCe):
 *  1. Компрессор по порогу threshold / ratio / attack / release (колено 0).
 *  2. Ceiling-гейн = min(0, ceiling − threshold) дБ.
 *  3. Пиковый лимитер с lookahead: выход гарантированно не превышает ceiling.
 *
 * Шаг 3 — отличие от Web Audio: сэмплы не клиппуются и не «визжат» на 24-bit FLAC
 * даже при ratio 20 и сильных пиках.
 *
 * Алгоритм шага 3: для каждого кадра берётся требуемое усиление t = ceil/peak (<= 1),
 * затем минимум по окну из N кадров (монотонная дека), затем скользящее среднее по N кадрам
 * и релиз-ограничитель. Звук задерживается на N−1 кадров, поэтому усиление успевает
 * опуститься ДО прихода пика (математически: каждое значение в окне среднего <= t пика).
 *
 * Задержка: [latencyFrames] кадров (~2 мс). Формат: interleaved float, на месте.
 */
class Limiter(private val channels: Int, private val sampleRate: Int) {

    private val comp = Compressor(channels, sampleRate)

    // ---- Lookahead ----
    private val n = max(2, (LOOKAHEAD_MS * 0.001 * sampleRate).toInt())
    private val delayFrames = n - 1
    /** Задержка, которую вносит лимитер (кадров). Нужна для компенсации и для вывода хвоста. */
    val latencyFrames: Int get() = delayFrames

    private val delayBuf = FloatArray(delayFrames * channels)
    private var delayPos = 0

    // Монотонная дека для минимума по окну
    private val dqVal = DoubleArray(n + 1)
    private val dqIdx = LongArray(n + 1)
    private var dqHead = 0
    private var dqSize = 0
    private var frameCounter = 0L

    // Скользящее среднее минимума
    private val avgBuf = DoubleArray(n) { 1.0 }
    private var avgPos = 0
    private var avgSum = n.toDouble()

    // Релиз
    private var relFollower = 1.0
    private var relStep = 0.0

    // Параметры
    private var ceilLin = 1.0
    private var ceilGain = 1.0

    /** Общее ослабление в дБ (<= 0): компрессор + пиковая ступень. */
    var gainReductionDb = 0.0; private set

    init { configure(LimiterParams(-1.0, 0.0, 20.0, 0.003, 0.1, -1.0)) }

    fun configure(p: LimiterParams) {
        comp.configure(p.thresholdDb, p.kneeDb, p.ratio, p.attackS, p.releaseS, 0.0)
        ceilGain = AudioMath.dbToGain(min(0.0, p.ceilingDb - p.thresholdDb))
        ceilLin = AudioMath.dbToGain(min(0.0, p.ceilingDb))
        relStep = 1.0 - exp(-1.0 / (max(p.releaseS, 1e-3) * sampleRate))
    }

    fun reset() {
        comp.reset()
        delayBuf.fill(0f); delayPos = 0
        dqHead = 0; dqSize = 0; frameCounter = 0L
        avgBuf.fill(1.0); avgPos = 0; avgSum = n.toDouble()
        relFollower = 1.0
        gainReductionDb = 0.0
    }

    fun process(data: FloatArray, frames: Int) {
        // Шаг 1: компрессор
        comp.process(data, frames)

        val ch = channels
        val cap = n + 1
        var idx = 0
        for (i in 0 until frames) {
            // Шаг 2: ceiling-гейн и поиск пика кадра
            var peak = 0.0
            for (c in 0 until ch) {
                val v = (data[idx + c] * ceilGain).toFloat()
                data[idx + c] = v
                val a = abs(v.toDouble())
                if (a > peak) peak = a
            }
            val t = if (peak > ceilLin) ceilLin / peak else 1.0

            // Минимум по окну из n кадров (монотонная дека)
            while (dqSize > 0) {
                val back = (dqHead + dqSize - 1) % cap
                if (dqVal[back] >= t) dqSize-- else break
            }
            val tail = (dqHead + dqSize) % cap
            dqVal[tail] = t; dqIdx[tail] = frameCounter; dqSize++
            while (dqIdx[dqHead] <= frameCounter - n) {
                dqHead = (dqHead + 1) % cap; dqSize--
            }
            val m = dqVal[dqHead]
            frameCounter++

            // Скользящее среднее минимума по n кадрам
            avgSum += m - avgBuf[avgPos]
            avgBuf[avgPos] = m
            avgPos = (avgPos + 1) % n
            val gBox = avgSum / n

            // Релиз: усиление не может подниматься быстрее экспоненты, вниз — мгновенно по gBox
            relFollower = min(gBox, relFollower + (1.0 - relFollower) * relStep)
            val g = relFollower.toFloat()

            // Задержка и применение усиления
            val base = delayPos * ch
            for (c in 0 until ch) {
                val delayed = delayBuf[base + c]
                delayBuf[base + c] = data[idx + c]
                data[idx + c] = delayed * g
            }
            delayPos = (delayPos + 1) % delayFrames
            idx += ch
        }

        val limDb = if (relFollower > 1e-9) 20.0 * log10(relFollower) else SILENCE_DB
        gainReductionDb = comp.gainReductionDb + limDb
    }

    /**
     * Вывод хвоста при конце потока (queueEndOfStream): прогоняет нули, чтобы выдать
     * оставшиеся [latencyFrames] кадров из линии задержки. [out] должен вмещать
     * latencyFrames * channels сэмплов.
     */
    fun drainTail(out: FloatArray) {
        java.util.Arrays.fill(out, 0, delayFrames * channels, 0f)
        process(out, delayFrames)
    }

    companion object {
        /** Время упреждения лимитера, мс. */
        const val LOOKAHEAD_MS = 2.0
    }
}
