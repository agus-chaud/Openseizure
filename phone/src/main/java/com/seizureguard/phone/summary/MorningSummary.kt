package com.seizureguard.phone.summary

import com.seizureguard.phone.bridge.FaultKind
import com.seizureguard.phone.bridge.FaultPeriod
import java.util.Calendar
import java.util.TimeZone

const val SUMMARY_HOUR = 8
const val SUMMARY_WINDOW_MS = 12L * 60 * 60 * 1000

/** [minutesByKind] is ordered longest first; kinds can overlap, so it may sum to more than [totalMinutes]. */
data class SummaryData(val interruptions: Int, val totalMinutes: Long, val minutesByKind: List<Pair<FaultKind, Long>>)

private fun minutes(ms: Long) = (ms + 59_999) / 60_000

private fun union(intervals: List<LongRange>): List<LongRange> {
    val merged = mutableListOf<LongRange>()
    for (r in intervals.sortedBy { it.first }) {
        val last = merged.lastOrNull()
        if (last != null && r.first <= last.last) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last) else merged += r
    }
    return merged
}

/** Open periods run to the window end; everything is clipped to the window; overlaps count as one interruption. */
fun buildSummary(periods: List<FaultPeriod>, windowStartMs: Long, windowEndMs: Long): SummaryData {
    val clipped = periods.mapNotNull { p ->
        val s = maxOf(p.startMs, windowStartMs)
        val e = minOf(p.endMs ?: windowEndMs, windowEndMs)
        if (e > s) p.kind to (s..e) else null
    }
    val all = union(clipped.map { it.second })
    val byKind = clipped.groupBy({ it.first }, { it.second }).map { (k, rs) -> k to union(rs).sumOf { it.last - it.first } }
    return SummaryData(
        all.size,
        minutes(all.sumOf { it.last - it.first }),
        byKind.sortedByDescending { it.second }.map { it.first to minutes(it.second) },
    )
}

/** Open fault periods of a dead process end when the process was last seen alive. */
fun withServiceDown(periods: List<FaultPeriod>, down: FaultPeriod?): List<FaultPeriod> =
    if (down == null) periods
    else periods.map { if (it.endMs == null) it.copy(endMs = maxOf(down.startMs, it.startMs)) else it } + down

/** First [hour]:00 local time strictly after [nowMs]. */
fun nextSummaryTriggerMs(nowMs: Long, zone: TimeZone = TimeZone.getDefault(), hour: Int = SUMMARY_HOUR): Long {
    val cal = Calendar.getInstance(zone).apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_MONTH, 1)
    return cal.timeInMillis
}
