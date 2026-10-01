package com.seizureguard.phone.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) // FaultLog / AlarmStateRelay need org.json from the Android runtime
@Config(sdk = [34])
class ContractVersionCheckTest {
    // ── pure compare ──────────────────────────────────────────────────────────

    @Test fun compare_sameVersion_matches() {
        assertEquals(VersionCompatibility.MATCH, ContractVersionCheck.compare(1, 1))
        assertEquals(VersionCompatibility.MATCH, ContractVersionCheck.compare(7, 7))
    }

    @Test fun compare_differentNumber_mismatches() {
        listOf(0, 2, 99).forEach { assertEquals("watch=$it", VersionCompatibility.MISMATCH, ContractVersionCheck.compare(it, 1)) }
    }

    @Test fun compare_missingVersion_isMissing() {
        assertEquals(VersionCompatibility.MISSING, ContractVersionCheck.compare(null, 1))
    }

    @Test fun onlyMatchIsCompatible() {
        assertFalse(VersionCompatibility.MATCH.isIncompatible)
        assertTrue(VersionCompatibility.MISMATCH.isIncompatible)
        assertTrue(VersionCompatibility.MISSING.isIncompatible)
    }

    @Test fun companionConstant_isOne_matchingTheWatchContract() {
        assertEquals(1, COMPANION_CONTRACT_VERSION) // must equal wear TRANSPORT_CONTRACT_VERSION
    }

    // ── recorder over a real FaultLog ─────────────────────────────────────────

    private val store = MemoryStore()
    private var now = 1_000L
    private val log = FaultLog(store) { now }
    private val recorder = VersionMismatchRecorder(log)
    private fun settings(v: Int?) = WatchSettings(80, 25, v)
    private fun openVersionPeriods() = log.periods().filter { it.kind == FaultKind.VERSION_MISMATCH && it.endMs == null }

    @Test fun recorder_matchingVersion_opensNothing() {
        assertEquals(VersionCompatibility.MATCH, recorder.onWatchSettings(settings(1)))
        assertEquals(emptyList<FaultPeriod>(), log.periods())
    }

    @Test fun recorder_mismatchOrMissing_opens_thenMatchCloses() {
        listOf(2, null, 0).forEach { v ->
            now += 1_000
            recorder.onWatchSettings(settings(v))
            assertEquals("watch=$v", 1, openVersionPeriods().size)
            now += 1_000
            recorder.onWatchSettings(settings(1))
            assertEquals("watch=$v", 0, openVersionPeriods().size)
        }
        assertEquals(3, log.periods().size)
        assertTrue(log.periods().all { it.endMs != null })
    }

    @Test fun recorder_neverThrows_evenIfPersistenceFails() {
        val failing = FaultLog(object : StringStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) = throw IllegalStateException("disk full")
        }) { now }
        assertEquals(VersionCompatibility.MISMATCH, VersionMismatchRecorder(failing).onWatchSettings(settings(2)))
    }

    @Test fun recorder_doesNotChangeAnotherOpenFaultPeriod() {
        log.onFault(BridgeFault.OSD_UNREACHABLE)
        recorder.onWatchSettings(settings(2))
        recorder.onWatchSettings(settings(1))
        assertEquals(FaultPeriod(FaultKind.OSD_UNREACHABLE, 1_000, null), log.periods().single { it.kind == FaultKind.OSD_UNREACHABLE })
    }

    // ── the alarm relay does not depend on the version period ─────────────────

    private fun states(sent: List<String>) = sent.map { JSONObject(it).getInt("alarm_state") }

    @Test fun alarmRelay_stillRelaysOk_whenBridgeIsHealthy_withVersionMismatchOpen() {
        recorder.onWatchSettings(settings(2))
        assertEquals(1, openVersionPeriods().size)
        val state = BridgeState(1_000L)
        val sent = mutableListOf<String>()
        val relay = AlarmStateRelay({ sent += String(it, Charsets.UTF_8); true }, { 1_500L }, { state.fault(1_500L) })
        assertEquals(BridgeFault.NONE, state.fault(1_500L)) // the bridge is healthy: the version period is not a BridgeFault
        relay.onAlarmDataPolled("""{"alarmState":0,"alarmPhrase":"OK"}""")
        assertEquals(listOf(0), states(sent))
    }

    @Test fun alarmRelay_stillFailsLoud_onRealBridgeFault_withVersionMismatchOpen() {
        recorder.onWatchSettings(settings(2))
        val sent = mutableListOf<String>()
        val relay = AlarmStateRelay({ sent += String(it, Charsets.UTF_8); true }, { 2_000L }, { BridgeFault.OSD_UNREACHABLE })
        relay.onAlarmDataPolled("""{"alarmState":0,"alarmPhrase":"OK"}""") // OK withheld on a real fault
        relay.onAlarmDataPolled("""{"alarmState":2,"alarmPhrase":"ALARM"}""") // non-OK always relayed
        assertEquals(listOf(2), states(sent))
    }
}
