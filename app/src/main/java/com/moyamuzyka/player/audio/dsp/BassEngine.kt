package com.moyamuzyka.player.audio.dsp

import com.moyamuzyka.player.audio.AudioState
import com.moyamuzyka.player.audio.BassComp
import com.moyamuzyka.player.audio.BassConfig
import com.moyamuzyka.player.audio.BassMode
import com.moyamuzyka.player.audio.SubOsc
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Бас-движок. Порт buildBass / applyBass / envTick / hrOsc из HTML-эталона.
 *
 * Цепочка точно как в эталоне:
 *
 *   вход ─► highpass 18 Гц ─► lowshelf ─► peaking ×3 ─► компрессор ─► лимитер баса ─┐
 *     │                                                                             ├─► trim ─► выход
 *     └─► lowpass 120 Гц ─► огибающая (RMS, 512 сэмплов) ─► синус-саб ───────────────┘
 *
 * Режимы: «Инфра бас», «Чистый бас», «Глубокий бас», «Хард бас», «Динамический бас».
 * Все числа (частоты, Q, дБ, пороги, времена) берутся из [BassConfig] — это копия таблицы BM эталона.
 *
 * Что делает синус-саб (режимы Инфра / Глубокий / Хард):
 *   каждый «тик» (60 Гц, как requestAnimationFrame) считается RMS низких частот входа r,
 *   пик pk = max(r, pk * 0.9995), затем gain = hrOsc(amp * (r / pk)^2).
 *   Атака 12 мс, релиз берётся из профиля. Speaker Safe: gain * 0.6 и потолок 0.3 (иначе потолок 0.6).
 *
 * Динамический бас: lowshelf-gain = база + (q − 1) * 3.5 дБ, где q = clamp(r / avg, 0.7, 1.4),
 * avg — медленное среднее RMS (коэффициент 0.03 за тик).
 *
 * Отличия от Web Audio (сделаны намеренно, ради качества на телефоне):
 *  - Когда бас выключен и всё затухло, движок полностью обходится (нулевая нагрузка CPU и
 *    ни одного изменённого сэмпла — важно для 16/24-bit FLAC). Пока идёт затухание, обработка
 *    продолжается, поэтому щелчков при выключении нет.
 *  - Q у peaking-фильтров меняется плавно (в эталоне мгновенно); на слух разницы нет (tau 30 мс).
 *  - Обработка и фильтры считаются в Double, на входе/выходе interleaved Float.
 *
 * Иммерсивный звук: бас-режим отключается правилом AudioState.bassModeActive (null при включённом
 * иммерсивном звуке), поэтому вызывайте [update] — он сам учтёт эксклюзивность.
 *
 * Потоки: [configure] / [update] можно вызывать из любого потока (передача через volatile-ссылку).
 * [process] и [reset] — только из аудио-потока (в ExoPlayer AudioProcessor это поток воспроизведения:
 * queueInput и onFlush вызываются в нём).
 *
 * Формат данных: interleaved float, обработка на месте. Задержка 0 кадров.
 */
class BassEngine(private val channels: Int, private val sampleRate: Int) {

    init {
        require(channels >= 1) { "channels must be >= 1" }
        require(sampleRate > 0) { "sampleRate must be > 0" }
    }

    /** Неизменяемый снимок настроек: передаётся из UI-потока в аудио-поток одной ссылкой. */
    private class Settings(val mode: BassMode?, val dynDb: Double, val speakerSafe: Boolean)

    @Volatile private var pending = Settings(null, 0.0, true)
    private var applied: Settings = pending

    // ---- Фильтры цепочки (эталон: buildBass) ----
    private val highpass = Biquad(BiquadType.HIGHPASS, channels, sampleRate)
    private val shelf = Biquad(BiquadType.LOWSHELF, channels, sampleRate)
    private val peak1 = Biquad(BiquadType.PEAKING, channels, sampleRate)
    private val peak2 = Biquad(BiquadType.PEAKING, channels, sampleRate)
    private val peak3 = Biquad(BiquadType.PEAKING, channels, sampleRate)
    private val comp = BassDynamics(channels, sampleRate)
    private val lim = BassDynamics(channels, sampleRate)
    private val trim = SmoothGain(channels, sampleRate, PARAM_TAU_S)

