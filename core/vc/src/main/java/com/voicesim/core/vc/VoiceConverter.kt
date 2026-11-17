package com.voicesim.core.vc

import com.voicesim.core.inference.InferenceBackend
import kotlin.math.min

/**
 * 转换器协议 —— 整套框架唯一的音色扩展点。
 *
 * 生命周期：`prepare()` → 多次 `convert()` → `close()`。
 * `convert()` 运行在实时音频线程，必须：
 * - 不做阻塞 I/O、不加锁等待、不加载文件；
 * - 在 hop 时长内返回；
 * - 复用 `prepare()` 阶段分配好的缓冲，避免持续 GC。
 *
 * 失败语义：抛异常表示本块失败，管线会回退直通并计数，不会中断链路。
 */
interface VoiceConverter : AutoCloseable {

    /** 管线侧采样率（通常 48 kHz）。 */
    val sampleRate: Int

    /** 每次推进的样本数。 */
    val hopSamples: Int

    /** 每次推理需要的额外历史样本数（左侧上下文）。 */
    val contextSamples: Int

    fun prepare(profile: VoiceProfile, config: ConversionConfig)

    /**
     * @param inputWindow 长度必须为 [contextSamples] + [hopSamples]
     * @param output 至少 [hopSamples] 的缓冲
     * @return 实际写入 [output] 的样本数
     */
    fun convert(inputWindow: FloatArray, output: FloatArray): Int

    fun reset()
}

/**
 * 直通转换器：不做任何变声，用来验证音频链路、测延迟、作为降级兜底。
 */
class PassthroughConverter(
    override val sampleRate: Int,
    override val hopSamples: Int,
) : VoiceConverter {

    override val contextSamples: Int = 0

    override fun prepare(profile: VoiceProfile, config: ConversionConfig) = Unit

    override fun convert(inputWindow: FloatArray, output: FloatArray): Int {
        val n = min(hopSamples, min(inputWindow.size, output.size))
        System.arraycopy(inputWindow, inputWindow.size - n, output, 0, n)
        return n
    }

    override fun reset() = Unit

    override fun close() = Unit
}

enum class ConversionMode {
    /** P0：直通，验证链路与延迟。 */
    PASSTHROUGH,

    /** P2：RVC 三模型音色转换。 */
    RVC,
}

/**
 * 根据模式创建转换器。上层（Controller）只依赖这个工厂，
 * 以后增加 NCNN / TTS 方案时，UI 和管线都不用改。
 */
class ConverterFactory(private val backendProvider: () -> InferenceBackend) {

    fun create(mode: ConversionMode, config: ConversionConfig): VoiceConverter = when (mode) {
        ConversionMode.PASSTHROUGH -> PassthroughConverter(
            sampleRate = config.sampleRate,
            hopSamples = config.hopSamples,
        )

        ConversionMode.RVC -> RvcConverter(backendProvider)
    }
}
