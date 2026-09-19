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
    private val relay = AlarmStateRelay({ sent += String(it, Charsets.UTF_8); accept }, { now })

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

    @Test fun parse_missingPhraseDefaultsToEmpty_longStateAccepted() {
        assertEquals(AlarmState(3, ""), parseAlarmState("""{"alarmState":3}"""))
        assertNull(parseAlarmState("""{"alarmPhrase":"OK"}"""))
    }
}
