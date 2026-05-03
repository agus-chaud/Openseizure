package com.seizureguard.phone.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneCircularBufferTest {

    private lateinit var buffer: PhoneCircularBuffer

    @Before
    fun setup() {
        buffer = PhoneCircularBuffer(inputSize = 750)
    }

    @Test
    fun `partial append grows size but snapshot empty until full`() {
        buffer.addAll(FloatArray(100) { it.toFloat() })

        assertEquals(100, buffer.size)
        assertFalse(buffer.isFull)
        assertEquals(0, buffer.snapshot().size)
    }

    @Test
    fun `six chunks of 125 fill buffer and snapshot is chronological 1 to 750`() {
        for (chunkIndex in 1..6) {
            val start = (chunkIndex - 1) * 125 + 1
            val chunk = FloatArray(125) { i -> (start + i).toFloat() }
            buffer.addAll(chunk)
        }

        assertTrue(buffer.isFull)
        assertEquals(750, buffer.size)
        val snap = buffer.snapshot()
        assertEquals(750, snap.size)
        assertEquals(1f, snap[0], 0.001f)
        assertEquals(750f, snap[749], 0.001f)
    }

    @Test
    fun `seventh chunk slides window oldest 125 replaced`() {
        for (chunkIndex in 1..6) {
            val start = (chunkIndex - 1) * 125 + 1
            buffer.addAll(FloatArray(125) { i -> (start + i).toFloat() })
        }
        buffer.addAll(FloatArray(125) { i -> (751 + i).toFloat() })

        val snap = buffer.snapshot()
        assertEquals(126f, snap[0], 0.001f)
        assertEquals(875f, snap[749], 0.001f)
    }

    @Test
    fun `reset clears buffer`() {
        buffer.addAll(FloatArray(750) { it.toFloat() })
        assertTrue(buffer.isFull)
        buffer.reset()
        assertEquals(0, buffer.size)
        assertFalse(buffer.isFull)
    }

    @Test
    fun `custom inputSize respected`() {
        val small = PhoneCircularBuffer(inputSize = 10)
        small.addAll(FloatArray(5) { 1f })
        assertFalse(small.isFull)
        small.addAll(FloatArray(5) { 2f })
        assertTrue(small.isFull)
        assertEquals(10, small.snapshot().size)
    }
}
