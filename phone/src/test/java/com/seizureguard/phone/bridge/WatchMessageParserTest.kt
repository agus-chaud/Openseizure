package com.seizureguard.phone.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric supplies a real org.json (android.jar stubs throw on the JVM).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchMessageParserTest {
    private fun accel(n: Int, item: String = "1.5") =
        """{"samples":[${List(n) { item }.joinToString(",")}]}""".toByteArray()

    @Test fun accel_validChunkKeepsOrder() {
        val json = """{"samples":[${(0 until 125).joinToString(",")}]}"""
        val out = WatchMessageParser.parseAccel(json.toByteArray())!!
        assertArrayEquals(DoubleArray(125) { it.toDouble() }, out, 0.0)
    }

    @Test fun accel_wrongLengthsRejected() {
        assertNull(WatchMessageParser.parseAccel(accel(124)))
        assertNull(WatchMessageParser.parseAccel(accel(126)))
        assertNull(WatchMessageParser.parseAccel(accel(0)))
    }

    @Test fun accel_nonNumericOrNonFiniteRejected() {
        assertNull(WatchMessageParser.parseAccel(accel(125, "\"x\"")))
        assertNull(WatchMessageParser.parseAccel(accel(125, "null")))
        assertNull(WatchMessageParser.parseAccel(accel(125, "1e999")))
        assertNull(WatchMessageParser.parseAccel(accel(125, "200000")))
    }

    @Test fun accel_garbageNeverThrows() {
        listOf(null, ByteArray(0), "not json".toByteArray(), "[]".toByteArray(), "{}".toByteArray(),
            """{"samples":"abc"}""".toByteArray(), ByteArray(9000) { 'a'.code.toByte() },
            byteArrayOf(0xC3.toByte(), 0x28)).forEach { assertNull(WatchMessageParser.parseAccel(it)) }
    }

    @Test fun settings_valid() =
        assertEquals(WatchSettings(87, 25), WatchMessageParser.parseSettings("""{"battery":87,"sample_freq":25}""".toByteArray()))

    @Test fun settings_invalidRejected() {
        listOf(
            """{"battery":101,"sample_freq":25}""", """{"battery":-1,"sample_freq":25}""",
            """{"battery":50,"sample_freq":0}""", """{"battery":50}""", """{"sample_freq":25}""",
            """{"battery":"50","sample_freq":25}""", """{"battery":50.5,"sample_freq":25}""", "oops",
        ).forEach { assertNull(it, WatchMessageParser.parseSettings(it.toByteArray())) }
        assertNotNull(WatchMessageParser.parseSettings("""{"battery":50.0,"sample_freq":25}""".toByteArray()))
    }
}
