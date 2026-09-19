package com.seizureguard.phone.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceLivenessTest {
    private val store = MemoryStore()
    private var wall = 1_000_000L
    private var mono = 0L
    private fun liveness() = ServiceLiveness(store, { wall }, { mono })

    @Test fun gapAboveThreshold_withIntentAndNoCleanStop_isDowntime() {
        val p = serviceDownPeriod(1_000, 1_000 + SERVICE_DOWN_THRESHOLD_MS + 1, cleanStop = false, wasBridging = true)
        assertEquals(FaultPeriod(FaultKind.SERVICE_DOWN, 1_000, 1_000 + SERVICE_DOWN_THRESHOLD_MS + 1), p)
    }

    @Test fun gapAtThreshold_cleanStop_noIntent_orNeverRan_isNotDowntime() {
        assertNull(serviceDownPeriod(1_000, 1_000 + SERVICE_DOWN_THRESHOLD_MS, false, true))
        assertNull(serviceDownPeriod(1_000, 10_000_000, cleanStop = true, wasBridging = true))
        assertNull(serviceDownPeriod(1_000, 10_000_000, cleanStop = false, wasBridging = false))
        assertNull(serviceDownPeriod(0, 10_000_000, cleanStop = false, wasBridging = true))
    }

    @Test fun touch_writesAtMostOncePerMinute() {
        val l = liveness()
        l.touch() // t=0 written
        wall += 10_000; mono += 10_000
        l.touch()
        assertEquals(1_000_000L, l.lastAliveMs())
        wall += 50_000; mono += 50_000
        l.touch()
        assertEquals(1_060_000L, l.lastAliveMs())
    }

    @Test fun crashThenRestart_reportsDowntime() {
        liveness().apply { onServiceStart(true); wall += 30_000; mono += 30_000; touch() }
        wall += 600_000 // process died, restarted 10 min later
        val period = liveness().onServiceStart(true)
        assertEquals(FaultKind.SERVICE_DOWN, period?.kind)
        assertEquals(1_000_000L, period?.startMs) // last write was the start; touch within 60 s was skipped
        assertEquals(wall, period?.endMs)
    }

    @Test fun cleanStop_isNotReported_andFlagResetsAfterNextStart() {
        val l = liveness()
        l.onServiceStart(true)
        l.markCleanStop()
        wall += 8 * 3_600_000L
        assertNull(liveness().onServiceStart(true)) // user was off overnight on purpose
        wall += 600_000
        assertEquals(FaultKind.SERVICE_DOWN, liveness().onServiceStart(true)?.kind) // later crash is reported
    }
}
