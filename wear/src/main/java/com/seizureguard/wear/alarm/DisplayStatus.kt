package com.seizureguard.wear.alarm

/**
 * What the watch screen shows as its status line.
 *
 * Pure decision (no Compose, no Android) so the safety-relevant choices are unit-testable.
 * VISUAL ONLY: nothing here vibrates or makes sound (DEC-057). The vibration path is
 * [AlarmStateManager.handleAlarmState] / [AlarmStateManager.classify] and is not touched.
 */
enum class DisplayStatus {
    /** Nothing to report: the normal "monitoring on/off" text (states 0 and 6/MUTE). */
    NORMAL,
    WARNING,
    ALARM,
    /** OSD reported a system fault (4, 7 or an unknown value). Not an alarm. */
    SYSTEM_FAULT,
    /** The monitoring pipeline is not working; the screen must not look reassuring. */
    DEGRADED
}

/** [status] to show, plus [degradedHint]: also show a small "degraded" hint under a live ALARM. */
data class DisplayState(val status: DisplayStatus, val degradedHint: Boolean = false)

object DisplayStatusMapper {

    /**
     * Decision table (evaluated top to bottom):
     *
     * 1. [alarmStateStale] -> DEGRADED, for ANY last alarm state including ALARM. A stale alarm state
     *    is not a confirmed current value and must not be presented as fresh (spec WCT-4).
     * 2. [pipelineDegraded] and the last state is a FRESH ALARM (2/3/5) -> ALARM with the degraded
     *    hint: a live emergency is never hidden by a fault elsewhere (delivery or sensor).
     * 3. [pipelineDegraded] otherwise -> DEGRADED (overrides OK, WARNING, MUTE and SYSTEM_FAULT).
     * 4. Healthy and fresh -> by [AlarmStateManager.classify]: ALARM, WARNING, SYSTEM_FAULT,
     *    and NORMAL for OK and MUTE.
     *
     * [pipelineDegraded] can only be true while monitoring runs (the service resets it on stop), so
     * no separate "monitoring is on" input is needed and an Activity re-created mid-night cannot
     * hide a DEGRADED state.
     */
    fun map(alarmState: Int, pipelineDegraded: Boolean, alarmStateStale: Boolean): DisplayState {
        val severity = AlarmStateManager.classify(alarmState)
        return when {
            alarmStateStale -> DisplayState(DisplayStatus.DEGRADED)
            pipelineDegraded && severity == AlarmStateManager.Severity.ALARM ->
                DisplayState(DisplayStatus.ALARM, degradedHint = true)
            pipelineDegraded -> DisplayState(DisplayStatus.DEGRADED)
            else -> DisplayState(
                when (severity) {
                    AlarmStateManager.Severity.ALARM -> DisplayStatus.ALARM
                    AlarmStateManager.Severity.WARNING -> DisplayStatus.WARNING
                    AlarmStateManager.Severity.SYSTEM_FAULT -> DisplayStatus.SYSTEM_FAULT
                    AlarmStateManager.Severity.OK, AlarmStateManager.Severity.MUTE -> DisplayStatus.NORMAL
                }
            )
        }
    }
}