    // ---- Огибающая баса (эталон: el + bEnvAn) ----
    private val envLowpass = Biquad(BiquadType.LOWPASS, 1, sampleRate)
    private val envRing = FloatArray(ENV_WINDOW)
    private var envPos = 0
    private var envActive = false
    private var envPeak = 0.0       // pk
    private var dynAvg = 0.0

    // ---- Синус-саб (эталон: bOsc + bOscG) ----
    private var osc: SubOsc? = null
    private var oscPhase = 0.0
    private var oscPhaseInc = 0.0
    private var oscGain = 0.0
    private var oscTarget = 0.0
    private var oscK = 0.0
    private var oscLast = 0.0

    private var speakerSafe = true
    private var shelfHz = 80.0
    private var dynBase = 4.0

    // ---- Управляющий такт ----
    private val tickFrames = max(1, (sampleRate / CONTROL_RATE_HZ + 0.5).toInt())
    private var untilTick = tickFrames
    private val segBuf = FloatArray(tickFrames * channels)
    private val monoBuf = FloatArray(tickFrames)

    // ---- Обход, когда бас выключен ----
    private var idle = true
    private var settleFrames = 0

    init {
        highpass.set(BassConfig.HIGHPASS_HZ, HP_Q_DB, 0.0)
        shelf.set(shelfHz, SHELF_Q, 0.0)
        for (p in arrayOf(peak1, peak2, peak3)) p.set(100.0, 1.0, 0.0)
        envLowpass.set(ENV_LOWPASS_HZ, HP_Q_DB, 0.0)
        setOscTarget(0.0, PARAM_TAU_S)
    }

    /** true — бас выключен и полностью обойдён (ни CPU, ни изменения сэмплов). */
    val isBypassed: Boolean get() = idle

    /** Задержка, которую вносит движок (кадров). Всегда 0. */
    val latencyFrames: Int get() = 0

    /** Задать режим. [mode] = null — бас выключен. [dynDb] — ползунок «Динамический бас», −6..+6 дБ. */
    fun configure(mode: BassMode?, dynDb: Double, speakerSafe: Boolean) {
        val d = if (dynDb.isFinite()) dynDb.coerceIn(BassConfig.DYN_MIN_DB, BassConfig.DYN_MAX_DB) else 0.0
        val cur = pending
        if (cur.mode == mode && cur.dynDb == d && cur.speakerSafe == speakerSafe) return
        pending = Settings(mode, d, speakerSafe)
    }

    /** Взять настройки из единого состояния. При иммерсивном звуке бас автоматически выключается. */
    fun update(state: AudioState) =
        configure(state.bassModeActive, state.bass.dynDb, state.hires.speakerSafe)

    /** Сброс состояния при перемотке / смене трека. Только из аудио-потока. */
    fun reset() {
        resetFilters()
        oscGain = 0.0
        oscPhase = 0.0
        untilTick = tickFrames
    }

    /** Обработка буфера на месте. [frames] — число кадров (не сэмплов). Только из аудио-потока. */
    fun process(data: FloatArray, frames: Int) {
        val s = pending
        if (s !== applied) applySettings(s)
        if (idle) return

        var pos = 0
        while (pos < frames) {
            val n = min(frames - pos, untilTick)
            val off = pos * channels
            val cnt = n * channels
            System.arraycopy(data, off, segBuf, 0, cnt)
            processSegment(segBuf, n)
            System.arraycopy(segBuf, 0, data, off, cnt)
            pos += n
            untilTick -= n
            if (untilTick == 0) {
                tick()
                untilTick = tickFrames
            }
        }

        if (applied.mode == null) checkIdle(frames)
    }

    // =====================================================================================
    //  applyBass
    // =====================================================================================

    private fun applySettings(s: Settings) {
        val wasIdle = idle
        applied = s
        speakerSafe = s.speakerSafe

        if (s.mode != null) {
            idle = false
            if (wasIdle) wakeUp()
        } else if (!wasIdle) {
            settleFrames = max(1, (SETTLE_S * sampleRate).toInt())
        }

        val p = BassConfig.profile(s.mode, s.dynDb)

        shelfHz = p.shelfHz
        dynBase = p.shelfDb
        shelf.setTarget(p.shelfHz, SHELF_Q, p.shelfDb, PARAM_TAU_S)
        peak1.setTarget(p.p1.freqHz, p.p1.q, p.p1.gainDb, PARAM_TAU_S)
        peak2.setTarget(p.p2.freqHz, p.p2.q, p.p2.gainDb, PARAM_TAU_S)
        peak3.setTarget(p.p3.freqHz, p.p3.q, p.p3.gainDb, PARAM_TAU_S)

        val c = p.comp
        if (c != null) comp.setActive(c) else comp.setNeutral()
        if (s.mode != null) lim.setActive(BassConfig.LIMITER_ON) else lim.setNeutral()

        trim.setDb(p.trimDb)

        val o = p.osc
        osc = o
        if (o != null) {
            oscPhaseInc = 2.0 * PI * o.freqHz / sampleRate
        } else {
            setOscTarget(0.0, PARAM_TAU_S)
        }
        envActive = o != null || s.mode == BassMode.DYN
    }

