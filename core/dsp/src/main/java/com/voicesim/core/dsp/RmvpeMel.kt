/*
 * Portions derived from MeowRVC (https://github.com/xiaoxiaoyu-miao/MeowRVC),
 * licensed under the Apache License, Version 2.0.
 */
package com.voicesim.core.dsp

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * RMVPE 的 128 维 Mel 前端。
 *
 * 输出布局与 ONNX 模型一致：[melBins, frameCount]，按 bin 连续存放
 * （即 `mel[bin * frameCount + frame]`）。
 *
 * 参数与实现移植自 MeowRVC（Apache-2.0）的 RVCOnnxEngine，
 * 原始定义来自 RMVPE。
 */
object RmvpeMel {

    const val SAMPLE_RATE = 16_000
    const val MEL_BINS = 128
    const val FFT_SIZE = 1024
    const val HOP_SIZE = 160
    const val SPECTRUM_BINS = FFT_SIZE / 2 + 1
    const val FMIN = 30.0
    const val FMAX = 8000.0
    private const val LOG_EPSILON = 1e-6

    private val window = FloatArray(FFT_SIZE) {
        (0.5 - 0.5 * cos(2.0 * Math.PI * it / FFT_SIZE)).toFloat()
    }

    private val filterbank: FloatArray by lazy { buildFilterbank() }

    /**
     * @param audio 16 kHz 波形，不足部分按静音补齐
     * @param frameCount Mel 帧数，建议为 32 的倍数（RMVPE 对齐要求）
     * @return 长度 [MEL_BINS] × [frameCount] 的 float 数组
     */
    fun compute(audio: FloatArray, frameCount: Int): FloatArray {
        require(frameCount > 0) { "frameCount must be positive" }
        val mel = FloatArray(MEL_BINS * frameCount)
        val spec = DoubleArray(SPECTRUM_BINS)
        val frame = FloatArray(FFT_SIZE)

        for (f in 0 until frameCount) {
            val center = f * HOP_SIZE
            val start = center - FFT_SIZE / 2
            for (i in 0 until FFT_SIZE) {
                val index = start + i
                frame[i] = if (index in audio.indices) audio[index] * window[i] else 0f
            }

            val power = Fft.powerSpectrum(frame, FFT_SIZE)
            System.arraycopy(power, 0, spec, 0, SPECTRUM_BINS)

            for (bin in 0 until MEL_BINS) {
                var energy = 0.0
                val offset = bin * SPECTRUM_BINS
                for (s in 0 until SPECTRUM_BINS) {
                    energy += spec[s] * filterbank[offset + s]
                }
                mel[bin * frameCount + f] = log10(max(energy, LOG_EPSILON)).toFloat()
            }
        }
        return mel
    }

    private fun buildFilterbank(): FloatArray {
        val weights = FloatArray(MEL_BINS * SPECTRUM_BINS)
        val melMin = hzToMel(FMIN)
        val melMax = hzToMel(FMAX)
        val melPoints = DoubleArray(MEL_BINS + 2) {
            melMin + (melMax - melMin) * it / (MEL_BINS + 1)
        }
        val binPoints = melPoints.map { mel ->
            val hz = melToHz(mel)
            ((FFT_SIZE + 1) * hz / SAMPLE_RATE).roundToInt()
                .coerceIn(0, SPECTRUM_BINS - 1)
        }

        for (bin in 0 until MEL_BINS) {
            val left = binPoints[bin]
            val center = max(left + 1, binPoints[bin + 1])
            val right = max(center + 1, binPoints[bin + 2])
            val offset = bin * SPECTRUM_BINS
            for (b in left until center) {
                weights[offset + b] = (b - left).toFloat() / (center - left)
            }
            for (b in center until min(right, SPECTRUM_BINS)) {
                weights[offset + b] = (right - b).toFloat() / (right - center)
            }
        }
        return weights
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
