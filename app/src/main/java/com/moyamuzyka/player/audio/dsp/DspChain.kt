package com.moyamuzyka.player.audio.dsp

import com.moyamuzyka.player.audio.AudioState
import com.moyamuzyka.player.audio.BassComp
import com.moyamuzyka.player.audio.BassMode
import com.moyamuzyka.player.audio.LimiterConfig
import com.moyamuzyka.player.audio.LimiterParams
import com.moyamuzyka.player.audio.ToneState
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Полная DSP-цепочка. Порядок узлов повторяет buildChain / buildFX / hrWire из HTML-эталона:
 *
 *   Headroom ─► Subsonic (highpass) ─► Ultrasonic (lowpass) ─► Предусилитель ─► Тон (lowshelf 120 Гц, highshelf 8 кГц)
 *   ─► BassEngine ─► Эквалайзер (peaking × N)
 *   ─► FX: Усиление басов (lowshelf + peaking 30–200 Гц, до +15 дБ) ─► Усиление (до +20 дБ)
 *          ─► Виртуализатор ─► Реверберация ─► Громкость (vol/100)² ─► защитный компрессор (−1 дБ, 20:1)
 *   ─► Наушники (HeadphoneEngine) ─► Голос (pitch, PitchShifter) ─► Баланс ─► Hi-Res компрессор (если включён) ─► Limiter (Safety Limiter + ceiling)
 *
 * Правило «Иммерсивный звук» берётся из [AudioState]: пока он включён, эквалайзер, бас, лимитер
 * пользователя и тяжёлые эффекты нейтрализуются (значения в состоянии не теряются).
 * Защитный лимитер на выходе остаётся всегда — он защищает динамики.
 *
 * Пока НЕ входят (отдельные шаги):
 * режимы «360 SOUND» / «8D», «Динамическое аудио». Для headroom они уже учитываются по эталону.
 *
 * Потоки: [update] вызывается из любого потока (UI) и только кладёт неизменяемый снимок;
 * [process] и [reset] — только из аудио-потока. Реверберация строит импульс в фоновом потоке
 * с задержкой 140 мс, как в эталоне (fxIRSoon). Импульс строится лениво — только когда реверб включён
 * (экономия памяти на 24-bit/192 кГц).
 *
 * Формат: interleaved float, на месте. Виртуализатор, реверберация и баланс работают только для стерео.
 */
class DspChain(private val channels: Int, private val sampleRate: Int) {

    private val stereo = channels == 2

    // ---- Узлы ----
    private val headroom = SmoothGain(channels, sampleRate, 0.05)
    private val subsonic = Biquad(BiquadType.HIGHPASS, channels, sampleRate)
    private val ultrasonic = Biquad(BiquadType.LOWPASS, channels, sampleRate)
    private val preamp = SmoothGain(channels, sampleRate, 0.03)
    private val toneLow = Biquad(BiquadType.LOWSHELF, channels, sampleRate)
    private val toneHigh = Biquad(BiquadType.HIGHSHELF, channels, sampleRate)
    private val bass = BassEngine(channels, sampleRate)
    private var eq: Array<Biquad> = emptyArray()
    private val bassShelf = Biquad(BiquadType.LOWSHELF, channels, sampleRate)
    private val bassPeak = Biquad(BiquadType.PEAKING, channels, sampleRate)
    private val gain = SmoothGain(channels, sampleRate, 0.03)
    private val virtualizer = Virtualizer(sampleRate)
    private val reverb = Reverb(sampleRate)
    private val volume = SmoothGain(channels, sampleRate, 0.03)
    private val fxCompressor = Compressor(channels, sampleRate)
    private val headphone = HeadphoneEngine(channels, sampleRate)
    private val pitch = PitchShifter(channels, sampleRate)
    private val balance = StereoBalance(sampleRate)
    private val hiResCompressor = Compressor(channels, sampleRate)
    private val limiter = Limiter(channels, sampleRate)

    /** Задержка всей цепочки в кадрах (даёт только Limiter). */
    val latencyFrames: Int get() = limiter.latencyFrames

    // ---- Снимок настроек ----
    private class Snapshot(
        val headroomDb: Double,
        val subsonicHz: Double,
        val ultrasonicHz: Double,
        val preampDb: Double,
        val toneLowDb: Double,
        val toneHighDb: Double,
        val eqFreqs: DoubleArray,
        val eqGains: DoubleArray,
        val eqQ: Double,
        val bassBoostShelfHz: Double,
        val bassBoostPeakHz: Double,
        val bassBoostDb: Double,
        val gainDb: Double,
        val virtualizerAmount: Double,
        val reverbValues: IntArray,
        val reverbOn: Boolean,
        val volumeLinear: Double,
        val voiceSemitones: Int,
        val pan: Double,
        val hiResCompOn: Boolean,
        val hiResComp: BassComp,
        val limiter: LimiterParams
    )

    @Volatile private var pending: Snapshot? = null
    private var applied: Snapshot? = null
    private var hiResCompOn = false

