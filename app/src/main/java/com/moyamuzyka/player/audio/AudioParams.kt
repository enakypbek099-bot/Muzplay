package com.moyamuzyka.player.audio

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh

/* ======================= МАТЕМАТИКА (HTML: db2g, qFor, interp, r5, cl) ======================= */
object AudioMath {
    fun dbToGain(db: Double) = 10.0.pow(db / 20.0)
    fun clamp(v: Double, m: Double) = max(-m, min(m, v))
    fun roundHalf(v: Double) = Math.round(v * 2.0) / 2.0

    fun qForBands(n: Int): Double {
        val bw = max(0.3, 9.97 / (n - 1))
        return 1.0 / (2.0 * sinh(ln(2.0) / 2.0 * bw))
    }

    /** Логарифмическая интерполяция (HTML: interp). */
    fun interp(fs: List<Double>, gs: List<Double>, f: Double): Double {
        fun lg2(x: Double) = ln(x) / ln(2.0)
        val x = lg2(f)
        val l = fs.size
        if (x <= lg2(fs[0])) return gs[0]
        if (x >= lg2(fs[l - 1])) return gs[l - 1]
        var i = 0
        while (lg2(fs[i + 1]) < x) i++
        val a = lg2(fs[i]); val b = lg2(fs[i + 1])
        return gs[i] + (gs[i + 1] - gs[i]) * (x - a) / (b - a)
    }
}

