package com.seizureguard.phone.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeStateTest {
    private val t0 = 5_000_000L // arbitrary non-zero "elapsedRealtime" at service start

    @Test fun clocks_startAtServiceStart_noFalseFaultOnFirstTick() {
        val s = BridgeState(t0)
        assertEquals(BridgeFault.NONE, s.fault(t0 + HEALTH_TICK_MS))
    }

    @Test fun clocks_zeroStartWouldHaveFalseAlarmed() {
        // Documents the review finding: uninitialised (0) clocks trip evaluate() immediately.
        assertEquals(BridgeFault.NO_WATCH_DATA, evaluate(t0, 0L, 0L, 0, PostOutcome.OK))
    }

    @Test fun noWatchData_afterSilence() {
        val s = BridgeState(t0)
        s.onPostOutcome(PostOutcome.OK, t0 + 30_000)
        assertEquals(BridgeFault.NO_WATCH_DATA, s.fault(t0 + 30_001))
        s.onValidAccel(t0 + 30_001)
        assertEquals(BridgeFault.NONE, s.fault(t0 + 30_002))
    }

    @Test fun unreachable_afterThreeFailures() {
        val s = BridgeState(t0)
        repeat(2) { s.onPostOutcome(PostOutcome.UNREACHABLE, t0 + 1_000) }
        assertEquals(BridgeFault.NONE, s.fault(t0 + 1_000))
        s.onPostOutcome(PostOutcome.UNREACHABLE, t0 + 1_000)
        assertEquals(BridgeFault.OSD_UNREACHABLE, s.fault(t0 + 1_000))
    }

    @Test fun wrongDatasource_latchedUntilTwoConsecutiveOk() {
        val s = BridgeState(t0)
        s.onPostOutcome(PostOutcome.WRONG_DATASOURCE, t0 + 1)
        assertEquals(BridgeFault.OSD_WRONG_DATASOURCE, s.fault(t0 + 2))
        s.onPostOutcome(PostOutcome.OK, t0 + 3)  // single stray OK must not clear
        assertEquals(BridgeFault.OSD_WRONG_DATASOURCE, s.fault(t0 + 4))
        s.onPostOutcome(PostOutcome.OK, t0 + 5)
        assertEquals(BridgeFault.NONE, s.fault(t0 + 6))
    }

    @Test fun latch_okStreakResetByFailure() {
        val s = BridgeState(t0)
        s.onPostOutcome(PostOutcome.OSD_PARSE_ERROR, t0 + 1)
        assertEquals(BridgeFault.OSD_REJECTS_DATA, s.fault(t0 + 2))
        s.onPostOutcome(PostOutcome.OK, t0 + 3)
        s.onPostOutcome(PostOutcome.UNREACHABLE, t0 + 4)
        s.onPostOutcome(PostOutcome.OK, t0 + 5)
        assertEquals(BridgeFault.OSD_REJECTS_DATA, s.fault(t0 + 6)) // OK, fail, OK is not consecutive
        s.onPostOutcome(PostOutcome.OK, t0 + 7)
        assertEquals(BridgeFault.NONE, s.fault(t0 + 8))
    }

    @Test fun latch_sendSettingsIsNeutral() {
        val s = BridgeState(t0)
        s.onPostOutcome(PostOutcome.WRONG_DATASOURCE, t0 + 1)
        s.onPostOutcome(PostOutcome.OK, t0 + 2)
        s.onPostOutcome(PostOutcome.SEND_SETTINGS, t0 + 3)
        s.onPostOutcome(PostOutcome.OK, t0 + 4)
        assertEquals(BridgeFault.NONE, s.fault(t0 + 5))
    }

    @Test fun counters_andSettingsCache() {
        val s = BridgeState(t0)
        s.onMalformed(); s.onMalformed(); s.onDropped()
        assertEquals(2L, s.malformedCount())
        assertEquals(1L, s.droppedCount())
        assertNull(s.settings())
        s.onSettings(WatchSettings(80, 25))
        assertEquals(WatchSettings(80, 25), s.settings())
    }

    @Test fun pollDecision_onlyAfterOkPost() {
        assertTrue(shouldPollAfterPost(PostOutcome.OK))
        PostOutcome.values().filter { it != PostOutcome.OK }.forEach { assertFalse(shouldPollAfterPost(it)) }
    }

    @Test fun idlePollDelay_boundaries() {
        assertEquals(IDLE_POLL_MS, idlePollDelayMs(1_000, 1_000))
        assertEquals(1_000L, idlePollDelayMs(5_000, 1_000))
        assertEquals(0L, idlePollDelayMs(6_000, 1_000))
        assertEquals(0L, idlePollDelayMs(60_000, 1_000))
    }

    @Test fun foregroundPrerequisite_onlyBlocksApi34WithoutBluetooth() {
        assertTrue(foregroundPrerequisiteMissing(34, false))
        assertFalse(foregroundPrerequisiteMissing(34, true))
        assertFalse(foregroundPrerequisiteMissing(33, false))
    }

    @Test fun handshakeSettings_cachedOrDefaults() {
        assertEquals(WatchSettings(42, 25), handshakeSettings(WatchSettings(42, 25)))
        assertEquals(WatchSettings(DEFAULT_HANDSHAKE_BATTERY, 25), handshakeSettings(null))
    }

    @Test fun latestSlot_replacesUnconsumedAndReports() {
        val slot = LatestSlot<Int>()
        assertFalse(slot.offer(1))
        assertTrue(slot.offer(2))       // 1 was never consumed -> displaced
        assertEquals(2, slot.take())
        assertNull(slot.take())
        assertFalse(slot.offer(3))
    }
}
