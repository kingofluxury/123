package com.voicesim.core.dsp

/**
 * 流式采样率转换器。
 *
 * 输入输出都是 float32 单声道，允许任意长度分块调用；内部保留跨块状态。
 */
interface Resampler {
    val inputRate: Int
    val outputRate: Int

    /** 给定输入长度时，输出缓冲至少需要多大。 */
    fun maxOutputSize(inputSize: Int): Int

    /**
     * @return 实际写入 [output] 的样本数；没凑够插值点的尾部样本会留到下次。
     */
    fun process(input: FloatArray, inputSize: Int, output: FloatArray): Int

    fun reset()
}

/**
 * 线性插值重采样，流式、零分配、任意分块。
 *
 * 质量说明：下采样时**没有带限**，48k → 16k 会有混叠。
 * P0/P1 用它跑通链路；P2 前换成多相 FIR（或先低通再抽取），接口不变。
 */
class LinearResampler(
    override val inputRate: Int,
    override val outputRate: Int,
) : Resampler {

    init {
        require(inputRate > 0 && outputRate > 0)
    }

    private val step: Double = inputRate.toDouble() / outputRate
    private var frac: Double = 0.0
    private var s0: Float = 0f
    private var s1: Float = 0f
    private var held: Int = 0

    override fun maxOutputSize(inputSize: Int): Int =
        (inputSize.toLong() * outputRate / inputRate).toInt() + 4

    override fun process(input: FloatArray, inputSize: Int, output: FloatArray): Int {
        if (inputSize <= 0 || output.isEmpty()) return 0

        var out = 0
        var i = 0

        while (true) {
            while (held < 2 && i < inputSize) {
                if (held == 0) s0 = input[i] else s1 = input[i]
                i++
                held++
            }
            if (held < 2 || out >= output.size) break

            output[out++] = (s0 + (s1 - s0) * frac).toFloat()
            frac += step

            while (frac >= 1.0) {
                frac -= 1.0
                s0 = s1
                held = 1
                if (i < inputSize) {
                    s1 = input[i]
                    i++
                    held = 2
                } else {
                    break
                }
            }
        }
        return out
    }

    override fun reset() {
        frac = 0.0
        s0 = 0f
        s1 = 0f
        held = 0
    }
}