/* ======================= ЭКВАЛАЙЗЕР ======================= */
object EqConfig {
    private val ISO: Map<Int, List<Double>> = mapOf(
        5 to listOf(60.0, 230.0, 910.0, 3600.0, 14000.0),
        10 to listOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0),
        12 to listOf(32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 6000.0, 8000.0, 12000.0, 16000.0),
        15 to listOf(25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0),
        31 to listOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
            800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0)
    )

    val BAND_COUNTS = listOf(5, 10, 12, 15, 16, 24, 31, 32)
    val B10: List<Double> = ISO.getValue(10)
    const val GAIN_MAX_DB = 15.0      // полоса: ±15 дБ
    const val PREAMP_MAX_DB = 24.0    // преамп: ±24 дБ

    private fun genF(n: Int): List<Double> = List(n) { i ->
        val v = 20.0 * 1000.0.pow(i.toDouble() / (n - 1))
        val p = 10.0.pow(floor(log10(v)) - 1)
        Math.round(v / p) * p
    }

    fun bandFreqs(n: Int): List<Double> = ISO[n] ?: genF(n)

    class Preset(val name: String, val gains10: List<Double>)

    val PRESETS = listOf(
        Preset("Плоский", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        Preset("Рок", listOf(4.5, 3.5, 2.5, 1.0, -1.0, -1.5, 0.5, 3.0, 4.0, 4.5)),
        Preset("Поп", listOf(-1.5, 2.5, 4.0, 4.5, 3.0, 0.0, -1.5, -1.5, -1.5, -1.5)),
        Preset("Джаз", listOf(3.5, 2.5, 1.0, 2.0, -1.5, -1.5, 0.0, 1.5, 2.5, 3.5)),
        Preset("Классика", listOf(3.0, 2.5, 2.0, 1.5, -0.5, -0.5, 0.0, 2.0, 2.5, 3.0)),
        Preset("Блюз", listOf(2.5, 1.5, 0.0, -1.0, -2.0, -1.0, 0.0, 1.5, 2.5, 3.0)),
        Preset("Метал", listOf(4.0, 3.0, 0.0, -1.5, -3.0, -1.0, 2.0, 4.5, 5.5, 5.5)),
        Preset("Хип-хоп", listOf(5.5, 5.0, 3.5, 2.5, -1.0, -1.0, 1.0, -0.5, 1.5, 2.5)),
        Preset("Электроника", listOf(4.5, 4.0, 1.5, 0.0, -2.0, 2.0, 1.0, 1.5, 4.0, 4.5)),
        Preset("Танцевальная", listOf(5.5, 6.5, 4.0, 0.0, 0.0, -2.0, -3.0, -3.0, 0.0, 0.0)),
        Preset("Кантри", listOf(-1.0, 0.5, 1.5, 2.5, 2.0, 1.0, 0.0, 2.0, 3.0, 3.0)),
        Preset("Регги", listOf(0.0, 0.0, -0.5, -4.5, 0.0, 4.0, 4.0, 0.0, 0.0, 0.0)),
        Preset("Латино", listOf(3.0, 1.5, 0.0, 0.0, -1.5, -1.5, -1.5, 0.0, 3.5, 5.0)),
        Preset("Акустика", listOf(4.5, 4.0, 3.0, 1.0, 1.5, 1.5, 3.0, 3.5, 3.0, 2.0)),
        Preset("Вокал", listOf(-3.0, -3.0, -2.0, 1.0, 3.5, 3.5, 3.0, 1.5, 0.0, -1.5)),
        Preset("Усиление баса", listOf(6.0, 5.0, 4.0, 2.5, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        Preset("Усиление высоких", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.5, 3.5, 5.0, 6.0, 7.0)),
        Preset("Живое выступление", listOf(-2.0, 0.0, 2.0, 3.0, 3.5, 3.5, 3.0, 2.0, 1.5, 1.5)),
        Preset("Лаунж", listOf(-2.5, -1.5, 0.0, 2.0, 3.0, 2.5, 1.0, 0.0, 1.5, 0.5)),
        Preset("R&B", listOf(3.0, 6.5, 5.0, 1.5, -2.0, -1.0, 2.0, 2.5, 3.0, 3.5)),
        Preset("Наушники", listOf(3.0, 5.5, 2.5, -1.5, -1.0, 1.0, 2.5, 3.5, 5.0, 6.0)),
        Preset("Подкаст", listOf(-3.0, -2.0, -1.0, 1.0, 3.0, 4.0, 4.0, 3.0, 1.0, 0.0))
    )
}

class EqState {
    var n = 10
    var freqs: List<Double> = EqConfig.bandFreqs(10)
    var gains = DoubleArray(10)
    var preampDb = 0.0
    var preset: String? = "Плоский"

    fun applyPreset(p: EqConfig.Preset) {
        preset = p.name
        gains = DoubleArray(n) {
            AudioMath.clamp(AudioMath.roundHalf(AudioMath.interp(EqConfig.B10, p.gains10, freqs[it])), EqConfig.GAIN_MAX_DB)
        }
    }

    /** HTML: setCount — смена количества полос. */
    fun setBandCount(newN: Int) {
        val pv = EqConfig.PRESETS.firstOrNull { it.name == preset }
        val of = freqs; val og = gains.toList()
        n = newN
        freqs = EqConfig.bandFreqs(newN)
        gains = DoubleArray(newN) {
            val v = if (pv != null) AudioMath.interp(EqConfig.B10, pv.gains10, freqs[it])
            else AudioMath.interp(of, og, freqs[it])
            AudioMath.clamp(AudioMath.roundHalf(v), EqConfig.GAIN_MAX_DB)
        }
    }

    fun reset() { preampDb = 0.0; preset = "Плоский"; gains = DoubleArray(n) }
    fun q() = AudioMath.qForBands(n)
}

/* ======================= ТОН + ЛИМИТЕР ======================= */
class ToneState {
    var low = 0          // 0..100 -> lowshelf 120 Гц, 0..+12 дБ
    var high = 0         // 0..100 -> highshelf 8000 Гц, 0..+12 дБ
    var limiterOn = false
    fun lowDb() = low / 100.0 * 12.0
    fun highDb() = high / 100.0 * 12.0
    companion object { const val LOW_SHELF_HZ = 120.0; const val HIGH_SHELF_HZ = 8000.0 }
}

/** Лимитер (HTML: HIRES.lim + applyLim). Время в секундах. */
data class LimiterParams(
    val thresholdDb: Double, val kneeDb: Double, val ratio: Double,
    val attackS: Double, val releaseS: Double, val ceilingDb: Double
)

class LimiterConfig {
    var ceilDb = -1.0; var thrDb = -1.0; var atkMs = 3.0; var relMs = 100.0; var ratio = 20.0
    fun active() = LimiterParams(thrDb, 0.0, ratio, atkMs / 1000.0, relMs / 1000.0, ceilDb)
    companion object {
        /** Защита при ВЫКЛЮЧЕННОМ лимитере (HTML: ветка else в applyLim). */
        val SAFETY = LimiterParams(-1.5, 0.0, 20.0, 0.004, 0.18, 0.0)
        val RANGES = mapOf(
            "ceil" to (-6.0..0.0), "thr" to (-24.0..0.0), "atk" to (1.0..30.0),
            "rel" to (10.0..500.0), "ratio" to (2.0..20.0)
        )
    }
}

/* ======================= БАС ======================= */
enum class BassMode(val title: String) {
    INFRA("Инфра бас"), CLEAN("Чистый бас"), DEEP("Глубокий бас"),
    HARD("Хард бас"), DYN("Динамический бас")
}

data class BassPeak(val freqHz: Double, val q: Double, val gainDb: Double)
data class BassComp(val thrDb: Double, val kneeDb: Double, val ratio: Double, val attackS: Double, val releaseS: Double)
data class SubOsc(val freqHz: Double, val amp: Double, val releaseS: Double)
data class BassProfile(
    val shelfHz: Double, val shelfDb: Double,
    val p1: BassPeak, val p2: BassPeak, val p3: BassPeak,
    val comp: BassComp?, val osc: SubOsc?, val trimDb: Double
)

object BassConfig {
    const val DYN_MIN_DB = -6.0; const val DYN_MAX_DB = 6.0; const val DYN_STEP = 0.5
    const val HIGHPASS_HZ = 18.0
    /** Нейтральное значение компрессора, когда он выключен (HTML: [0,0,1,.01,.1]). */
    val COMP_NEUTRAL = BassComp(0.0, 0.0, 1.0, 0.01, 0.1)
    val LIMITER_ON = BassComp(-2.0, 2.0, 20.0, 0.003, 0.08)
    val LIMITER_OFF = BassComp(0.0, 0.0, 1.0, 0.003, 0.1)

    val OFF = BassProfile(80.0, 0.0, BassPeak(100.0, 1.0, 0.0), BassPeak(100.0, 1.0, 0.0), BassPeak(100.0, 1.0, 0.0), null, null, 0.0)

    private val BM = mapOf(
        BassMode.INFRA to BassProfile(45.0, 9.0, BassPeak(100.0, 1.0, -2.5), BassPeak(60.0, 1.0, 0.0), BassPeak(140.0, 1.0, 0.0),
            null, SubOsc(30.0, 0.5, 0.12), -3.0),
        BassMode.CLEAN to BassProfile(40.0, -2.0, BassPeak(65.0, 1.1, 4.0), BassPeak(105.0, 1.0, 2.5), BassPeak(230.0, 1.0, -3.0),
            null, null, -2.5),
        BassMode.DEEP to BassProfile(90.0, 6.0, BassPeak(55.0, 1.3, 5.0), BassPeak(140.0, 0.8, 1.5), BassPeak(140.0, 1.0, 0.0),
            BassComp(-26.0, 12.0, 3.0, 0.02, 0.3), SubOsc(48.0, 0.45, 0.35), -4.0),
        BassMode.HARD to BassProfile(80.0, 8.0, BassPeak(55.0, 1.2, 7.0), BassPeak(90.0, 1.0, 4.0), BassPeak(140.0, 1.0, 2.0),
            BassComp(-22.0, 6.0, 4.0, 0.01, 0.12), SubOsc(45.0, 0.55, 0.15), -6.0)
    )

    fun profile(mode: BassMode?, dynDb: Double): BassProfile = when (mode) {
        null -> OFF
        BassMode.DYN -> BassProfile(
            120.0, 4.0 + dynDb,
            BassPeak(80.0, 0.8, 2.0 + dynDb * 0.5), BassPeak(140.0, 1.0, 0.0), BassPeak(140.0, 1.0, 0.0),
            BassComp(-24.0, 12.0, 2.5, 0.012, 0.16), null,
            -(max(0.0, 4.0 + dynDb) * 0.5 + 1.0)
        )
        else -> BM.getValue(mode)
    }
}

class BassState { var mode: BassMode? = null; var dynDb = 0.0 }

/* ======================= FX: РЕВЕРБ, УСИЛЕНИЕ, ВИРТУАЛИЗАТОР, ЛИМИТЫ ======================= */
class FxKnob(var on: Boolean = false, var v: Int = 0)   // v: 0..100

object ReverbConfig {
    /** [Размер, Затухание, Предзадержка, Ширина, Тон, Микс] */
    class Preset(val name: String, val v: IntArray)
    val DEFAULT = intArrayOf(45, 45, 20, 85, 75, 40)
    val PRESETS = listOf(
        Preset("Студия", intArrayOf(4, 70, 0, 50, 80, 15)),
        Preset("Телестудия", intArrayOf(8, 88, 3, 30, 40, 32)),
        Preset("Ванная", intArrayOf(14, 0, 0, 60, 100, 62)),
        Preset("Пластина", intArrayOf(30, 8, 10, 100, 95, 50)),
        Preset("Малый зал", intArrayOf(38, 40, 15, 80, 65, 45)),
        Preset("Концертный зал", intArrayOf(72, 28, 35, 100, 78, 56)),
        Preset("Собор", intArrayOf(95, 18, 45, 100, 42, 72)),
        Preset("Пещера", intArrayOf(100, 4, 85, 90, 28, 78)),
        Preset("Стадион", intArrayOf(85, 45, 100, 100, 92, 70))
    )
    val KNOBS = listOf("Размер", "Затухание", "Предзадержка", "Ширина", "Тон", "Микс")

    fun irSeconds(v: IntArray) = 0.3 + v[0] / 100.0 * 3.7
    fun preDelayS(v: IntArray) = v[2] / 100.0 * 0.1
    fun lowpassHz(v: IntArray) = 1500.0 * (16000.0 / 1500.0).pow(v[4] / 100.0)
    fun wetGain(v: IntArray, on: Boolean) = if (on) v[5] / 100.0 * 2.8 else 0.0
    fun dryGain(v: IntArray, on: Boolean) = if (on) 1.0 - v[5] / 100.0 * 0.55 else 1.0
}

class FxState {
    val rv = FxKnob(); var rvValues = ReverbConfig.DEFAULT.copyOf(); var rvPreset: String? = null
    val gn = FxKnob()          // Усиление
    val bb = FxKnob()          // Усиление басов
    val vz = FxKnob()          // Виртуализатор
    var vol = 100              // Громкость 0..100 %
    var balance = 0.0          // Аудио баланс −5..+5 (шаг 0.5)

    /** Блок «Лимиты» (Пределы усиления). */
    var limitsOn = false
    var maxBassDb = 15
    var maxGainDb = 20
    var bassFreqHz = 80

    /** HTML: fxE() — максимумы по умолчанию, если «Лимиты» выключены. */
    fun effMaxBassDb() = if (limitsOn) maxBassDb else FxLimits.MAX_BASS_DB
    fun effMaxGainDb() = if (limitsOn) maxGainDb else FxLimits.MAX_GAIN_DB
    fun effBassHz() = if (limitsOn) bassFreqHz else FxLimits.BASS_HZ_DEFAULT

    fun ampOn() = gn.on || bb.on || limitsOn

    // ---- Реальные значения DSP (HTML: fxApply) ----
    fun gainDb() = if (gn.on) gn.v / 100.0 * effMaxGainDb() else 0.0           // до +20 дБ
    fun bassBoostDb() = if (bb.on) bb.v / 100.0 * effMaxBassDb() else 0.0      // до +15 дБ
    fun bassShelfHz() = min(400.0, effBassHz() * 1.8)
    fun bassPeakHz() = effBassHz().toDouble()
    fun bassPeakDb() = bassBoostDb() * 0.6
    fun volumeGain() = (vol / 100.0).pow(2)                                    // (vol/100)²
    fun virtSideGain() = 1.0 + (if (vz.on) vz.v / 100.0 else 0.0) * 2.0
    fun virtWetGain() = (if (vz.on) vz.v / 100.0 else 0.0) * 0.85
    fun reset() {
        rv.on = false; rvPreset = null; rvValues = ReverbConfig.DEFAULT.copyOf()
        gn.on = false; gn.v = 0; bb.on = false; bb.v = 0; vz.on = false; vz.v = 0
        vol = 100; limitsOn = false; maxBassDb = 15; maxGainDb = 20; bassFreqHz = 80; balance = 0.0
    }
}

object FxLimits {
    const val MAX_BASS_DB = 15        // +15 дБ (бас)
    const val MAX_GAIN_DB = 20        // +20 дБ (громкость)
    const val BASS_HZ_MIN = 30        // 30 Гц
    const val BASS_HZ_MAX = 200       // 200 Гц
    const val BASS_HZ_STEP = 5
    const val BASS_HZ_DEFAULT = 80
    const val VIRT_DELAY_S = 0.012
}

/* ======================= ЛАБОРАТОРИЯ ======================= */
class LabState {
    var voiceSemitones = 0        // Голос: −12..+12
    var speed = 0.0               // Скорость: −3..+3, шаг 0.5
    var balance = 0.0             // Баланс: −5..+5, шаг 0.5

    /** HTML: applySpeed */
    fun playbackRate(): Float {
        val r = if (speed >= 0) 1.0 + speed * 2.0 / 3.0 else 1.0 / (1.0 + (-speed) * 2.0 / 3.0)
        return r.toFloat()
    }

    /** HTML: applyLab — общий пан = (LAB.bl + FX.bal)/5, диапазон [-1..1] */
    fun pan(fxBalance: Double) = max(-1.0, min(1.0, (balance + fxBalance) / 5.0))
}

/* ======================= НАУШНИКИ ======================= */
data class HpFilter(val type: String, val freqHz: Double, val q: Double, val gainDb: Double)

data class HeadphoneProfile(
    val key: String, val name: String, val tagline: String, val bassMode: BassMode,
    val width: Double, val delayS: Double, val eq: List<HpFilter>,
    val shelfHz: Double, val shelfDb: Double,
    val peakHz: Double, val peakDb: Double, val peakQ: Double
)

class HpState { var key: String = ""; var on = true; var level = 3 /* 0..5 */ ; var linkedBassMode: BassMode? = null /* HTML: HP.bm */ ; fun k() = if (on) level / 3.0 else 0.0 }

object HeadphoneConfig {
    private fun pk(f: Double, q: Double, g: Double) = HpFilter("peaking", f, q, g)
    private fun hs(f: Double, q: Double, g: Double) = HpFilter("highshelf", f, q, g)

    val PROFILES: Map<String, HeadphoneProfile> = linkedMapOf(
        "apple" to HeadphoneProfile("apple", "Apple AirPods Pro", "Тёплый · Сдержанный бас", BassMode.CLEAN, 0.7, 0.0002,
            listOf(pk(200.0, .9, 1.5), pk(3000.0, 1.0, 2.0), pk(6500.0, 1.2, -2.5), hs(10000.0, .7, -3.5)), 80.0, 3.5, 130.0, 1.2, 1.0),
        "pixel" to HeadphoneProfile("pixel", "Google Pixel Buds Pro", "Акцент на басе · V-образный", BassMode.DEEP, 0.8, 0.0003,
            listOf(pk(1200.0, .8, -3.0), pk(3000.0, 1.0, -1.0), pk(6500.0, 1.2, 3.0), hs(11000.0, .7, 1.5)), 100.0, 5.5, 150.0, 1.5, 0.9),
        "sony" to HeadphoneProfile("sony", "Sony WH-1000XM6", "Динамичный · Глубокий бас", BassMode.DYN, 1.15, 0.0005,
            listOf(pk(300.0, .9, -2.0), pk(3000.0, 1.0, 2.5), pk(6000.0, 1.3, 3.0), hs(12000.0, .7, 2.0)), 90.0, 4.5, 45.0, 2.0, 1.2),
        "sundara" to HeadphoneProfile("sundara", "HiFiMAN Sundara", "Лёгкий · Планарный бас", BassMode.INFRA, 1.5, 0.0012,
            listOf(pk(200.0, .9, 2.0), pk(3000.0, 1.0, -2.0), pk(9000.0, 2.0, 4.0), hs(13000.0, .7, -1.0)), 80.0, -1.5, 60.0, -2.0, 0.8),
        "jbl" to HeadphoneProfile("jbl", "JBL WAVE 300 TWS", "Плотный · Мощный бас", BassMode.HARD, 0.6, 0.0002,
            listOf(pk(200.0, .9, 2.5), pk(1500.0, .9, -3.5), pk(3500.0, 1.0, -2.0), hs(8000.0, .7, -4.5)), 100.0, 7.0, 55.0, 3.0, 1.1),
        "bose" to HeadphoneProfile("bose", "Bose QuietComfort Ultra", "Тёплый · Объёмный бас", BassMode.DEEP, 0.9, 0.0003,
            listOf(pk(150.0, .9, 1.5), pk(2500.0, 1.0, 1.5), pk(6000.0, 1.3, -1.5), hs(10000.0, .7, 1.0)), 85.0, 4.0, 120.0, 1.5, 1.0),
        "airmax" to HeadphoneProfile("airmax", "Apple AirPods Max", "Сбалансированный · Тёплый", BassMode.CLEAN, 1.1, 0.0004,
            listOf(pk(250.0, .9, 1.0), pk(3000.0, 1.0, 2.0), pk(7000.0, 1.2, -1.5), hs(11000.0, .7, 2.0)), 80.0, 3.0, 110.0, 1.0, 1.0),
        "beats" to HeadphoneProfile("beats", "Beats Studio Pro", "Мощный бас · Яркий вокал", BassMode.HARD, 0.85, 0.0003,
            listOf(pk(200.0, .9, 2.0), pk(1500.0, .9, -2.0), pk(4000.0, 1.0, 2.5), hs(9000.0, .7, 1.0)), 90.0, 6.0, 60.0, 2.5, 1.1),
        "galaxy" to HeadphoneProfile("galaxy", "Samsung Galaxy Buds3 Pro", "Чистый · Чёткие верха", BassMode.DYN, 0.75, 0.0002,
            listOf(pk(180.0, .9, 1.0), pk(2800.0, 1.0, 2.0), pk(6500.0, 1.3, 2.0), hs(11000.0, .7, 1.5)), 85.0, 3.5, 100.0, 1.2, 1.0),
        "hd600" to HeadphoneProfile("hd600", "Sennheiser HD 600", "Нейтральный · Естественный вокал", BassMode.CLEAN, 1.4, 0.001,
            listOf(pk(100.0, .9, 1.5), pk(3000.0, 1.0, -1.0), pk(8000.0, 1.5, 2.0), hs(12000.0, .7, 1.5)), 70.0, 1.0, 90.0, 0.5, 1.0),
        "momentum" to HeadphoneProfile("momentum", "Sennheiser Momentum 4", "Тёплый · Мягкий бас", BassMode.DYN, 1.0, 0.0004,
            listOf(pk(120.0, .9, 1.5), pk(2500.0, 1.0, 1.0), pk(7000.0, 1.3, -1.0), hs(11000.0, .7, 1.0)), 80.0, 3.5, 110.0, 1.2, 1.0),
        "m50x" to HeadphoneProfile("m50x", "Audio-Technica ATH-M50x", "Студийный · V-образный", BassMode.DEEP, 1.2, 0.0006,
            listOf(pk(100.0, .9, 2.0), pk(1500.0, .9, -2.0), pk(5000.0, 1.2, 2.0), hs(10000.0, .7, 2.5)), 80.0, 3.5, 90.0, 1.5, 1.0),
        "dt770" to HeadphoneProfile("dt770", "Beyerdynamic DT 770 Pro", "Яркие верха · Плотный бас", BassMode.HARD, 1.25, 0.0008,
            listOf(pk(90.0, .9, 2.5), pk(2000.0, .9, -1.5), pk(8000.0, 1.6, 4.0), hs(12000.0, .7, 2.0)), 75.0, 4.5, 80.0, 2.0, 1.0),
        "xm5" to HeadphoneProfile("xm5", "Sony WF-1000XM5", "Компактный · Глубокий бас", BassMode.DEEP, 0.7, 0.0002,
            listOf(pk(150.0, .9, 1.5), pk(3000.0, 1.0, 2.5), pk(6500.0, 1.3, 1.5), hs(11000.0, .7, 1.0)), 90.0, 4.5, 50.0, 1.8, 1.1),
        "marshall" to HeadphoneProfile("marshall", "Marshall Major V", "Рок-звук · Яркие середины", BassMode.DYN, 0.8, 0.0003,
            listOf(pk(200.0, .9, 1.5), pk(1800.0, .9, 2.0), pk(4500.0, 1.2, 2.5), hs(9000.0, .7, -0.5)), 85.0, 3.0, 100.0, 1.2, 1.0),
        "focal" to HeadphoneProfile("focal", "Focal Utopia", "Чёткий · Нейтральный бас", BassMode.CLEAN, 1.6, 0.0015,
            listOf(pk(2500.0, 1.0, 2.5), pk(5000.0, 1.2, 1.5), pk(9000.0, 1.5, 1.0), hs(12000.0, .7, 3.0)), 70.0, 0.0, 110.0, 0.0, 1.0)
    )

    /** HTML: выходное ослабление (дБ) в конце applyHP. */
    fun outputTrimDb(p: HeadphoneProfile?, k: Double): Double {
        if (p == null) return 0.0
        val maxEq = max(0.0, p.eq.maxOf { it.gainDb })
        return -(maxEq * 0.6 + max(0.0, p.shelfDb * k) * 0.55 + 1.0)
    }
}

/* ======================= ИММЕРСИВНЫЙ ЗВУК («360 SOUND», «8D») ======================= */
enum class ImmersiveMode(val title: String) { M360("360 SOUND") }

class ImmersiveState {
    var on = false
    var mode: ImmersiveMode? = null
    var d8 = 0                         // 0 = выкл, 1 = 8D (a), 2 = 8D (b)
    var dynamicAudioOn = false
    var dynamicAudioLevel = 70         // 0..100 %

    /** HTML: effKey() */
    fun effectiveKey(): String? = when (d8) { 1 -> "d8a"; 2 -> "d8b"; else -> mode?.name?.lowercase() }
}

/* ======================= HI-RES ПАРАМЕТРЫ (HTML: HIRES) ======================= */
class HiResConfig {
    var precisionF64 = true
    var speakerSafe = true
    val limiter = LimiterConfig()
    var compressorOn = false
    val comp = BassComp(-24.0, 12.0, 3.0, 0.010, 0.200)
}

/* ======================= ЕДИНОЕ СОСТОЯНИЕ + ПРАВИЛО ЭКСКЛЮЗИВНОСТИ ======================= */
class AudioState {
    val eq = EqState()
    val tone = ToneState()
    val bass = BassState()
    val fx = FxState()
    val lab = LabState()
    val hp = HpState()
    val imm = ImmersiveState()
    val hires = HiResConfig()

    /**
     * ПРАВИЛО №5 ИЗ ПРОМПТА: при включении «Иммерсивного звука» эквалайзер, лимитер
     * и все тяжёлые эффекты (реверб, усиление басов, виртуализатор, усиление,
     * лимиты, бас-режимы, профиль наушников) отключаются автоматически.
     * Параметры не сбрасываются (вернутся при выключении режима), в DSP они просто нейтрализуются.
     */
    val effectsLocked get() = imm.on
    val eqActive get() = !effectsLocked
    val limiterActive get() = !effectsLocked && tone.limiterOn
    val reverbActive get() = !effectsLocked && fx.rv.on
    val bassBoostActive get() = !effectsLocked && fx.bb.on
    val virtualizerActive get() = !effectsLocked && fx.vz.on
    val gainActive get() = !effectsLocked && fx.gn.on
    val bassModeActive get() = if (effectsLocked) null else bass.mode
    val headphoneActive get() = if (effectsLocked) null else HeadphoneConfig.PROFILES[hp.key]

    /** Взаимная блокировка усиления и баса (тексты toast как в эталоне). null = разрешено. */
    fun blockReasonForAmp(): String? =
        bass.mode?.let { "Сначала отключите «Бас»" }

    fun blockReasonForBass(target: BassMode): String? = when {
        bass.mode == target -> null
        bass.mode != null -> "Сначала отключите «${bass.mode!!.title}»"
        fx.ampOn() -> "Сначала отключите «Усиление»"
        else -> null
    }
}
