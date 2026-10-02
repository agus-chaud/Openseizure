package com.seizureguard.phone.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Batch 8c, R10-F3: service start and clean stop close bridge-fault periods exactly as before, but an open
 * VERSION_MISMATCH period survives them (and a process restart); only a real watch MATCH closes it.
 */
@RunWith(RobolectricTestRunner::class) // org.json needs the Android runtime
@Config(sdk = [34])
class BridgeHistoryTest {
    private val store = MemoryStore()
    private var now = 1_000L
    private fun log() = FaultLog(store) { now }
    private fun liveness(wall: Long) = ServiceLiveness(store, { wall }, { 0L })

    @Test fun serviceStartAndCleanStop_keepTheOpenVersionPeriod_butStillCloseBridgeFaults() {
        liveness(1_000).onServiceStart(true)
        val log = log()
        log.onFault(BridgeFault.OSD_UNREACHABLE) // 1000
        now = 2_000
        log.onVersionCompatibility(VersionCompatibility.MISMATCH)

        // The user presses Stop: bridge fault closed at the stop time, version period untouched.
        BridgeHistory.onCleanStop(log, liveness(3_000), 3_000)
        assertEquals(
            listOf(FaultPeriod(FaultKind.OSD_UNREACHABLE, 1_000, 3_000), FaultPeriod(FaultKind.VERSION_MISMATCH, 2_000, null)),
            log.periods(),
        )

        // Process restart: the version period is still open after the next service start.
        val reborn = log()
        BridgeHistory.onServiceStart(reborn, liveness(9_000), wasBridging = true, nowMs = 9_000)
        assertEquals(listOf(FaultPeriod(FaultKind.VERSION_MISMATCH, 2_000, null)), reborn.periods().filter { it.endMs == null })
    }

    @Test fun crashRestart_closesTheBridgeFaultAtLastAlive_andKeepsTheVersionPeriod() {
        liveness(1_000).onServiceStart(true) // last alive = 1000
        val log = log()
        log.onFault(BridgeFault.NO_WATCH_DATA)
        log.onVersionCompatibility(VersionCompatibility.MISSING)
        val reborn = log()
        BridgeHistory.onServiceStart(reborn, liveness(900_000), wasBridging = true, nowMs = 900_000)
        val byKind = reborn.periods().associateBy { it.kind }
        assertEquals(1_000L, byKind.getValue(FaultKind.NO_WATCH_DATA).endMs)
        assertNull(byKind.getValue(FaultKind.VERSION_MISMATCH).endMs)
        assertNotNull(byKind[FaultKind.SERVICE_DOWN]) // downtime still reported exactly as before
    }
}
