package com.moyamuzyka.player.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.moyamuzyka.player.audio.dsp.DspChain
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Аудио-приёмник Media3, который прогоняет весь звук через [DspChain] и отдаёт в систему 32-bit float.
 *
 * Зачем свой AudioSink, а не AudioProcessor:
 *  - в DefaultAudioSink пользовательские AudioProcessor работают только на целочисленном пути,
 *    где 24-bit FLAC сначала урезается до 16 бит, ещё до нашей обработки;
 *  - здесь вход (16 / 24 / 32-bit int или float) переводится в float ДО обработки, DSP считает
 *    в float/Double, а вниз по цепочке идёт PCM_FLOAT. Артефактов от урезания разрядности нет.
 *
 * Количество кадров на входе и выходе одинаковое, поэтому позиция воспроизведения не сбивается.
 * Несжатые форматы (passthrough AC3 и т. п.) идут без изменений.
 *
 * Потоки: [updateState] можно вызывать из любого потока. Остальные методы вызывает
 * плеер из потока воспроизведения.
 */
@UnstableApi
class DspAudioSink private constructor(private val delegate: AudioSink) : ForwardingAudioSink(delegate) {

    private class Dsp(val chain: DspChain, val encoding: Int, val channels: Int, val sampleRate: Int)

    @Volatile private var dsp: Dsp? = null
    @Volatile private var lastState: AudioState? = null

    // Рабочие буферы (растут по необходимости, не пересоздаются на каждый буфер)
    private var work = FloatArray(0)
    private var outBuf: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var pending: ByteBuffer? = null

    /** Передать новое состояние (EQ, бас, эффекты, лимитер и т. д.). Применяется на следующем блоке. */
    fun updateState(state: AudioState) {
        lastState = state
        dsp?.chain?.update(state)
    }

    // ---- Поддержка форматов: считаем, что на вход приёмника придёт float ----

    override fun supportsFormat(format: Format): Boolean = super.supportsFormat(asDspFormat(format))

    override fun getFormatSupport(format: Format): Int = super.getFormatSupport(asDspFormat(format))

    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val old = dsp
        if (isDspPcm(inputFormat)) {
            val reuse = old != null && old.encoding == inputFormat.pcmEncoding &&
                old.channels == inputFormat.channelCount && old.sampleRate == inputFormat.sampleRate
            if (!reuse) {
                val chain = DspChain(inputFormat.channelCount, inputFormat.sampleRate)
                lastState?.let { chain.update(it) }
                dsp = Dsp(chain, inputFormat.pcmEncoding, inputFormat.channelCount, inputFormat.sampleRate)
                old?.chain?.release()
            }
            super.configure(asDspFormat(inputFormat), specifiedBufferSize, outputChannels)
        } else {
            dsp = null
            old?.chain?.release()
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
        }
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val d = dsp ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

        var out = pending
        if (out == null) {
            if (!buffer.hasRemaining()) return true
            out = convertAndProcess(buffer, d)
            pending = out
        }
        // Если приёмник принял буфер не целиком, плеер вызовет метод снова с тем же буфером:
        // входной уже полностью потреблён, поэтому продолжаем отдавать сохранённый результат.
        val done = super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)
        if (done) pending = null
        return done
    }

    override fun flush() {
        pending = null
        dsp?.chain?.reset()
        super.flush()
    }

    override fun reset() {
        pending = null
        dsp?.chain?.release()
        dsp = null
        super.reset()
    }

    // =====================================================================================

    /** Вход (16/24/32-bit int или float) → float → DspChain → ByteBuffer с PCM_FLOAT. */
    private fun convertAndProcess(input: ByteBuffer, d: Dsp): ByteBuffer {
        val bytesPerSample = bytesPerSample(d.encoding)
        val frameBytes = bytesPerSample * d.channels
        val frames = input.remaining() / frameBytes
        val samples = frames * d.channels

        if (work.size < samples) work = FloatArray(samples)

        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var p = src.position()
        when (d.encoding) {
            C.ENCODING_PCM_16BIT -> for (i in 0 until samples) {
                work[i] = src.getShort(p) * SCALE_16
                p += 2
            }
            C.ENCODING_PCM_24BIT -> for (i in 0 until samples) {
                val v = (src.get(p + 2).toInt() shl 16) or
                    ((src.get(p + 1).toInt() and 0xFF) shl 8) or
                    (src.get(p).toInt() and 0xFF)
                work[i] = v * SCALE_24
                p += 3
            }
            C.ENCODING_PCM_32BIT -> for (i in 0 until samples) {
                work[i] = src.getInt(p) * SCALE_32
                p += 4
            }
            else -> for (i in 0 until samples) {      // C.ENCODING_PCM_FLOAT
                work[i] = src.getFloat(p)
                p += 4
            }
        }
        // Входной буфер считаем потреблённым целиком (целые кадры)
        input.position(input.position() + frames * frameBytes)

        d.chain.process(work, frames)

        val outBytes = samples * 4
        if (outBuf.capacity() < outBytes) {
            outBuf = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        }
        outBuf.clear()
        outBuf.limit(outBytes)
        outBuf.asFloatBuffer().put(work, 0, samples)
        outBuf.position(0)
        outBuf.limit(outBytes)
        return outBuf
    }

    private fun bytesPerSample(encoding: Int) = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        else -> 4
    }

    private fun isDspPcm(f: Format): Boolean =
        f.sampleMimeType == MimeTypes.AUDIO_RAW &&
            (f.pcmEncoding == C.ENCODING_PCM_16BIT || f.pcmEncoding == C.ENCODING_PCM_24BIT ||
                f.pcmEncoding == C.ENCODING_PCM_32BIT || f.pcmEncoding == C.ENCODING_PCM_FLOAT) &&
            f.channelCount in 1..8 && f.sampleRate > 0

    private fun asDspFormat(f: Format): Format =
        if (isDspPcm(f) && f.pcmEncoding != C.ENCODING_PCM_FLOAT)
            f.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
        else f

    companion object {
        private const val SCALE_16 = 1.0f / 32768f
        private const val SCALE_24 = 1.0f / 8388608f
        private const val SCALE_32 = 1.0f / 2147483648f

        /** Готовый приёмник: DefaultAudioSink с 32-bit float выходом, обёрнутый DSP. */
        @JvmStatic
        fun create(context: Context): DspAudioSink {
            val sink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .build()
            return DspAudioSink(sink)
        }
    }
}