    /** Выход из обхода: чистое состояние, чтобы в фильтрах не осталось хвоста от старого звука. */
    private fun wakeUp() {
        resetFilters()
        untilTick = tickFrames
    }

    private fun resetFilters() {
        highpass.reset(); shelf.reset()
        peak1.reset(); peak2.reset(); peak3.reset()
        envLowpass.reset()
        envRing.fill(0f)
        envPos = 0
        comp.reset(); lim.reset()
    }

    /** Бас выключен: ждём, пока всё плавно вернётся в ноль, и только потом включаем обход. */
    private fun checkIdle(frames: Int) {
        settleFrames -= frames
        if (settleFrames <= 0 && comp.isNeutral && lim.isNeutral &&
            oscTarget == 0.0 && oscGain <= OSC_SILENCE
        ) {
            oscGain = 0.0
            idle = true
        }
    }

    // =====================================================================================
    //  Обработка сегмента (не длиннее одного тика)
    // =====================================================================================

    private fun processSegment(seg: FloatArray, n: Int) {
        if (envActive) feedEnvelope(seg, n)

        highpass.process(seg, n)
        shelf.process(seg, n)
        peak1.process(seg, n)
        peak2.process(seg, n)
        peak3.process(seg, n)
        comp.process(seg, n)
        lim.process(seg, n)
        mixSubOscillator(seg, n)
        trim.process(seg, n)
    }

    /** Вход → моно (как AnalyserNode) → lowpass 120 Гц → кольцевой буфер на 512 сэмплов. */
    private fun feedEnvelope(seg: FloatArray, n: Int) {
        val mono = monoBuf
        if (channels == 1) {
            System.arraycopy(seg, 0, mono, 0, n)
        } else {
            val inv = 1.0f / channels
            var idx = 0
            for (i in 0 until n) {
                var sum = 0f
                for (c in 0 until channels) sum += seg[idx + c]
                mono[i] = sum * inv
                idx += channels
            }
        }
        envLowpass.process(mono, n)
        var pos = envPos
        for (i in 0 until n) {
            envRing[pos] = mono[i]
            pos = (pos + 1) and (ENV_WINDOW - 1)
        }
        envPos = pos
    }

    /** Синус-саб идёт после лимитера баса и до trim, в левый и правый каналы (моно → стерео). */
    private fun mixSubOscillator(seg: FloatArray, n: Int) {
        val target = oscTarget
        var g = oscGain
        if (target == 0.0 && g <= OSC_SILENCE) {
            oscGain = 0.0
            oscPhase = (oscPhase + oscPhaseInc * n) % TWO_PI
            return
        }

        val k = oscK
        val inc = oscPhaseInc
        val outCh = min(2, channels)
        var ph = oscPhase
        var idx = 0
        for (i in 0 until n) {
            g += (target - g) * k
            val v = (sin(ph) * g).toFloat()
            for (c in 0 until outCh) seg[idx + c] += v
            idx += channels
            ph += inc
            if (ph >= TWO_PI) ph -= TWO_PI
        }
        oscGain = g
        oscPhase = ph
    }

    // =====================================================================================
    //  envTick
    // =====================================================================================

    private fun tick() {
        if (!envActive) return

        val r = envRms()
        envPeak = max(r, envPeak * ENV_PEAK_DECAY)

        if (applied.mode == BassMode.DYN) {
            dynAvg += (r - dynAvg) * DYN_AVG_COEF
            val q = if (dynAvg > DYN_AVG_MIN) (r / dynAvg).coerceIn(DYN_Q_MIN, DYN_Q_MAX) else 1.0
            shelf.setTarget(shelfHz, SHELF_Q, dynBase + (q - 1.0) * DYN_SHELF_RANGE_DB, DYN_TAU_S)
        } else {
            val o = osc ?: return
            val n = if (envPeak > ENV_PEAK_MIN) r / envPeak else 0.0
            val tg = safeOscGain(o.amp * n * n)
            setOscTarget(tg, if (tg > oscLast) OSC_ATTACK_S else o.releaseS)
            oscLast = tg
        }
    }

