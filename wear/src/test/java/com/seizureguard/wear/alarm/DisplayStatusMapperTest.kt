package com.seizureguard.wear.alarm

import com.seizureguard.wear.alarm.DisplayStatus.ALARM
import com.seizureguard.wear.alarm.DisplayStatus.DEGRADED
import com.seizureguard.wear.alarm.DisplayStatus.NORMAL
import com.seizureguard.wear.alarm.DisplayStatus.SYSTEM_FAULT
import com.seizureguard.wear.alarm.DisplayStatus.WARNING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 7 follow-up (HIGH-1): the watch screen must never falsely reassure.
 * Pure JVM tests of the display decision (no Compose, no Robolectric).
 */
class DisplayStatusMapperTest {

    private fun map(state: Int, degraded: Boolean, stale: Boolean) =
        DisplayStatusMapper.map(state, pipelineDegraded = degraded, alarmStateStale = stale)

    // (label, representative alarm states)
    private val classes = listOf(
        "OK" to intArrayOf(0),
        "WARNING" to intArrayOf(1),
        "ALARM" to intArrayOf(2, 3, 5),
        "SYSTEM_FAULT" to intArrayOf(4, 7, 8, 99, -1),
        "MUTE" to intArrayOf(6)
    )

    /** Healthy pipeline, fresh alarm state: the display follows the state. */
    private val healthyFresh = mapOf(
        "OK" to NORMAL, "WARNING" to WARNING, "ALARM" to ALARM,
        "SYSTEM_FAULT" to SYSTEM_FAULT, "MUTE" to NORMAL
    )

    @Test
    fun healthy_and_fresh_followsTheAlarmState() {
        for ((label, states) in classes) for (s in states) {
            assertEquals("$label($s) healthy+fresh", DisplayState(healthyFresh.getValue(label)),
                map(s, degraded = false, stale = false))
        }
    }

    @Test
    fun degraded_and_fresh_overridesEverythingExceptALiveAlarm() {
        for ((label, states) in classes) for (s in states) {
            val expected = if (label == "ALARM") DisplayState(ALARM, degradedHint = true)
            else DisplayState(DEGRADED)
            assertEquals("$label($s) degraded+fresh", expected, map(s, degraded = true, stale = false))
        }
    }

    @Test
    fun staleAlarmState_isAlwaysDegraded_evenForAlarm_whateverTheHealth() {
        for ((label, states) in classes) for (s in states) {
            for (degraded in listOf(false, true)) {
                assertEquals("$label($s) degraded=$degraded stale", DisplayState(DEGRADED),
                    map(s, degraded = degraded, stale = true))
            }
        }
    }

    @Test
    fun aStaleAlarm_isNeverShownAsALiveAlarm() {
        for (s in intArrayOf(2, 3, 5)) {
            assertTrue(map(s, degraded = true, stale = true).status != ALARM)
            assertTrue(map(s, degraded = false, stale = true).status != ALARM)
        }
    }

    @Test
    fun aFreshAlarm_isNeverHidden_byDegradationFromAnotherCause() {
        for (s in intArrayOf(2, 3, 5)) {
            val d = map(s, degraded = true, stale = false)
            assertEquals(ALARM, d.status)
            assertTrue("degraded hint expected", d.degradedHint)
        }
    }

    @Test
    fun degradedNeverLooksReassuring() {
        for ((_, states) in classes) for (s in states) {
            for (stale in listOf(false, true)) {
                assertTrue("degraded must not show NORMAL",
                    map(s, degraded = true, stale = stale).status != NORMAL)
            }
        }
    }

    @Test
    fun noHintWhenNotAliveAlarmUnderDegradation() {
        assertFalse(map(0, degraded = true, stale = false).degradedHint)
        assertFalse(map(2, degraded = false, stale = false).degradedHint)
    }
}
