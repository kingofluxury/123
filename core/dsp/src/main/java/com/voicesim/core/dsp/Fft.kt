/*
 * Portions derived from MeowRVC (https://github.com/xiaoxiaoyu-miao/MeowRVC),
 * licensed under the Apache License, Version 2.0.
 */
package com.voicesim.core.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * 定点基 2 FFT（原地）。
 *
 * 仅服务于 RMVPE 的 1024 点 Mel 前端；不做通用 FFT 库的承诺。
 * 移植自 MeowRVC（Apache-2.0）的 RVCOnnxEngine，原始算法来自 RMVPE。
 */
object Fft {

    /** 原地计算 [real] + j[imag] 的 DFT，长度必须是 2 的幂。 */
    fun forward(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(n == imag.size) { "real/imag size mismatch" }
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two: $n" }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wLenR = cos(ang)
            val wLenI = sin(ang)
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until len / 2) {
                    val even = i + k
                    val odd = even + len / 2
                    val oddR = real[odd] * wR - imag[odd] * wI
                    val oddI = real[odd] * wI + imag[odd] * wR
                    real[odd] = real[even] - oddR
                    imag[odd] = imag[even] - oddI
                    real[even] += oddR
                    imag[even] += oddI
                    val nextR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextR
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** 对前 [size] 个样本做加窗前的功率谱，返回 size/2+1 个 bin。 */
    fun powerSpectrum(samples: FloatArray, size: Int): DoubleArray {
        require(size > 0 && size and (size - 1) == 0)
        val real = DoubleArray(size) { if (it < samples.size) samples[it].toDouble() else 0.0 }
        val imag = DoubleArray(size)
        forward(real, imag)
        return DoubleArray(size / 2 + 1) { real[it] * real[it] + imag[it] * imag[it] }
    }
}
