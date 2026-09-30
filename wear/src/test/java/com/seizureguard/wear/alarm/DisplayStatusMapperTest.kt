package com.seizureguard.wear.alarm

import com.seizureguard.wear.alarm.DisplayStatus.ALARM
import com.seizureguard.wear.alarm.DisplayStatus.DEGRADED
import com.seizureguard.wear.alarm.DisplayStatus.MUTED
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
        "SYSTEM_FAULT" to SYSTEM_FAULT, "MUTE" to MUTED
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
    fun mute_isNeverShownAsPlainMonitoring() {
        // H7-6: with OSD muted the watch does not vibrate; the screen must not say "Monitoreo activo".
        assertEquals(DisplayState(MUTED), map(6, degraded = false, stale = false))
        assertTrue(map(6, degraded = false, stale = false).status != NORMAL)
    }

    @Test
    fun mute_precedence_staleAndDegradedOverrideIt_andAnAlarmIsNeverHidden() {
        assertEquals(DisplayState(DEGRADED), map(6, degraded = false, stale = true))
        assertEquals(DisplayState(DEGRADED), map(6, degraded = true, stale = false))
        assertEquals(DisplayState(DEGRADED), map(6, degraded = true, stale = true))
        // A fresh ALARM after a MUTE is displayed as ALARM immediately (mapping is per last state).
        assertEquals(DisplayState(ALARM), map(2, degraded = false, stale = false))
    }

    @Test
    fun onlyOkIsNormal() {
        for ((label, states) in classes) for (s in states) {
            val isNormal = map(s, degraded = false, stale = false).status == NORMAL
            assertEquals("$label($s)", label == "OK", isNormal)
        }
    }

    private fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun contrastOnBlack(argb: Long) = (luminance(argb) + 0.05) / 0.05

    private fun colorDistance(a: Long, b: Long): Double {
        fun d(shift: Int) = (((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)).toDouble()
        return Math.sqrt(d(16) * d(16) + d(8) * d(8) + d(0) * d(0))
    }

    @Test
    fun mutedColor_hasAtLeast4_5ContrastOnBlack_andIsDistinctFromDegradedAndAlarm() {
        assertTrue("muted contrast ${contrastOnBlack(DisplayStatusColors.MUTED)}",
            contrastOnBlack(DisplayStatusColors.MUTED) >= 4.5)
        for (other in longArrayOf(DisplayStatusColors.DEGRADED, DisplayStatusColors.ALARM, DisplayStatusColors.WARNING)) {
            assertTrue("muted too close to ${other.toString(16)}",
                colorDistance(DisplayStatusColors.MUTED, other) > 100)
        }
    }

    @Test
    fun everyStatusColor_hasAtLeast4_5ContrastOnBlack() {
        for ((name, c) in mapOf("WARNING" to DisplayStatusColors.WARNING, "ALARM" to DisplayStatusColors.ALARM,
            "DEGRADED" to DisplayStatusColors.DEGRADED, "MUTED" to DisplayStatusColors.MUTED,
            "SYSTEM_FAULT" to DisplayStatusColors.SYSTEM_FAULT)) {
            assertTrue("$name contrast ${contrastOnBlack(c)}", contrastOnBlack(c) >= 4.5)
        }
    }

    @Test
    fun alarmColor_isDistinctFromEveryOtherStatusColor() {
        for (other in longArrayOf(DisplayStatusColors.SYSTEM_FAULT, DisplayStatusColors.MUTED,
            DisplayStatusColors.DEGRADED, DisplayStatusColors.WARNING)) {
            assertTrue("alarm too close to ${other.toString(16)}", colorDistance(DisplayStatusColors.ALARM, other) > 90)
        }
        // Still clearly red: red channel dominates.
        val a = DisplayStatusColors.ALARM
        assertTrue(((a shr 16) and 0xFF) > 2 * ((a shr 8) and 0xFF))
    }

    @Test
    fun mutedLabel_isExactAndDoesNotReadAsNothingHappening() {
        val xml = java.io.File("src/main/res/values/strings.xml").readText()
        assertTrue(xml.contains(">SILENCIADO, no avisa convulsiones</string>"))
        assertFalse(xml.contains("sin alarmas"))
    }

    @Test
    fun faultColor_hasAtLeast4_5ContrastOnBlack_andIsDistinctFromAlarmDegradedAndMuted() {
        val fault = DisplayStatusColors.SYSTEM_FAULT
        assertTrue("fault contrast ${contrastOnBlack(fault)}", contrastOnBlack(fault) >= 4.5)
        for (other in longArrayOf(DisplayStatusColors.ALARM, DisplayStatusColors.DEGRADED,
            DisplayStatusColors.MUTED, DisplayStatusColors.WARNING)) {
            assertTrue("fault too close to ${other.toString(16)}", colorDistance(fault, other) > 100)
        }
    }

    @Test
    fun testBuildBanner_followsOsdDirectMode() {
        assertTrue(TestBuildBanner.visible(osdDirectMode = true))
        assertFalse(TestBuildBanner.visible(osdDirectMode = false))
        // In the real build: visible in the osdDirect flavor only, nothing in companion.
        assertEquals(com.seizureguard.wear.BuildConfig.FLAVOR == "osdDirect",
            TestBuildBanner.visible(com.seizureguard.wear.BuildConfig.OSD_DIRECT_MODE))
    }

    @Test
    fun noHintWhenNotAliveAlarmUnderDegradation() {
        assertFalse(map(0, degraded = true, stale = false).degradedHint)
        assertFalse(map(2, degraded = false, stale = false).degradedHint)
    }
}
