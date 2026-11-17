package com.voicesim.core.audio

/**
 * 全链路统一的音频格式：单声道、float32、48 kHz、20 ms 一帧。
 *
 * 模型域（16 kHz / 40 kHz）的转换由 [com.voicesim.core.dsp.Resampler] 负责，
 * 这里只描述设备 I/O 侧的契约。
 */
data class AudioSpec(
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val frameDurationMs: Int = DEFAULT_FRAME_MS,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(frameDurationMs in 5..100) { "frameDurationMs out of range: $frameDurationMs" }
    }

    /** 一帧的样本数，例如 48k / 20 ms = 960。 */
    val frameSamples: Int = sampleRate * frameDurationMs / 1000

    /** PCM_FLOAT 下一个样本 4 字节。 */
    val bytesPerFrame: Int get() = frameSamples * Float.SIZE_BYTES

    fun samplesFor(durationMs: Int): Int = (sampleRate.toLong() * durationMs / 1000L).toInt()

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_FRAME_MS = 20
    }
}
