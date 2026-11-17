package com.voicesim.core.vc

/**
 * 一次转换的参数。
 *
 * RVC 的实际 hop 由模型 `segment_frames` 决定（64 帧 = 640 ms），
 * [hopMs] 只是期望值；直通/DSP 转换器会按这里取值。
 */
data class ConversionConfig(
    val sampleRate: Int = 48_000,
    val hopMs: Int = 640,
    val contextMs: Int = 0,
    val pitchSemitones: Float = 0f,
    val formantShift: Float = 0f,
    val indexRate: Float = 0.5f,
    val protect: Float = 0.33f,
    val crossfadeMs: Int = 10,
) {
    val hopSamples: Int get() = sampleRate * hopMs / 1000
    val contextSamples: Int get() = sampleRate * contextMs / 1000
    val crossfadeSamples: Int get() = sampleRate * crossfadeMs / 1000

    companion object {
        /** P0 直通模式：20 ms hop，零上下文，延迟最低。 */
        fun passthrough(sampleRate: Int, frameMs: Int = 20): ConversionConfig =
            ConversionConfig(
                sampleRate = sampleRate,
                hopMs = frameMs,
                contextMs = 0,
                crossfadeMs = 5,
            )

        /** RVC 标准：640 ms 块，对应 64 帧模型。 */
        fun standard(sampleRate: Int = 48_000): ConversionConfig =
            ConversionConfig(sampleRate = sampleRate, hopMs = 640, contextMs = 0)

        /** 高端机低延迟：320 ms 块，模型需支持 32 帧。 */
        fun lowLatency(sampleRate: Int = 48_000): ConversionConfig =
            ConversionConfig(sampleRate = sampleRate, hopMs = 320, contextMs = 0)

        /** 中端机稳定档：960 ms 块，优先不断音。 */
        fun stable(sampleRate: Int = 48_000): ConversionConfig =
            ConversionConfig(sampleRate = sampleRate, hopMs = 960, contextMs = 0)
    }
}
