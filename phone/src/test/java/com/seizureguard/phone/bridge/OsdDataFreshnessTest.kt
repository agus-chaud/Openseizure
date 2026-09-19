package com.seizureguard.phone.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsdDataFreshnessTest {
    private var now = 7_000_000L
    private val f = OsdDataFreshness({ now })

    @Test fun startup_isFreshWithoutAnySample_thenStaleAfterWindow() {
        assertTrue(f.isFresh())
        now += OSD_DATA_FRESH_MS
        assertTrue(f.isFresh())
        now += 1
        assertFalse(f.isFresh())
    }

    @Test fun firstSample_doesNotRefreshTheClock() {
        now += OSD_DATA_FRESH_MS + 1
        f.onSample("20260101T000000")
        assertFalse(f.isFresh())
    }

    @Test fun distinctValue_refreshes_repeatedValue_doesNot() {
        f.onSample("A")
        now += 10_000
        f.onSample("B")
        now += OSD_DATA_FRESH_MS // exactly at the window: still fresh
        f.onSample("B")
        assertTrue(f.isFresh())
        now += 1
        f.onSample("B")
        assertFalse(f.isFresh())
        f.onSample("C")
        assertTrue(f.isFresh())
    }

    @Test fun nullSample_isIgnored() {
        f.onSample("A")
        now += OSD_DATA_FRESH_MS + 1
        f.onSample(null)
        assertFalse(f.isFresh())
    }

    @Test fun parseDataTime_readsDataTimeStr_nullOtherwise() {
        assertEquals("20260101T101010", parseDataTime("""{"alarmState":0,"dataTimeStr":"20260101T101010"}"""))
        assertNull(parseDataTime("""{"dataTime":"01-01-2026 10:10:10"}"""))
        assertNull(parseDataTime("""{"dataTimeStr":5}"""))
        listOf(null, "", "not json", "[]").forEach { assertNull(parseDataTime(it)) }
    }
}
