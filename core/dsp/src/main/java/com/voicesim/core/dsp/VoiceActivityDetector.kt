package com.voicesim.core.dsp

import kotlin.math.log10
import kotlin.math.max

/**
 * 语音活动检测。用于空闲省电和统计，**不用于硬切断音频**（会吃掉起音）。
 */
interface VoiceActivityDetector {
    fun isSpeech(frame: FloatArray, size: Int): Boolean
    fun reset()
}

/**
 * 能量 + 迟滞的 VAD，够用且几乎零成本。
 * 后续可替换成 WebRTC VAD / Silero VAD，接口不变。
 */
class EnergyVad(
    private val thresholdDb: Float = -45f,
    private val hangoverFrames: Int = 25,
) : VoiceActivityDetector {

    private var hangover = 0

    override fun isSpeech(frame: FloatArray, size: Int): Boolean {
        val rms = Fade.rms(frame, size)
        val db = 20f * log10(max(rms, 1e-7f))

        if (db > thresholdDb) {
            hangover = hangoverFrames
        } else if (hangover > 0) {
            hangover--
        }
        return hangover > 0
    }

    override fun reset() {
        hangover = 0
    }
}
