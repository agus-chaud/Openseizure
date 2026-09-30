package com.seizureguard.wear.alarm

/**
 * ARGB colors of the status line, kept outside Compose so their contrast on the black Wear
 * background is unit-testable. Every status text color must be >= 4.5:1 on black (WCAG AA) and the
 * safety-relevant ones must be visually distinct from each other.
 */
object DisplayStatusColors {
    const val WARNING = 0xFFF57F17L   // amber-orange
    /** Light red (was #B71C1C, ~3.2:1 on black). */
    const val ALARM = 0xFFFF4D6AL
    const val DEGRADED = 0xFFFFC107L  // amber
    /** Cyan: "alarms are silenced". Not amber (degraded) nor red (alarm). */
    const val MUTED = 0xFF4DD0E1L
    /** Light purple: an OSD fault, not an alarm. Replaces the old brown (#6D4C41, ~2.4:1 on black). */
    const val SYSTEM_FAULT = 0xFFCE93D8L
}
