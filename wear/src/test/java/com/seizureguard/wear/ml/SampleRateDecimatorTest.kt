package com.seizureguard.wear.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests de [SampleRateDecimator]: grilla de 25 Hz por timestamp (DEC-068).
 * Clase pura, sin mocks. Los timestamps se generan en ns como los de SensorEvent.timestamp.
 */
class SampleRateDecimatorTest {

    private val periodNs = 40_000_000L
    private val ms = 1_000_000L
    private lateinit var decimator: SampleRateDecimator

    @Before
    fun setUp() {
        decimator = SampleRateDecimator(periodNs)
    }

    private fun feed(timestamps: List<Long>): List<Long> = timestamps.filter { decimator.shouldKeep(it) }

    @Test
    fun `native 25 Hz with jitter keeps every sample`() {
        val intervalsMs = listOf(37.8, 42.3, 40.0, 38.5, 41.7, 39.2, 42.0, 37.9, 40.4, 40.0)
        var t = 5_000 * ms
        val ts = mutableListOf(t)
        repeat(300) { i ->
            t += (intervalsMs[i % intervalsMs.size] * ms).toLong()
            ts.add(t)
        }
        assertEquals(ts.size, feed(ts).size)
    }

    @Test
    fun `50 Hz keeps exactly every other sample, 25 Hz over 60 s`() {
        val ts = (0 until 3000).map { it * 20 * ms }   // 60 s a 50 Hz
        val kept = feed(ts)
        assertEquals(1500, kept.size)
        assertEquals(ts.filterIndexed { i, _ -> i % 2 == 0 }, kept)
    }

    @Test
    fun `about 43 Hz keeps a long-run rate of 25 Hz within 1 percent`() {
        val stepNs = 23_256_000L   // ~43 Hz
        val total = (60_000 * ms / stepNs).toInt()
        val ts = (0 until total).map { it * stepNs }
        val kept = feed(ts)
        val seconds = (ts.last() - ts.first()) / 1e9
        assertEquals(25.0, kept.size / seconds, 0.25)
    }

    @Test
    fun `25 to 50 to 25 transition keeps no pair closer than 30 ms and 125 kept span 5 s`() {
        val ts = mutableListOf<Long>()
        var t = 0L
        repeat(100) { ts.add(t); t += 40 * ms }   // 25 Hz
        repeat(250) { ts.add(t); t += 20 * ms }   // 50 Hz
        repeat(100) { ts.add(t); t += 40 * ms }   // 25 Hz
        val kept = feed(ts)
        kept.zipWithNext().forEach { (a, b) ->
            assertTrue("interval ${(b - a) / ms} ms", b - a >= 30 * ms)
        }
        assertEquals(5.0, (kept[125] - kept[0]) / 1e9, 0.1)   // 125 intervalos = 5.0 s
    }

    @Test
    fun `gap resyncs the grid without a burst of accepted samples`() {
        assertTrue(decimator.shouldKeep(0))
        assertTrue(decimator.shouldKeep(40 * ms))
        // Sensor detenido 2 s y luego ráfaga a 100 Hz.
        val resume = 2_040 * ms
        val burst = (0 until 10).map { resume + it * 10 * ms }
        val kept = feed(burst)
        assertEquals(resume, kept.first())
        // En 90 ms de ráfaga solo entran 3 muestras como máximo (una por período).
        assertTrue(kept.size <= 3)
        kept.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 30 * ms) }
    }

    @Test
    fun `duplicate and backwards timestamps are dropped without moving the grid back`() {
        assertTrue(decimator.shouldKeep(1_000 * ms))
        assertFalse(decimator.shouldKeep(1_000 * ms))   // duplicado
        assertFalse(decimator.shouldKeep(900 * ms))     // retrocede
        assertFalse(decimator.shouldKeep(1_010 * ms))   // antes de la grilla
        assertTrue(decimator.shouldKeep(1_040 * ms))    // la grilla sigue en su lugar
    }

    @Test
    fun `reset makes the next sample start a new grid`() {
        assertTrue(decimator.shouldKeep(0))
        assertFalse(decimator.shouldKeep(10 * ms))
        decimator.reset()
        assertTrue(decimator.shouldKeep(10 * ms))
        assertTrue(decimator.shouldKeep(50 * ms))
        // Un timestamp menor al anterior es válido tras reset (nuevo registro del sensor).
        decimator.reset()
        assertTrue(decimator.shouldKeep(5 * ms))
    }
}