    // ---- Фоновая сборка импульса реверберации ----
    private val irExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "DspChain-IR").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private var irTask: Future<*>? = null
    @Volatile private var builtIrValues: IntArray? = null

    init {
        val nyquist = sampleRate / 2.0
        subsonic.set(20.0, SUBSONIC_Q_DB, 0.0)
        ultrasonic.set(min(22000.0, nyquist * 0.98), SUBSONIC_Q_DB, 0.0)
        toneLow.set(ToneState.LOW_SHELF_HZ, 0.7, 0.0)
        toneHigh.set(ToneState.HIGH_SHELF_HZ, 0.7, 0.0)
        bassShelf.set(150.0, 0.7, 0.0)
        bassPeak.set(80.0, 0.9, 0.0)
        // Защитный компрессор после «Громкости»: порог −1 дБ, колено 0, 20:1, attack 2 мс, release 120 мс
        // (как DynamicsCompressorNode в эталоне — с автоматическим makeup ≈ +0.57 дБ)
        fxCompressor.configureAutoMakeup(-1.0, 0.0, 20.0, 0.002, 0.12, immediate = true)
    }

    // =====================================================================================
    //  Управление
    // =====================================================================================

    /** Принять новое состояние. Можно вызывать из UI-потока при любом изменении настроек. */
    fun update(state: AudioState) {
        val snap = buildSnapshot(state)
        pending = snap
        bass.update(state)
        headphone.update(state)
        scheduleImpulse(snap.reverbValues, snap.reverbOn)
    }

    /** Освободить фоновый поток. Вызывать при уничтожении плеера. */
    fun release() {
        irTask?.cancel(false)
        irExecutor.shutdownNow()
    }

    /** Сброс состояния при перемотке / смене трека. Только из аудио-потока. */
    fun reset() {
        subsonic.reset(); ultrasonic.reset()
        toneLow.reset(); toneHigh.reset()
        eq.forEach { it.reset() }
        bassShelf.reset(); bassPeak.reset()
        bass.reset()
        virtualizer.reset(); reverb.reset()
        fxCompressor.reset(); hiResCompressor.reset()
        headphone.reset()
        pitch.reset()
        limiter.reset()
    }

    /** Вывод хвоста лимитера в конце потока. [out] вмещает latencyFrames * channels сэмплов. */
    fun drainTail(out: FloatArray) = limiter.drainTail(out)

    // =====================================================================================
    //  Обработка
    // =====================================================================================

    fun process(data: FloatArray, frames: Int) {
        val s = pending
        if (s != null && s !== applied) apply(s)

        headroom.process(data, frames)
        subsonic.process(data, frames)
        ultrasonic.process(data, frames)
        preamp.process(data, frames)
        toneLow.process(data, frames)
        toneHigh.process(data, frames)
        bass.process(data, frames)
        for (f in eq) f.process(data, frames)

        bassShelf.process(data, frames)
        bassPeak.process(data, frames)
        gain.process(data, frames)
        if (stereo) virtualizer.process(data, frames)
        if (stereo) reverb.process(data, frames)
        volume.process(data, frames)
        fxCompressor.process(data, frames)

        headphone.process(data, frames)
        pitch.process(data, frames)
        if (stereo) balance.process(data, frames)
        if (hiResCompOn) hiResCompressor.process(data, frames)
        limiter.process(data, frames)
    }

    private fun apply(s: Snapshot) {
        applied = s

        headroom.setDb(-s.headroomDb)
        subsonic.setTarget(s.subsonicHz, SUBSONIC_Q_DB, 0.0, PARAM_TAU_S)
        ultrasonic.setTarget(s.ultrasonicHz, SUBSONIC_Q_DB, 0.0, PARAM_TAU_S)
        preamp.setDb(s.preampDb)
        toneLow.setTarget(ToneState.LOW_SHELF_HZ, 0.7, s.toneLowDb, EQ_TAU_S)
        toneHigh.setTarget(ToneState.HIGH_SHELF_HZ, 0.7, s.toneHighDb, EQ_TAU_S)

        // Эквалайзер: при смене числа полос фильтры пересоздаются (состояние чистое)
        if (eq.size != s.eqFreqs.size) {
            eq = Array(s.eqFreqs.size) {
                Biquad(BiquadType.PEAKING, channels, sampleRate).also { b -> b.set(s.eqFreqs[it], s.eqQ, 0.0) }
            }
        }
        for (i in eq.indices) eq[i].setTarget(s.eqFreqs[i], s.eqQ, s.eqGains[i], EQ_TAU_S)

        // Усиление басов: lowshelf (Гц × 1.8, максимум 400) + peaking на выбранной частоте, gain × 0.6
        bassShelf.setTarget(s.bassBoostShelfHz, 0.7, s.bassBoostDb, PARAM_TAU_S)
        bassPeak.setTarget(s.bassBoostPeakHz, 0.9, s.bassBoostDb * 0.6, PARAM_TAU_S)

        gain.setDb(s.gainDb)
        virtualizer.setAmount(s.virtualizerAmount)
        reverb.setValues(s.reverbValues, s.reverbOn)
        volume.setLinear(s.volumeLinear)
        pitch.setSemitones(s.voiceSemitones)
        balance.setPan(s.pan)

        hiResCompOn = s.hiResCompOn
        hiResCompressor.configureAutoMakeup(s.hiResComp)
        limiter.configure(s.limiter)
    }

    private fun scheduleImpulse(v: IntArray, on: Boolean) {
        if (!on || !stereo) return
        val last = builtIrValues
        if (last != null && !ReverbImpulse.needsRebuild(last, v)) return
        irTask?.cancel(false)
        val delayMs = if (last == null) 0L else IR_DEBOUNCE_MS
        irTask = try {
            irExecutor.schedule({
                try {
                    reverb.setImpulse(v)
                    builtIrValues = v
                } catch (e: Throwable) {
                    // нет памяти или ошибка сборки: реверб останется без изменений
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null // executor уже остановлен
        }
    }

    // =====================================================================================
    //  Снимок из AudioState
    // =====================================================================================

    private fun buildSnapshot(st: AudioState): Snapshot {
        val fx = st.fx
        val eqOn = st.eqActive
        val nyquist = sampleRate / 2.0

        val boostDb = if (st.bassBoostActive) fx.bassBoostDb() else 0.0
        val eqGains = DoubleArray(st.eq.n) { if (eqOn) st.eq.gains[it] else 0.0 }

        return Snapshot(
            headroomDb = autoHeadroomDb(st),
            // Subsonic «auto»: 20 Гц при Speaker Safe, иначе 15 Гц; Ultrasonic «auto»: min(22 кГц, Найквист × 0.98)
            subsonicHz = if (st.hires.speakerSafe) 20.0 else 15.0,
            ultrasonicHz = min(22000.0, nyquist * 0.98),
            preampDb = if (eqOn) st.eq.preampDb else 0.0,
            toneLowDb = st.tone.lowDb(),
            toneHighDb = st.tone.highDb(),
            eqFreqs = st.eq.freqs.toDoubleArray(),
            eqGains = eqGains,
            eqQ = st.eq.q(),
            bassBoostShelfHz = fx.bassShelfHz(),
            bassBoostPeakHz = fx.bassPeakHz(),
            bassBoostDb = boostDb,
            gainDb = if (st.gainActive) fx.gainDb() else 0.0,
            virtualizerAmount = if (st.virtualizerActive) fx.vz.v / 100.0 else 0.0,
            reverbValues = fx.rvValues.copyOf(),
            reverbOn = st.reverbActive,
            volumeLinear = fx.volumeGain(),
            voiceSemitones = st.lab.voiceSemitones.coerceIn(-12, 12),
            pan = st.lab.pan(fx.balance),
            hiResCompOn = st.hires.compressorOn,
            hiResComp = st.hires.comp,
            limiter = if (st.limiterActive) st.hires.limiter.active() else LimiterConfig.SAFETY
        )
    }

    /**
     * Auto Headroom (дБ) — порт hrCalc из эталона. Запас сверху на усиливающие эффекты,
     * чтобы сумма усилений не уходила в клиппинг. Результат 0..18 дБ.
     * (Makeup Hi-Res компрессора пока не учитывается — в AudioState нет этого поля.)
     */
    internal fun autoHeadroomDb(st: AudioState): Double {
        var m = 0.0
        fun need(v: Double) { if (v > m) m = v }

        val eqMax = max(0.0, st.eq.gains.maxOrNull() ?: 0.0)
        val b = max(0.0, st.eq.preampDb) + eqMax + max(st.tone.low, st.tone.high) / 100.0 * 12.0
        if (b > 0) need(if (b > 18) 12.0 else if (b > 12) 9.0 else if (b > 6) 6.0 else 3.0)

        val bm = st.bass.mode
        if (bm != null) {
            need(
                when (bm) {
                    BassMode.HARD -> 9.0
                    BassMode.DYN -> if (st.bass.dynDb > 2) 6.0 else 3.0
                    BassMode.CLEAN -> 3.0
                    else -> 6.0
                }
            )
            if (b > 0) need(9.0)
        }
        if (st.imm.mode != null || st.imm.d8 != 0) need(6.0)

        var e = 0.0
        if (st.reverbActive && st.fx.rvValues[5] > 0) e += 2.0
        if (st.virtualizerActive && st.fx.vz.v > 0) e += 2.0
        if (st.bassBoostActive && st.fx.bb.v > 0) e += ceil(st.fx.bb.v / 100.0 * st.fx.effMaxBassDb() * 0.5)
        if (st.headphoneActive != null) e += 2.0
        if (st.imm.dynamicAudioOn) e += 3.0
        if (e > 0) m += e

        if (st.hires.speakerSafe && (bm == BassMode.INFRA || bm == BassMode.DEEP || bm == BassMode.HARD)) m += 3.0
        return m.coerceIn(0.0, 18.0)
    }

    private companion object {
        const val SUBSONIC_Q_DB = 0.707      // Q highpass/lowpass в Web Audio задаётся в дБ
        const val PARAM_TAU_S = 0.03
        const val EQ_TAU_S = 0.02            // сглаживание ползунков, чтобы не было щелчков
        const val IR_DEBOUNCE_MS = 140L
    }
}
