package com.seizureguard.phone.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeHealthTest {
    private val now = 1_000_000L

    private fun eval(
        watchAgo: Long = 0,
        postOkAgo: Long = 0,
        failures: Int = 0,
        outcome: PostOutcome = PostOutcome.OK,
    ) = evaluate(now, now - watchAgo, now - postOkAgo, failures, outcome)

    @Test fun healthy_isNone() = assertEquals(BridgeFault.NONE, eval())

    @Test fun watchSilence_boundary() {
        assertEquals(BridgeFault.NONE, eval(watchAgo = 30_000))
        assertEquals(BridgeFault.NO_WATCH_DATA, eval(watchAgo = 30_001))
    }

    @Test fun consecutiveFailures_boundary() {
        assertEquals(BridgeFault.NONE, eval(failures = 2, outcome = PostOutcome.UNREACHABLE))
        assertEquals(BridgeFault.OSD_UNREACHABLE, eval(failures = 3, outcome = PostOutcome.UNREACHABLE))
    }

    @Test fun postOkStale_boundary() {
        assertEquals(BridgeFault.NONE, eval(postOkAgo = 20_000))
        assertEquals(BridgeFault.OSD_UNREACHABLE, eval(postOkAgo = 20_001))
    }

    @Test fun wrongDatasource_latchesImmediately() =
        assertEquals(BridgeFault.OSD_WRONG_DATASOURCE, eval(outcome = PostOutcome.WRONG_DATASOURCE))

    @Test fun parseError_latchesRejectsData() =
        assertEquals(BridgeFault.OSD_REJECTS_DATA, eval(outcome = PostOutcome.OSD_PARSE_ERROR))

    @Test fun latchedFaultOutranksWatchSilence() =
        assertEquals(
            BridgeFault.OSD_WRONG_DATASOURCE,
            eval(watchAgo = 60_000, outcome = PostOutcome.WRONG_DATASOURCE),
        )

    @Test fun sendSettings_isNotAFault() = assertEquals(BridgeFault.NONE, eval(outcome = PostOutcome.SEND_SETTINGS))

    @Test fun recovery_backToNone() {
        assertEquals(BridgeFault.NO_WATCH_DATA, eval(watchAgo = 45_000))
        assertEquals(BridgeFault.NONE, eval(watchAgo = 1_000))
        assertEquals(BridgeFault.OSD_WRONG_DATASOURCE, eval(outcome = PostOutcome.WRONG_DATASOURCE))
        assertEquals(BridgeFault.NONE, eval(outcome = PostOutcome.OK))
        assertEquals(BridgeFault.OSD_UNREACHABLE, eval(failures = 4, outcome = PostOutcome.UNREACHABLE))
        assertEquals(BridgeFault.NONE, eval(failures = 0, outcome = PostOutcome.OK))
    }
}
