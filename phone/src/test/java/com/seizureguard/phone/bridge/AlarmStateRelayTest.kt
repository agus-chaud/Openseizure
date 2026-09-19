package com.seizureguard.phone.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric supplies a real org.json (android.jar stubs throw on the JVM).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmStateRelayTest {
    private var now = 1_000_000L
    private val sent = mutableListOf<String>()
    private var accept = true
    private var fault = BridgeFault.NONE
    private val relay = AlarmStateRelay({ sent += String(it, Charsets.UTF_8); accept }, { now }, { fault })

    private fun body(state: Int, phrase: String = "OK") = """{"alarmState":$state,"alarmPhrase":"$phrase","x":1}"""

    @Test fun firstPollIsSent_withWatchWireKeys() {
        relay.onAlarmDataPolled(body(2, "ALARM"))
        val o = JSONObject(sent.single())
        assertEquals(2, o.getInt("alarm_state"))
        assertEquals("ALARM", o.getString("alarm_phrase"))
    }

    @Test fun unchangedStateWithinKeepAlive_isNotResent() {
        relay.onAlarmDataPolled(body(0))
        now += ALARM_KEEP_ALIVE_MS - 1
        relay.onAlarmDataPolled(body(0))
        assertEquals(1, sent.size)
    }

    @Test fun unchangedStateAtKeepAlive_isResent() {
        relay.onAlarmDataPolled(body(0))
        now += ALARM_KEEP_ALIVE_MS
        relay.onAlarmDataPolled(body(0))
        assertEquals(2, sent.size)
    }

    @Test fun changeIsSentImmediately() {
        relay.onAlarmDataPolled(body(0))
        now += 250
        relay.onAlarmDataPolled(body(1, "WARNING"))
        assertEquals(2, sent.size)
    }

    @Test fun failedSendIsRetriedOnNextPoll() {
        accept = false
        relay.onAlarmDataPolled(body(1))
        accept = true
        now += 250
        relay.onAlarmDataPolled(body(1))
        assertEquals(2, sent.size)
    }

    @Test fun silenceOnFailure_neverSendsAnything() {
        listOf(null, "", "  ", "not json", "{", "[]", """{"foo":1}""", """{"alarmState":"2"}""",
            """{"alarmState":null}""", """{"alarmState":-1}""", """{"alarmState":99999999999}""")
            .forEach { relay.onAlarmDataPolled(it) }
        now += 10 * ALARM_KEEP_ALIVE_MS
        relay.onAlarmDataPolled(null)
        assertEquals(emptyList<String>(), sent)
    }

    @Test fun failedPollAfterSuccess_producesNoKeepAlive() {
        relay.onAlarmDataPolled(body(0))
        now += 5 * ALARM_KEEP_ALIVE_MS
        relay.onAlarmDataPolled(null)
        assertEquals(1, sent.size)
    }

    private fun stamped(state: Int, ts: String) = """{"alarmState":$state,"alarmPhrase":"x","dataTimeStr":"$ts"}"""
    private fun states() = sent.map { JSONObject(it).getInt("alarm_state") }

    // ---- F1 fail-loud asymmetry (safety-review-pre-batch7): frozen-state fixtures ----

    @Test fun nonOkState_isRelayedEvenWithFaultAndStaleData() {
        listOf(1, 2, 3).forEach { st ->
            sent.clear()
            fault = BridgeFault.OSD_WRONG_DATASOURCE
            now += 10 * OSD_DATA_FRESH_MS // same frozen timestamp for far longer than the window
            relay.onAlarmDataPolled(stamped(st, "20260101T000000"))
            now += 10 * OSD_DATA_FRESH_MS
            relay.onAlarmDataPolled(stamped(st, "20260101T000000")) // keep-alive while it persists
            assertEquals(listOf(st, st), states())
        }
    }

    @Test fun okState_withheldWhileFaultActive() {
        fault = BridgeFault.OSD_REJECTS_DATA
        relay.onAlarmDataPolled(stamped(0, "20260101T000001"))
        now += 5_000
        relay.onAlarmDataPolled(stamped(0, "20260101T000006"))
        assertEquals(emptyList<Int>(), states())
    }

    @Test fun okState_withheldWhenTimestampFrozenBeyondWindow_thenResumesWhenItAdvances() {
        relay.onAlarmDataPolled(stamped(0, "20260101T000000"))
        assertEquals(1, sent.size)
        now += OSD_DATA_FRESH_MS + 1
        relay.onAlarmDataPolled(stamped(0, "20260101T000000")) // same body repeated: OSD stopped analysing
        now += ALARM_KEEP_ALIVE_MS
        relay.onAlarmDataPolled(stamped(0, "20260101T000000"))
        assertEquals(1, sent.size)
        now += 250
        relay.onAlarmDataPolled(stamped(0, "20260101T000030")) // analysis resumed
        assertEquals(listOf(0, 0), states())
    }

    @Test fun okState_stillSentWhileFrozenWithinWindow() {
        relay.onAlarmDataPolled(stamped(0, "20260101T000000"))
        now += OSD_DATA_FRESH_MS
        relay.onAlarmDataPolled(stamped(0, "20260101T000000"))
        assertEquals(2, sent.size)
    }

    @Test fun okState_resumesOnlyOnceFaultClears() {
        fault = BridgeFault.NO_WATCH_DATA
        relay.onAlarmDataPolled(stamped(0, "20260101T000001"))
        assertEquals(0, sent.size)
        fault = BridgeFault.NONE
        now += 5_000
        relay.onAlarmDataPolled(stamped(0, "20260101T000006"))
        assertEquals(listOf(0), states())
    }

    @Test fun alarmThenStale_returnToOkIsWithheld() {
        relay.onAlarmDataPolled(stamped(2, "20260101T000000"))
        now += 2 * OSD_DATA_FRESH_MS
        relay.onAlarmDataPolled(stamped(0, "20260101T000000"))
        assertEquals(listOf(2), states())
    }

    @Test fun parse_missingPhraseDefaultsToEmpty_longStateAccepted() {
        assertEquals(AlarmState(3, ""), parseAlarmState("""{"alarmState":3}"""))
        assertNull(parseAlarmState("""{"alarmPhrase":"OK"}"""))
    }
}
