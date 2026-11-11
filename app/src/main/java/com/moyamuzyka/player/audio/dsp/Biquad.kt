package com.moyamuzyka.player.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Типы фильтров, которые использует HTML-эталон (BiquadFilterNode). */
enum class BiquadType { LOWPASS, HIGHPASS, PEAKING, LOWSHELF, HIGHSHELF }

/**
 * Биквадный фильтр 1-в-1 с BiquadFilterNode из Web Audio API.
 *
 * Важные детали эталона, которые здесь соблюдены:
 *  - LOWPASS / HIGHPASS: параметр Q задаётся в дБ (линейная добротность = 10^(Q/20)).
 *  - PEAKING: Q линейный, A = 10^(gain/40).
 *  - LOWSHELF / HIGHSHELF: Q игнорируется, наклон S = 1 (как в спецификации).
 *  - Крайние частоты (<= 0 и >= Найквиста) обрабатываются как в Chromium.
 *  - Плавное изменение параметров повторяет AudioParam.setTargetAtTime
 *    (экспоненциальное приближение с постоянной времени tau).
 *
 * Обработка: Direct Form II Transposed, состояние и коэффициенты в Double —
 * это важно для 24-bit FLAC и низких частот (30–200 Гц), чтобы не было шума и «срывов».
 *
 * Формат данных: чередующиеся (interleaved) float-сэмплы, обработка на месте.
 */
