/*
 * Portions derived from MeowRVC (https://github.com/xiaoxiaoyu-miao/MeowRVC),
 * licensed under the Apache License, Version 2.0.
 */
package com.voicesim.core.vc.rvc

import com.voicesim.core.dsp.RmvpeMel
import com.voicesim.core.inference.InferenceBackend
import com.voicesim.core.inference.ModelSession
import com.voicesim.core.inference.SessionConfig
import com.voicesim.core.inference.Tensor
import com.voicesim.core.vc.ModelManifest
import com.voicesim.core.vc.VoiceProfile
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * RVC v2 推理引擎（拆分 / 合并两种模型）。
 *
 * 单块固定为 [segmentFrames] × 10 ms（默认 64 帧 = 640 ms，16 kHz 下 10240 样本）。
 * 链路：HuBERT → 特征插值 → RMVPE/自相关 F0 → 音高偏移 → text_encoder → flow → generator。
 *
 * 说明：
 * - 本实现移植自 MeowRVC（Apache-2.0）的 RVCOnnxEngine，并按本项目接口重构；
 * - 不支持 index 检索融合（实时场景下 O(N×T×768) 太慢，留到离线模式）；
 * - 模型输入名按语义匹配，兼容不同导出脚本。
 */
class RvcEngine(
    private val backend: InferenceBackend,
    private val sessionConfig: SessionConfig = SessionConfig(
        threads = 2,
        useNnapi = false,
        useXnnpack = true,
    ),
) : AutoCloseable {

    private enum class Mode { SPLIT, COMBINED }

    private var mode = Mode.SPLIT

    private var targetSampleRate = 40_000
    private var segmentFrames = 64
    private var speakerId = 0L
    private var speakerEmbedding: FloatArray? = null
    private var f0Enabled = true

    private val random = Random(0x5EED)

    private var hubert: ModelSession? = null
    private var rmvpe: ModelSession? = null
    private var textEncoder: ModelSession? = null
    private var flow: ModelSession? = null
    private var generator: ModelSession? = null
    private var voice: ModelSession? = null

    /** 模型输出采样率（40 kHz / 48 kHz）。 */
    val outputSampleRate: Int get() = targetSampleRate

    /** 单块帧数（100 Hz）。 */
    val framesPerSegment: Int get() = segmentFrames

    /** 单块 16 kHz 样本数。 */
    val segmentSamples: Int get() = segmentFrames * HOP

    val isLoaded: Boolean get() = hubert != null

    fun load(profile: VoiceProfile) {
        close()

        val bundle = ModelManifest.parseBundle(profile)
        targetSampleRate = bundle.modelSampleRate
        segmentFrames = bundle.segmentFrames
        speakerId = bundle.speakerId.toLong()
        speakerEmbedding = bundle.speakerEmbedding?.let(::normalizeEmbedding)
        f0Enabled = bundle.f0
        mode = if (bundle.isCombined) Mode.COMBINED else Mode.SPLIT

        hubert = backend.open(bundle.hubertPath, sessionConfig)
        bundle.rmvpePath?.let { rmvpe = backend.open(it, sessionConfig) }

        if (mode == Mode.COMBINED) {
            val path = requireNotNull(bundle.combinedPath) { "combined model path missing" }
            voice = backend.open(path, sessionConfig)
        } else {
            textEncoder = backend.open(
                requireNotNull(bundle.textEncoderPath) { "text_encoder path missing" },
                sessionConfig,
            )
            flow = backend.open(
                requireNotNull(bundle.flowPath) { "flow path missing" },
                sessionConfig,
            )
            generator = backend.open(
                requireNotNull(bundle.generatorPath) { "generator path missing" },
                sessionConfig,
            )
        }

        validateSessions()
    }

    /**
     * 对一块 16 kHz 音频做转换。
     *
     * @param audio16k 长度不足 [segmentSamples] 时补零，超出部分截断
     * @param pitchSemitones 音高偏移（半音）
     * @return 模型采样率下的波形
     */
    fun process(audio16k: FloatArray, pitchSemitones: Int): FloatArray {
        check(isLoaded) { "RVC engine not loaded" }

        val segment = FloatArray(segmentSamples)
        System.arraycopy(audio16k, 0, segment, 0, min(audio16k.size, segment.size))

        val features = runHubert(segment)
        val phone = interpolateFeatures(features)

        val rawF0 = when {
            !f0Enabled -> FloatArray(segmentFrames)
            rmvpe != null -> runRmvpe(segment)
            else -> autoCorrelate(segment)
        }
        val f0 = medianFilter(rawF0, MEDIAN_RADIUS)

        val shift = 2.0.pow(pitchSemitones / 12.0).toFloat()
        val shiftedF0 = FloatArray(segmentFrames) { f0[it] * shift }
        val coarsePitch = LongArray(segmentFrames) { melToCoarse(shiftedF0[it]) }

        val audio = when (mode) {
            Mode.COMBINED -> runCombined(phone, coarsePitch, shiftedF0)
            Mode.SPLIT -> runSplit(phone, coarsePitch, shiftedF0)
        }
        return postProcess(audio)
    }

    fun reset() {
        // 目前所有状态都在单块内，保留接口给后续流式 F0/上下文使用。
    }

    /** 首帧前跑一次空块，把 ORT 的首次分配和算子编译开销移出实时路径。 */
    fun warmUp() {
        if (!isLoaded) return
        process(FloatArray(segmentSamples), 0)
    }

    override fun close() {
        runCatching { hubert?.close() }
        runCatching { rmvpe?.close() }
        runCatching { textEncoder?.close() }
        runCatching { flow?.close() }
        runCatching { generator?.close() }
        runCatching { voice?.close() }
        hubert = null
        rmvpe = null
        textEncoder = null
        flow = null
        generator = null
        voice = null
    }

    // ------------------------------------------------------------------ HuBERT

    private fun runHubert(audio: FloatArray): FloatArray {
        val session = requireNotNull(hubert)
        val size = audio.size.toLong()
        val names = session.inputs.map { it.name }

        val input: Map<String, Tensor> = when {
            "input_values" in names -> {
                val info = session.inputs.first { it.name == "input_values" }
                val shape = if (info.shape.size >= 3) {
                    longArrayOf(1, 1, size)
                } else {
                    longArrayOf(1, size)
                }
                mapOf("input_values" to Tensor.FloatTensor(shape, audio))
            }

            "source" in names && "padding_mask" in names -> {
                val sourceInfo = session.inputs.first { it.name == "source" }
                val rank = if (sourceInfo.shape.size >= 3) 3 else 2
                val sourceShape = if (rank == 3) {
                    longArrayOf(1, 1, size)
                } else {
                    longArrayOf(1, size)
                }
                val maskShape = if (rank == 3) longArrayOf(1, 1, size) else longArrayOf(1, size)
                mapOf(
                    "source" to Tensor.FloatTensor(sourceShape, audio),
                    "padding_mask" to Tensor.BoolTensor(maskShape, BooleanArray(audio.size)),
                )
            }

            else -> {
                val name = names.first()
                val shape = if (name.contains("source", ignoreCase = true)) {
                    longArrayOf(1, 1, size)
                } else {
                    longArrayOf(1, size)
                }
                mapOf(name to Tensor.FloatTensor(shape, audio))
            }
        }

        val output = session.run(input)
        val tensor = (output["features"] ?: output.values.firstOrNull())
            as? Tensor.FloatTensor
            ?: error("HuBERT output is not a float tensor: ${output.keys}")
        require(tensor.data.size >= FEAT_DIM) {
            "HuBERT output too small: ${tensor.data.size}, expected at least $FEAT_DIM"
        }

        // 兼容 [1, T, 768]（时间优先）和 [1, 768, T]（通道优先）两种导出
        val shape = tensor.shape
        if (shape.size == 3 && shape[1] == FEAT_DIM.toLong() && shape[2] != FEAT_DIM.toLong()) {
            val frames = shape[2].toInt()
            val timeMajor = FloatArray(frames * FEAT_DIM)
            for (channel in 0 until FEAT_DIM) {
                for (frame in 0 until frames) {
                    timeMajor[frame * FEAT_DIM + channel] = tensor.data[channel * frames + frame]
                }
            }
            return timeMajor
        }
        return tensor.data
    }

    private fun interpolateFeatures(features: FloatArray): FloatArray {
        val frames = features.size / FEAT_DIM
        require(frames > 0) { "empty HuBERT features" }

        val result = FloatArray(segmentFrames * FEAT_DIM)
        for (frame in 0 until segmentFrames) {
            val source = (frame.toLong() * frames / segmentFrames).toInt().coerceIn(0, frames - 1)
            System.arraycopy(
                features,
                source * FEAT_DIM,
                result,
                frame * FEAT_DIM,
                FEAT_DIM,
            )
        }
        return result
    }

    // -------------------------------------------------------------------- F0

    private fun runRmvpe(audio: FloatArray): FloatArray {
        val session = requireNotNull(rmvpe)
        val frameCount = ceil(segmentFrames / 32.0).toInt() * 32
        val mel = RmvpeMel.compute(audio, frameCount)
        val inputName = session.inputs.firstOrNull { it.name == "input" }?.name
            ?: session.inputs.first().name

        val output = session.run(
            mapOf(
                inputName to Tensor.FloatTensor(
                    longArrayOf(1, RmvpeMel.MEL_BINS.toLong(), frameCount.toLong()),
                    mel,
                ),
            ),
        )
        val tensor = (output["output"] ?: output.values.firstOrNull())
            as? Tensor.FloatTensor
            ?: error("RMVPE output is not a float tensor")
        val data = tensor.data
        require(data.size >= frameCount * PITCH_BINS) {
            "RMVPE output too small: ${data.size}, expected ${frameCount * PITCH_BINS}"
        }

        val f0 = FloatArray(segmentFrames)
        val binsFirst = tensor.shape.size >= 3 && tensor.shape[1].toInt() == PITCH_BINS

        fun confidence(frame: Int, bin: Int): Float {
            val f = min(frame, frameCount - 1)
            val index = if (binsFirst) bin * frameCount + f else f * PITCH_BINS + bin
            return data.getOrElse(index) { 0f }
        }

        for (frame in 0 until segmentFrames) {
            var bestBin = 0
            var bestConfidence = 0f
            for (bin in 0 until PITCH_BINS) {
                val value = confidence(frame, bin)
                if (value > bestConfidence) {
                    bestConfidence = value
                    bestBin = bin
                }
            }
            f0[frame] = if (bestConfidence >= VOICED_THRESHOLD) binToHz(bestBin) else 0f
        }
        return f0
    }

    private fun autoCorrelate(audio: FloatArray): FloatArray {
        val f0 = FloatArray(segmentFrames)
        val minLag = HUBERT_SR / F0_MAX.toInt()
        val maxLagLimit = HUBERT_SR / F0_MIN.toInt()

        for (frame in 0 until segmentFrames) {
            val start = frame * HOP
            val end = min(start + CORRELATION_WINDOW, audio.size)
            if (end - start < 320) break

            var energy = 0.0
            for (i in start until end) energy += audio[i].toDouble() * audio[i]
            val rms = sqrt(energy / (end - start))
            if (rms < 0.005) continue

            var bestCorrelation = 0f
            var bestLag = 0
            val maxLag = min(maxLagLimit, (end - start) / 2)
            for (lag in minLag..maxLag) {
                var correlation = 0f
                for (i in 0 until end - start - lag) {
                    correlation += audio[start + i] * audio[start + i + lag]
                }
                if (correlation > bestCorrelation) {
                    bestCorrelation = correlation
                    bestLag = lag
                }
            }
            if (bestLag > 0 && bestCorrelation > rms * rms * (end - start) * 0.3f) {
                f0[frame] = HUBERT_SR.toFloat() / bestLag
            }
        }

        var lastVoiced = -1
        for (i in f0.indices) {
            if (f0[i] > 0f) lastVoiced = i else if (lastVoiced >= 0) f0[i] = f0[lastVoiced]
        }
        return f0
    }

    private fun medianFilter(input: FloatArray, radius: Int): FloatArray {
        if (radius <= 0) return input
        val output = FloatArray(input.size)
        val window = ArrayList<Float>(radius * 2 + 1)
        for (i in input.indices) {
            window.clear()
            for (j in max(0, i - radius)..min(input.lastIndex, i + radius)) {
                if (input[j] > 0f) window.add(input[j])
            }
            output[i] = if (window.isEmpty()) {
                0f
            } else {
                window.sort()
                window[window.size / 2]
            }
        }
        return output
    }

    // --------------------------------------------------------------- generator

    private fun runSplit(
        phone: FloatArray,
        coarsePitch: LongArray,
        f0: FloatArray,
    ): FloatArray {
        val frames = segmentFrames
        val phoneTensor = Tensor.FloatTensor(
            longArrayOf(1, frames.toLong(), FEAT_DIM.toLong()),
            phone,
        )
        val pitchTensor = Tensor.LongTensor(longArrayOf(1, frames.toLong()), coarsePitch)
        val lengthTensor = Tensor.LongTensor(longArrayOf(1), longArrayOf(frames.toLong()))

        val encoder = requireNotNull(textEncoder)
        val encoderInputs = HashMap<String, Tensor>()
        for (info in encoder.inputs) {
            val name = info.name.lowercase()
            when {
                name.contains("length") -> encoderInputs[info.name] = lengthTensor
                name.contains("pitch") -> encoderInputs[info.name] = pitchTensor
                name.contains("phone") -> encoderInputs[info.name] = phoneTensor
            }
        }
        require(encoderInputs.size >= 3) {
            "text_encoder inputs not recognized: ${encoder.inputs.map { it.name }}"
        }

        val encoderOutput = encoder.run(encoderInputs)
        val m = requireFloatOutput(encoderOutput, "m")
        val logs = requireFloatOutput(encoderOutput, "logs")
        val mask = requireFloatOutput(encoderOutput, "x_mask")
        require(m.size >= FLOW_DIM * frames && logs.size >= FLOW_DIM * frames) {
            "text_encoder output size mismatch"
        }

        fun maskAt(frame: Int): Float = if (mask.size >= frames) mask[frame] else 1f

        val z = FloatArray(FLOW_DIM * frames)
        for (i in z.indices) {
            val frame = i % frames
            z[i] = (m[i] + exp(logs[i].toDouble()).toFloat() * gaussian() * 0.66666f) *
                maskAt(frame)
        }

        val speaker = speakerTensor()
        val flowSession = requireNotNull(flow)
        val flowInputs = HashMap<String, Tensor>()
        for (info in flowSession.inputs) {
            val name = info.name.lowercase()
            when {
                name.contains("mask") -> flowInputs[info.name] = Tensor.FloatTensor(
                    longArrayOf(1, 1, frames.toLong()),
                    FloatArray(frames) { maskAt(it) },
                )
                name == "g" || name.contains("speaker") -> flowInputs[info.name] = speaker
                name.contains("z_p") || name == "zp" -> flowInputs[info.name] = Tensor.FloatTensor(
                    longArrayOf(1, FLOW_DIM.toLong(), frames.toLong()),
                    z,
                )
            }
        }
        require(flowInputs.size >= 3) {
            "flow inputs not recognized: ${flowSession.inputs.map { it.name }}"
        }
        val flowOutput = flowSession.run(flowInputs)
        val z2 = requireFloatOutput(flowOutput, "z")
        require(z2.size >= FLOW_DIM * frames) { "flow output size mismatch" }
        val zMasked = FloatArray(FLOW_DIM * frames) {
            z2[it] * maskAt(it % frames)
        }

        val generatorSession = requireNotNull(generator)
        val generatorInputs = HashMap<String, Tensor>()
        for (info in generatorSession.inputs) {
            val name = info.name.lowercase()
            when {
                name.contains("f0") -> generatorInputs[info.name] =
                    Tensor.FloatTensor(longArrayOf(1, frames.toLong()), f0)
                name == "g" || name.contains("speaker") -> generatorInputs[info.name] = speaker
                name.contains("z") -> generatorInputs[info.name] = Tensor.FloatTensor(
                    longArrayOf(1, FLOW_DIM.toLong(), frames.toLong()),
                    zMasked,
                )
            }
        }
        require(generatorInputs.size >= 3) {
            "generator inputs not recognized: ${generatorSession.inputs.map { it.name }}"
        }
        val generated = generatorSession.run(generatorInputs)
        return requireFloatOutput(generated, "audio")
    }

    private fun runCombined(
        phone: FloatArray,
        coarsePitch: LongArray,
        f0: FloatArray,
    ): FloatArray {
        val session = requireNotNull(voice)
        val frames = segmentFrames
        val phoneTensor = Tensor.FloatTensor(
            longArrayOf(1, frames.toLong(), FEAT_DIM.toLong()),
            phone,
        )
        val pitchTensor = Tensor.LongTensor(longArrayOf(1, frames.toLong()), coarsePitch)
        val f0Tensor = Tensor.FloatTensor(longArrayOf(1, frames.toLong()), f0)
        val lengthTensor = Tensor.LongTensor(longArrayOf(1), longArrayOf(frames.toLong()))
        val sidTensor = Tensor.LongTensor(longArrayOf(1), longArrayOf(speakerId))
        val noiseTensor = Tensor.FloatTensor(
            longArrayOf(1, FLOW_DIM.toLong(), frames.toLong()),
            FloatArray(FLOW_DIM * frames) { gaussian() * 0.66666f },
        )

        val inputs = HashMap<String, Tensor>()
        for (info in session.inputs) {
            val name = info.name.lowercase()
            when {
                name.contains("length") -> inputs[info.name] = lengthTensor
                name.contains("pitchf") || name.contains("nsff0") || name == "f0" ->
                    inputs[info.name] = f0Tensor
                name.contains("rnd") || name.contains("noise") -> inputs[info.name] = noiseTensor
                name.contains("pitch") -> inputs[info.name] = pitchTensor
                name == "ds" || name.contains("sid") || name.contains("speaker") ->
                    inputs[info.name] = sidTensor
                name.contains("phone") || name.contains("feat") -> inputs[info.name] = phoneTensor
            }
        }
        require(inputs.size >= 4) {
            "combined model inputs not recognized: ${session.inputs.map { it.name }}"
        }

        val output = session.run(inputs)
        val floats = output.values.filterIsInstance<Tensor.FloatTensor>()
        val audio = floats.maxByOrNull { it.data.size }
            ?: error("combined model produced no float output")

        output.values.filterIsInstance<Tensor.IntTensor>()
            .firstOrNull()
            ?.data
            ?.firstOrNull()
            ?.let { sampleRate ->
                if (sampleRate in 8_000..96_000) targetSampleRate = sampleRate
            }

        return audio.data
    }

    // ------------------------------------------------------------------ utils

    private fun speakerTensor(): Tensor.FloatTensor {
        val embedding = speakerEmbedding ?: FloatArray(SPEAKER_DIM)
        return Tensor.FloatTensor(longArrayOf(1, SPEAKER_DIM.toLong(), 1), embedding)
    }

    private fun normalizeEmbedding(source: FloatArray): FloatArray {
        if (source.size == SPEAKER_DIM) return source
        val out = FloatArray(SPEAKER_DIM)
        System.arraycopy(source, 0, out, 0, min(source.size, SPEAKER_DIM))
        return out
    }

    private fun requireFloatOutput(output: Map<String, Tensor>, name: String): FloatArray {
        (output[name] as? Tensor.FloatTensor)?.let { return it.data }

        val floats = output.values.filterIsInstance<Tensor.FloatTensor>()
        require(floats.size == 1) {
            "cannot resolve output '$name' from ${output.keys}"
        }
        return floats.first().data
    }

    private fun postProcess(audio: FloatArray): FloatArray {
        var peak = 0f
        for (i in audio.indices) {
            val sample = audio[i]
            if (!sample.isFinite()) {
                audio[i] = 0f
                continue
            }
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
        }
        if (peak > 0.98f) {
            val gain = 0.98f / peak
            for (i in audio.indices) audio[i] *= gain
        }
        return audio
    }

    private fun validateSessions() {
        val h = requireNotNull(hubert) { "hubert session missing" }
        require(h.inputs.isNotEmpty() && h.outputs.isNotEmpty()) { "invalid hubert session" }
        when (mode) {
            Mode.SPLIT -> {
                requireNotNull(textEncoder) { "text_encoder session missing" }
                requireNotNull(flow) { "flow session missing" }
                requireNotNull(generator) { "generator session missing" }
            }
            Mode.COMBINED -> requireNotNull(voice) { "combined voice session missing" }
        }
    }

    private fun gaussian(): Float = random.nextGaussian().toFloat()

    private fun binToHz(bin: Int): Float =
        (10.0 * 2.0.pow((RMVPE_CENTS_BASE + RMVPE_CENTS_PER_BIN * bin) / 1200.0)).toFloat()

    private fun melToCoarse(f0: Float): Long {
        if (f0 <= 0f) return 1L
        val mel = 1127.0 * ln(f0 / 700.0 + 1.0)
        val minMel = 1127.0 * ln(50.0 / 700.0 + 1.0)
        val maxMel = 1127.0 * ln(1100.0 / 700.0 + 1.0)
        return ((mel - minMel) * 254.0 / (maxMel - minMel) + 1.0)
            .toLong()
            .coerceIn(1L, 255L)
    }

    private companion object {
        const val HUBERT_SR = 16_000
        const val HOP = 160
        const val FEAT_DIM = 768
        const val PITCH_BINS = 360
        const val FLOW_DIM = 192
        const val SPEAKER_DIM = 256
        const val F0_MIN = 50f
        const val F0_MAX = 1100f
        const val MEDIAN_RADIUS = 6
        const val CORRELATION_WINDOW = 640
        const val VOICED_THRESHOLD = 0.005f
        const val RMVPE_CENTS_BASE = 1997.3794084376191
        const val RMVPE_CENTS_PER_BIN = 20.0
    }
}
