package com.seizureguard.phone.bridge

enum class BridgeFault { NONE, NO_WATCH_DATA, OSD_UNREACHABLE, OSD_WRONG_DATASOURCE, OSD_REJECTS_DATA }

const val NO_WATCH_DATA_MS = 30_000L
const val POST_OK_STALE_MS = 20_000L
const val MAX_POST_FAILURES = 3

fun evaluate(
    nowMs: Long,
    lastWatchMsgAtMs: Long,
    lastPostOkAtMs: Long,
    consecutivePostFailures: Int,
    lastOutcome: PostOutcome,
): BridgeFault = when {
    lastOutcome == PostOutcome.WRONG_DATASOURCE -> BridgeFault.OSD_WRONG_DATASOURCE
    lastOutcome == PostOutcome.OSD_PARSE_ERROR -> BridgeFault.OSD_REJECTS_DATA
    nowMs - lastWatchMsgAtMs > NO_WATCH_DATA_MS -> BridgeFault.NO_WATCH_DATA
    consecutivePostFailures >= MAX_POST_FAILURES || nowMs - lastPostOkAtMs > POST_OK_STALE_MS ->
        BridgeFault.OSD_UNREACHABLE
    else -> BridgeFault.NONE
}
