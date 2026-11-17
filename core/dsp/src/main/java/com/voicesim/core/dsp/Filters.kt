package com.voicesim.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 双二阶滤波器（RBJ cookbook），Direct Form I。
 *
 * 目前只提供变声链路需要的高通（去除 80 Hz 以下的直流/隆隆声），
 * 后续需要 EQ / 去齿音时在这里加 lowPass / peak。
 */
class Biquad private constructor(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float,
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun processInPlace(buffer: FloatArray, size: Int = buffer.size) {
        for (i in 0 until size) {
            val x = buffer[i]
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            buffer[i] = y
        }
    }

    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }

    companion object {
        fun highPass(sampleRate: Int, cutoffHz: Float, q: Float = 0.70710678f): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosw = cos(w0)
            val sinw = sin(w0)
            val alpha = sinw / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 = (1.0 + cosw) / 2.0
            val b1 = -(1.0 + cosw)
            val b2 = b0
            val a1 = -2.0 * cosw
            val a2 = 1.0 - alpha
            return Biquad(
                (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                (a1 / a0).toFloat(), (a2 / a0).toFloat(),
            )
        }

        fun lowPass(sampleRate: Int, cutoffHz: Float, q: Float = 0.70710678f): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosw = cos(w0)
            val sinw = sin(w0)
            val alpha = sinw / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 = (1.0 - cosw) / 2.0
            val b1 = 1.0 - cosw
            val b2 = b0
            val a1 = -2.0 * cosw
            val a2 = 1.0 - alpha
            return Biquad(
                (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                (a1 / a0).toFloat(), (a2 / a0).toFloat(),
            )
        }
    }
}

/**
 * 峰值限幅器：瞬时压、缓慢放。
 *
 * 放在链路最后一级，保证生成模型偶发过冲不会削爆后级。
 */
class PeakLimiter(
    sampleRate: Int,
    private val threshold: Float = 0.95f,
    releaseMs: Float = 80f,
) {
    private var gain = 1f
    private val releaseCoef = exp(-1.0 / (releaseMs / 1000.0 * sampleRate)).toFloat()

    fun process(buffer: FloatArray, size: Int = buffer.size) {
        for (i in 0 until size) {
            val peak = abs(buffer[i])
            val desired = if (peak > threshold) threshold / peak else 1f
            gain = if (desired < gain) desired else gain + (desired - gain) * (1f - releaseCoef)
            buffer[i] *= gain
        }
    }

    fun reset() {
        gain = 1f
    }
}

/**
 * 淡入淡出工具。只在旁路切换、起停、拼接点使用，
 * 不要对每个 chunk 无条件调用（会形成周期性音量起伏）。
 */
object Fade {
    fun applyInPlace(buffer: FloatArray, offset: Int, size: Int, fadeSamples: Int) {
        val n = min(fadeSamples, size / 2)
        for (i in 0 until n) {
            buffer[offset + i] *= i.toFloat() / n
            buffer[offset + size - 1 - i] *= i.toFloat() / n
        }
    }

    fun rms(buffer: FloatArray, size: Int = buffer.size): Float {
        if (size <= 0) return 0f
        var sum = 0.0
        for (i in 0 until size) sum += buffer[i].toDouble() * buffer[i]
        return sqrt(sum / size).toFloat()
    }
}
