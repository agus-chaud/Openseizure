package com.seizureguard.phone.ml

import com.seizureguard.phone.service.DataLayerListenerService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comportamiento del pipeline phone: parseo LE + acumulación + política DEC-040.
 * Sin GMS: solo [PhoneAccelChunkProcessor] + [AccelPayloadCodec].
 */
class PhoneAccelChunkProcessorTest {

    @Test
    fun `six chunks of 125 first inference on sixth chunk only`() {
        val buffer = PhoneCircularBuffer(750)
        var inferenceCount = 0
        val processor = PhoneAccelChunkProcessor(buffer, { _ ->
            inferenceCount++
            0.toByte()
        })

        for (chunkIndex in 1..6) {
            val start = (chunkIndex - 1) * 125 + 1
            val chunk = FloatArray(125) { i -> (start + i).toFloat() }
            val bytes = AccelPayloadCodec.floatsToBytes(chunk)
            val result = processor.processChunk(bytes)

            if (chunkIndex < 6) {
                assertFalse("chunk $chunkIndex no debe inferir", result.didInfer)
                assertEquals(0, inferenceCount)
            } else {
                assertTrue("chunk 6 debe inferir", result.didInfer)
                assertEquals(1, inferenceCount)
                assertEquals(0.toByte(), result.alarmState)
                assertEquals(750, result.window!!.size)
            }
        }
    }

    @Test
    fun `each chunk after warm up triggers new inference sliding step 125`() {
        val buffer = PhoneCircularBuffer(750)
        var inferenceCount = 0
        val processor = PhoneAccelChunkProcessor(buffer, { _ ->
            inferenceCount++
            0.toByte()
        })

        for (chunkIndex in 1..7) {
            val start = (chunkIndex - 1) * 125 + 1
            val chunk = FloatArray(125) { i -> (start + i).toFloat() }
            processor.processChunk(AccelPayloadCodec.floatsToBytes(chunk))
        }

        assertEquals(2, inferenceCount)
    }

    @Test
    fun `seventh chunk window starts at 126`() {
        val buffer = PhoneCircularBuffer(750)
        val processor = PhoneAccelChunkProcessor(buffer) { _ -> 0.toByte() }

        for (chunkIndex in 1..7) {
            val start = (chunkIndex - 1) * 125 + 1
            val chunk = FloatArray(125) { i -> (start + i).toFloat() }
            processor.processChunk(AccelPayloadCodec.floatsToBytes(chunk))
        }

        val snap = buffer.snapshot()
        assertArrayEquals(
            "ventana deslizante: 126..875",
            FloatArray(750) { i -> (126 + i).toFloat() },
            snap,
            0.001f
        )
    }

    @Test
    fun `simulated little endian payload roundTrips through codec`() {
        val original = floatArrayOf(1.5f, -2.25f, 999.125f)
        val bytes = AccelPayloadCodec.floatsToBytes(original)
        assertEquals(12, bytes.size)
        val back = AccelPayloadCodec.bytesToFloats(bytes)
        assertArrayEquals(original, back, 0.0001f)
    }

    @Test
    fun `osd paths match wear contract`() {
        assertEquals("/osd/accel_data", DataLayerListenerService.PATH_ACCEL_DATA)
        assertEquals("/osd/alarm_state", DataLayerListenerService.PATH_ALARM_STATE)
    }
}
