package com.seizureguard.phone.bridge

import java.net.URLDecoder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric supplies a real org.json (android.jar stubs throw on the JVM).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsdPayloadCodecTest {
    @Test fun rawDataJson_hasSchema() {
        val o = JSONObject(OsdPayloadCodec.rawDataJson(doubleArrayOf(1.5, -2.0, 1000.0)))
        assertEquals("raw", o.getString("dataType"))
        assertEquals(3, o.getJSONArray("data").length())
        assertEquals(1.5, o.getJSONArray("data").getDouble(0), 0.0)
    }

    @Test fun settingsJson_hasMandatoryInts() {
        val o = JSONObject(OsdPayloadCodec.settingsJson(battery = 87))
        assertEquals("settings", o.getString("dataType"))
        assertEquals(5, o.getInt("analysisPeriod"))
        assertEquals(25, o.getInt("sampleFreq"))
        assertEquals(87, o.getInt("battery"))
    }

    @Test fun settingsJson_overridesAndOptionalStrings() {
        val o = JSONObject(OsdPayloadCodec.settingsJson(10, sampleFreq = 50, analysisPeriod = 10))
        assertEquals(50, o.getInt("sampleFreq"))
        assertEquals(10, o.getInt("analysisPeriod"))
        listOf("watchPartNo", "watchFwVersion", "sdVersion", "sdName").forEach { assertTrue(o.has(it)) }
    }

    @Test fun formBody_prefixAndNoRawCrLf() {
        val json = "{\"a\":\"x\r\ny\"}"
        val body = String(OsdPayloadCodec.formBody(json), Charsets.UTF_8)
        assertTrue(body.startsWith("dataObj="))
        assertFalse(body.contains('\r') || body.contains('\n'))
    }

    @Test fun formBody_roundTrips() {
        val json = OsdPayloadCodec.rawDataJson(DoubleArray(125) { it * 1.25 })
        val body = String(OsdPayloadCodec.formBody(json), Charsets.UTF_8)
        assertEquals(json, URLDecoder.decode(body.removePrefix("dataObj="), "UTF-8"))
    }
}
