package com.voicesim.core.vc

import com.voicesim.core.dsp.Biquad
import com.voicesim.core.dsp.LinearResampler
import com.voicesim.core.inference.InferenceBackend
import com.voicesim.core.vc.rvc.RvcEngine
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RVC 转换器：把 48 kHz 窗口重采样到 16 kHz，交给 [RvcEngine]，
 * 再把模型输出（40k/48k）重采样回 48 kHz。
 *
 * hop 由模型决定：`segment_frames × 10 ms`（64 帧 → 640 ms）。
 * 块间做 10 ms 交叉淡化，抑制相位跳变带来的咔哒声。
 */
class RvcConverter(
    private val backendProvider: () -> InferenceBackend,
) : VoiceConverter {

    private lateinit var engine: RvcEngine

    override var sampleRate: Int = 48_000
        private set

    override var hopSamples: Int = 0
        private set

    override var contextSamples: Int = 0
        private set

    private var pitchSemitones: Int = 0

    private var to16k: LinearResampler = LinearResampler(48_000, 16_000)
    private var fromModel: LinearResampler = LinearResampler(40_000, 48_000)
    private var antiAlias: Biquad = Biquad.lowPass(48_000, 7_200f)

    private var inputScratch = FloatArray(0)
    private var buffer16k = FloatArray(0)
    private var buffer48k = FloatArray(0)
    private var previousTail = FloatArray(0)

    override fun prepare(profile: VoiceProfile, config: ConversionConfig) {
        engine = RvcEngine(backendProvider())
        engine.load(profile)
        engine.warmUp()

        sampleRate = config.sampleRate
        pitchSemitones = config.pitchSemitones.roundToInt()
        contextSamples = 0
        hopSamples = engine.segmentSamples * sampleRate / HUBERT_SR

        to16k = LinearResampler(sampleRate, HUBERT_SR)
        fromModel = LinearResampler(engine.outputSampleRate, sampleRate)
        antiAlias = Biquad.lowPass(sampleRate, 7_200f)
        antiAlias.reset()

        inputScratch = FloatArray(hopSamples)
        buffer16k = FloatArray(engine.segmentSamples + 64)
        buffer48k = FloatArray(hopSamples + sampleRate / 5)
        previousTail = FloatArray(min(config.crossfadeSamples, hopSamples))
    }

    override fun convert(inputWindow: FloatArray, output: FloatArray): Int {
        if (hopSamples <= 0) return 0

        val copyCount = min(inputWindow.size, inputScratch.size)
        System.arraycopy(inputWindow, 0, inputScratch, 0, copyCount)
        antiAlias.processInPlace(inputScratch, copyCount)

        val produced16k = to16k.process(inputScratch, copyCount, buffer16k)
        val segment = FloatArray(engine.segmentSamples)
        System.arraycopy(buffer16k, 0, segment, 0, min(produced16k, segment.size))

        val converted = engine.process(segment, pitchSemitones)
        val produced48k = fromModel.process(converted, converted.size, buffer48k)

        val count = min(hopSamples, produced48k)
        if (count > 0) {
            System.arraycopy(buffer48k, 0, output, 0, count)
        }
        if (count < hopSamples) {
            java.util.Arrays.fill(output, count, hopSamples, 0f)
        }

        crossfade(output)
        return hopSamples
    }

    override fun reset() {
        to16k.reset()
        fromModel.reset()
        antiAlias.reset()
        previousTail.fill(0f)
        if (::engine.isInitialized) engine.reset()
    }

    override fun close() {
        if (::engine.isInitialized) engine.close()
    }

    private fun crossfade(output: FloatArray) {
        val fade = previousTail.size
        if (fade <= 0) return

        for (i in 0 until fade) {
            val ratio = i.toFloat() / fade
            output[i] = previousTail[i] * (1f - ratio) + output[i] * ratio
        }
        System.arraycopy(output, hopSamples - fade, previousTail, 0, fade)
    }

    private companion object {
        const val HUBERT_SR = 16_000
    }
}
