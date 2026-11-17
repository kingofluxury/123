package com.voicesim.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class FftTest {

    @Test
    fun impulseHasFlatMagnitude() {
        val n = 1024
        val real = DoubleArray(n)
        val imag = DoubleArray(n)
        real[0] = 1.0

        Fft.forward(real, imag)

        for (i in 0 until n) {
            val magnitude = sqrt(real[i] * real[i] + imag[i] * imag[i])
            assertEquals(1.0, magnitude, 1e-9)
        }
    }

    @Test
    fun melHasExpectedShapeAndFiniteValues() {
        val audio = FloatArray(16_000) {
            (sin(2.0 * PI * 440.0 * it / 16_000.0) * 0.5).toFloat()
        }

        val mel = RmvpeMel.compute(audio, 64)

        assertEquals(RmvpeMel.MEL_BINS * 64, mel.size)
        assertTrue(mel.all { it.isFinite() })
        assertTrue(mel.any { it > -10f })
    }
}
