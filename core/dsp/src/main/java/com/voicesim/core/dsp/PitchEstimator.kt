package com.voicesim.core.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 单帧基频估计结果。 */
data class PitchFrame(
    val f0Hz: Float,
    val voiced: Boolean,
    val confidence: Float,
)

/**
 * 基频估计器。
 *
 * P1 用 YIN 做兜底/校验；P2 接入 RMVPE ONNX 后，由 [com.voicesim.core.vc.RvcConverter]
 * 直接消费模型输出，这个接口仍然保留用于无模型场景和可视化。
 */
interface PitchEstimator {
    val minF0Hz: Float
    val maxF0Hz: Float

    fun estimate(frame: FloatArray, size: Int, sampleRate: Int): PitchFrame

    fun reset()
}

/**
 * YIN 基频估计（累积均值归一化差分函数）。
 *
 * 复杂度 O(size * maxTau)，在 16 kHz / 1024 点窗口下开销很小，适合每帧调用。
 */
class YinPitchEstimator(
    override val minF0Hz: Float = 60f,
    override val maxF0Hz: Float = 1000f,
    private val threshold: Float = 0.15f,
) : PitchEstimator {

    private var diff = FloatArray(0)
    private var cmnd = FloatArray(0)

    override fun estimate(frame: FloatArray, size: Int, sampleRate: Int): PitchFrame {
        val window = size / 2
        val maxTau = min((sampleRate / minF0Hz).toInt(), size / 2)
        val minTau = max(2, (sampleRate / maxF0Hz).toInt())

        if (maxTau <= minTau || window + maxTau > size) {
            return PitchFrame(0f, false, 0f)
        }
        if (diff.size <= maxTau) {
            diff = FloatArray(maxTau + 1)
            cmnd = FloatArray(maxTau + 1)
        }

        // 1) 差分函数
        for (tau in 1..maxTau) {
            var sum = 0.0
            var j = 0
            while (j < window) {
                val d = (frame[j] - frame[j + tau]).toDouble()
                sum += d * d
                j++
            }
            diff[tau] = sum.toFloat()
        }

        // 2) 累积均值归一化
        cmnd[0] = 1f
        var running = 0f
        for (tau in 1..maxTau) {
            running += diff[tau]
            cmnd[tau] = if (running <= 1e-12f) 1f else diff[tau] * tau / running
        }

        // 3) 找第一个低于阈值的谷，并下降到局部最小
        var bestTau = -1
        var tau = minTau
        while (tau < maxTau) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 < maxTau && cmnd[tau + 1] < cmnd[tau]) tau++
                bestTau = tau
                break
            }
            tau++
        }
        if (bestTau <= 0) return PitchFrame(0f, false, 0f)

        // 4) 抛物线插值，得到亚样本精度
        val a = cmnd[bestTau - 1]
        val b = cmnd[bestTau]
        val c = cmnd[bestTau + 1]
        val denom = a - 2f * b + c
        var refined = bestTau.toDouble()
        if (abs(denom) > 1e-9f) {
            refined += 0.5 * (a - c) / denom
        }
        if (refined <= 0.0) return PitchFrame(0f, false, 0f)

        return PitchFrame(
            f0Hz = (sampleRate / refined).toFloat(),
            voiced = true,
            confidence = (1f - b).coerceIn(0f, 1f),
        )
    }

    override fun reset() {
        diff.fill(0f)
        cmnd.fill(0f)
    }
}