    private fun envRms(): Double {
        var sum = 0.0
        for (v in envRing) sum += v.toDouble() * v.toDouble()
        return sqrt(sum / ENV_WINDOW)
    }

    /** hrOsc из эталона: безопасный gain синтетического саба (Speaker Safe). */
    private fun safeOscGain(v: Double): Double {
        var x = if (v.isFinite()) v else 0.0
        if (speakerSafe) x *= OSC_SAFE_SCALE
        return x.coerceIn(0.0, if (speakerSafe) OSC_SAFE_MAX else OSC_MAX)
    }

    /** Аналог osc.gain.setTargetAtTime(target, now, tau). */
    private fun setOscTarget(target: Double, tauS: Double) {
        oscTarget = target
        oscK = 1.0 - exp(-1.0 / (max(tauS, 1e-4) * sampleRate))
    }

    private companion object {
        const val CONTROL_RATE_HZ = 60.0        // как requestAnimationFrame в эталоне
        const val PARAM_TAU_S = 0.03            // setTargetAtTime(..., 0.03) в applyBass
        const val HP_Q_DB = 0.7                 // у highpass/lowpass в Web Audio Q задаётся в дБ
        const val SHELF_Q = 0.7                 // для shelf-фильтров Q не используется
        const val ENV_LOWPASS_HZ = 120.0
        const val ENV_WINDOW = 512              // fftSize анализатора; степень двойки
        const val ENV_PEAK_DECAY = 0.9995
        const val ENV_PEAK_MIN = 0.01
        const val DYN_AVG_COEF = 0.03
        const val DYN_AVG_MIN = 0.004
        const val DYN_Q_MIN = 0.7
        const val DYN_Q_MAX = 1.4
        const val DYN_SHELF_RANGE_DB = 3.5
        const val DYN_TAU_S = 0.05
        const val OSC_ATTACK_S = 0.012
        const val OSC_SAFE_SCALE = 0.6
        const val OSC_SAFE_MAX = 0.3
        const val OSC_MAX = 0.6
        const val OSC_SILENCE = 1e-5
        const val SETTLE_S = 0.8
        const val TWO_PI = 2.0 * PI
    }
}

/**
 * Компрессор/лимитер ступени баса с безопасным выключением.
 *
 * [Compressor] при ratio = 1 мгновенно выходит без обработки, и если в этот момент он
 * ослаблял сигнал на несколько дБ, получится щелчок. Поэтому при выключении сначала включается
 * «мягкая» нейтраль (ratio чуть больше 1, порог 0 дБ): ослабление плавно отпускается по release,
 * и только когда оно стало меньше [SETTLE_DB], включается настоящая нейтраль (нулевая нагрузка).
 */
private class BassDynamics(channels: Int, sampleRate: Int) {

    private enum class State { ACTIVE, SETTLING, NEUTRAL }

    private val comp = Compressor(channels, sampleRate)
    private var state = State.NEUTRAL

    val isNeutral: Boolean get() = state == State.NEUTRAL

    fun setActive(c: BassComp) {
        comp.configure(c)
        state = State.ACTIVE
    }

    fun setNeutral() {
        if (state == State.ACTIVE) {
            comp.configure(SOFT_NEUTRAL)
            state = State.SETTLING
        }
    }

    fun reset() = comp.reset()

    fun process(data: FloatArray, frames: Int) {
        when (state) {
            State.NEUTRAL -> return
            State.SETTLING -> if (comp.gainReductionDb > -SETTLE_DB) {
                comp.configure(BassConfig.COMP_NEUTRAL)
                comp.reset()
                state = State.NEUTRAL
                return
            }
            State.ACTIVE -> Unit
        }
        comp.process(data, frames)
    }

    private companion object {
        const val SETTLE_DB = 0.002
        /** Математически нейтральна для сигнала ниже 0 дБFS, но не включает быстрый выход. */
        val SOFT_NEUTRAL = BassComp(0.0, 0.0, 1.000001, 0.003, 0.1)
    }
}