class Biquad(
    private val type: BiquadType,
    private val channels: Int,
    private val sampleRate: Int
) {
    // Текущие параметры
    private var freq = 350.0
    private var q = 1.0
    private var gainDb = 0.0

    // Целевые параметры (для плавного изменения)
    private var tFreq = freq
    private var tQ = q
    private var tGainDb = gainDb
    private var tau = 0.0
    private var smoothing = false

    // Коэффициенты (нормированы на a0)
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var bypass = true

    // Состояние по каналам
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    init { recompute() }

    /** Мгновенная установка параметров (аналог присвоения `.value`). */
    fun set(freqHz: Double, q: Double = this.q, gainDb: Double = this.gainDb) {
        this.freq = freqHz; this.q = q; this.gainDb = gainDb
        tFreq = freqHz; tQ = q; tGainDb = gainDb
        smoothing = false
        recompute()
    }

    /** Плавная установка (аналог `setTargetAtTime(value, now, tau)`), tau в секундах. */
    fun setTarget(freqHz: Double, q: Double, gainDb: Double, tauS: Double) {
        if (tauS <= 0.0) { set(freqHz, q, gainDb); return }
        tFreq = freqHz; tQ = q; tGainDb = gainDb
        tau = tauS
        smoothing = true
    }

    /** Сброс состояния фильтра (при перемотке / смене трека). */
    fun reset() {
        z1.fill(0.0); z2.fill(0.0)
    }

    /** Обработка буфера на месте. [frames] — число кадров (не сэмплов). */
    fun process(data: FloatArray, frames: Int) {
        var pos = 0
        while (pos < frames) {
            val n = min(BLOCK, frames - pos)
            if (smoothing) advance(n)
            if (!bypass) runBlock(data, pos, n)
            pos += n
        }
    }

    private fun runBlock(data: FloatArray, startFrame: Int, n: Int) {
        val ch = channels
        var idx = startFrame * ch
        for (i in 0 until n) {
            for (c in 0 until ch) {
                val x = data[idx].toDouble()
                val y = b0 * x + z1[c]
                var s1 = b1 * x - a1 * y + z2[c]
                var s2 = b2 * x - a2 * y
                // Защита от денормалов
                if (abs(s1) < DENORMAL) s1 = 0.0
                if (abs(s2) < DENORMAL) s2 = 0.0
                z1[c] = s1
                z2[c] = s2
                data[idx] = y.toFloat()
                idx++
            }
        }
    }

    /** Один шаг setTargetAtTime для блока из n кадров. */
    private fun advance(n: Int) {
        val k = 1.0 - exp(-(n.toDouble() / sampleRate) / tau)
        freq += (tFreq - freq) * k
        q += (tQ - q) * k
        gainDb += (tGainDb - gainDb) * k
        if (abs(tFreq - freq) < 1e-4 * maxOf(1.0, abs(tFreq)) &&
            abs(tQ - q) < 1e-5 && abs(tGainDb - gainDb) < 1e-5
        ) {
            freq = tFreq; q = tQ; gainDb = tGainDb
            smoothing = false
        }
        recompute()
    }

    private fun setIdentity() { b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0 }
    private fun setZero() { b0 = 0.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0 }

    /** Пересчёт коэффициентов по спецификации Web Audio (RBJ Audio EQ Cookbook). */
    private fun recompute() {
        val nyquist = sampleRate / 2.0
        val fn = freq / nyquist                      // нормированная частота 0..1
        val isGainType = type == BiquadType.PEAKING || type == BiquadType.LOWSHELF || type == BiquadType.HIGHSHELF
        val a = 10.0.pow(gainDb / 40.0)

        bypass = isGainType && abs(gainDb) < 1e-6 && !smoothing

        when {
            // ---- Краевые случаи (как в Chromium) ----
            fn >= 1.0 -> when (type) {
                BiquadType.LOWPASS -> setIdentity()
                BiquadType.HIGHPASS -> setZero()
                BiquadType.LOWSHELF -> { setIdentity(); b0 = a * a }
                BiquadType.PEAKING, BiquadType.HIGHSHELF -> setIdentity()
            }
            fn <= 0.0 -> when (type) {
                BiquadType.LOWPASS -> setZero()
                BiquadType.HIGHPASS, BiquadType.PEAKING, BiquadType.LOWSHELF -> setIdentity()
                BiquadType.HIGHSHELF -> { setIdentity(); b0 = a * a }
            }
            else -> {
                val w0 = PI * fn
                val cosW = cos(w0)
                val sinW = sin(w0)
                var nb0: Double; var nb1: Double; var nb2: Double
                var na0: Double; var na1: Double; var na2: Double

                when (type) {
                    BiquadType.LOWPASS -> {
                        val alpha = sinW / (2.0 * 10.0.pow(q / 20.0))   // Q в дБ
                        nb0 = (1.0 - cosW) / 2.0; nb1 = 1.0 - cosW; nb2 = nb0
                        na0 = 1.0 + alpha; na1 = -2.0 * cosW; na2 = 1.0 - alpha
                    }
                    BiquadType.HIGHPASS -> {
                        val alpha = sinW / (2.0 * 10.0.pow(q / 20.0))   // Q в дБ
                        nb0 = (1.0 + cosW) / 2.0; nb1 = -(1.0 + cosW); nb2 = nb0
                        na0 = 1.0 + alpha; na1 = -2.0 * cosW; na2 = 1.0 - alpha
                    }
                    BiquadType.PEAKING -> {
                        if (q > 0.0) {
                            val alpha = sinW / (2.0 * q)               // Q линейный
                            nb0 = 1.0 + alpha * a; nb1 = -2.0 * cosW; nb2 = 1.0 - alpha * a
                            na0 = 1.0 + alpha / a; na1 = -2.0 * cosW; na2 = 1.0 - alpha / a
                        } else {
                            nb0 = a * a; nb1 = 0.0; nb2 = 0.0; na0 = 1.0; na1 = 0.0; na2 = 0.0
                        }
                    }
                    BiquadType.LOWSHELF -> {
                        val alpha = sinW / 2.0 * sqrt(2.0)             // S = 1
                        val k2 = 2.0 * sqrt(a) * alpha
                        nb0 = a * ((a + 1.0) - (a - 1.0) * cosW + k2)
                        nb1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)
                        nb2 = a * ((a + 1.0) - (a - 1.0) * cosW - k2)
                        na0 = (a + 1.0) + (a - 1.0) * cosW + k2
                        na1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW)
                        na2 = (a + 1.0) + (a - 1.0) * cosW - k2
                    }
                    BiquadType.HIGHSHELF -> {
                        val alpha = sinW / 2.0 * sqrt(2.0)             // S = 1
                        val k2 = 2.0 * sqrt(a) * alpha
                        nb0 = a * ((a + 1.0) + (a - 1.0) * cosW + k2)
                        nb1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)
                        nb2 = a * ((a + 1.0) + (a - 1.0) * cosW - k2)
                        na0 = (a + 1.0) - (a - 1.0) * cosW + k2
                        na1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW)
                        na2 = (a + 1.0) - (a - 1.0) * cosW - k2
                    }
                }
                b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0
                a1 = na1 / na0; a2 = na2 / na0
            }
        }

        if (bypass) { z1.fill(0.0); z2.fill(0.0) }
    }

    companion object {
        /** Размер подблока для плавного изменения параметров (кадров). */
        private const val BLOCK = 32
        private const val DENORMAL = 1e-30
    }
}
