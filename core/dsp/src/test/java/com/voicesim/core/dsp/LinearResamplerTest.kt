package com.voicesim.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearResamplerTest {

    @Test
    fun constantInputStaysConstant() {
        val resampler = LinearResampler(48_000, 16_000)
        val input = FloatArray(4_800) { 0.5f }
        val output = FloatArray(2_000)

        val produced = resampler.process(input, input.size, output)

        assertTrue("produced=$produced", produced in 1_590..1_610)
        for (i in 0 until produced) {
            assertEquals(0.5f, output[i], 1e-4f)
        }
    }

    @Test
    fun streamingChunksMatchSingleShotLength() {
        val resampler = LinearResampler(48_000, 16_000)
        val output = FloatArray(1_024)
        var total = 0

        repeat(10) {
            val input = FloatArray(480) { 0.1f }
            total += resampler.process(input, input.size, output)
        }

        // 4800 个输入样本应产生约 1600 个输出样本，允许插值边界误差
        assertTrue("total=$total", total in 1_590..1_610)
    }
}
