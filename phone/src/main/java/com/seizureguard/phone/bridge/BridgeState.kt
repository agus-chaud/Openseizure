package com.seizureguard.phone.bridge

const val HEALTH_TICK_MS = 10_000L
const val SETTLE_POLL_DELAY_MS = 250L      // GET /data this long after a successful POST /data
const val IDLE_POLL_MS = 5_000L            // safety poll when no POST-triggered poll happened
const val WAKE_LOCK_TIMEOUT_MS = 10L * 60 * 60 * 1000
const val LATCH_CLEAR_OK_STREAK = 2        // consecutive OK outcomes needed to clear a latched fault
const val DEFAULT_HANDSHAKE_BATTERY = 100  // only used if OSD asks for settings before the watch sent any

/** Only an accepted accel POST can have produced a fresh OSD decision worth polling for. */
fun shouldPollAfterPost(outcome: PostOutcome): Boolean = outcome == PostOutcome.OK

/** Delay until the next idle safety poll is due (0 = due now). */
fun idlePollDelayMs(nowMs: Long, lastPollAtMs: Long): Long =
    (IDLE_POLL_MS - (nowMs - lastPollAtMs)).coerceIn(0L, IDLE_POLL_MS)

/** API 34 rejects a connectedDevice FGS unless a prerequisite such as BLUETOOTH_CONNECT is granted (DV-1). */
fun foregroundPrerequisiteMissing(sdkInt: Int, bluetoothConnectGranted: Boolean): Boolean =
    sdkInt >= 34 && !bluetoothConnectGranted

/** Settings to hand OSD on a `sendSettings` handshake: cached watch values, else safe defaults. */
fun handshakeSettings(cached: WatchSettings?): WatchSettings =
    cached ?: WatchSettings(DEFAULT_HANDSHAKE_BATTERY, 25)

/** Single-slot "latest wins" mailbox: no queue, an unconsumed item is replaced (and reported). */
class LatestSlot<T : Any> {
    private var item: T? = null

    /** Stores [value]; returns true when it displaced an item nobody had consumed yet. */
    @Synchronized fun offer(value: T): Boolean {
        val replaced = item != null
        item = value
        return replaced
    }

    @Synchronized fun take(): T? {
        val v = item
        item = null
        return v
    }
}

/**
 * Thread-safe health inputs for [evaluate]. Clocks start at [startMs] (service start), NOT 0, so
 * the first tick cannot raise a false NO_WATCH_DATA / OSD_UNREACHABLE.
 *
 * Latch interpretation (design: WRONG_DATASOURCE / OSD_REJECTS_DATA are "latched immediately"):
 * the fault is raised on the first bad response and held until [LATCH_CLEAR_OK_STREAK] consecutive
 * `OK` outcomes arrive, so a single stray OK cannot silently clear it. UNREACHABLE resets the streak;
 * SEND_SETTINGS is neutral (it leaves the streak unchanged).
 */
class BridgeState(startMs: Long) {
    private var lastWatchMsgAtMs = startMs
    private var lastPostOkAtMs = startMs
    private var consecutiveFailures = 0
    private var lastOutcome = PostOutcome.OK
    private var latched: PostOutcome? = null
    private var okStreak = 0
    private var malformed = 0L
    private var dropped = 0L
    private var cachedSettings: WatchSettings? = null

    @Synchronized fun onValidAccel(nowMs: Long) { lastWatchMsgAtMs = nowMs }
    @Synchronized fun onMalformed() { malformed++ }
    @Synchronized fun onDropped() { dropped++ }
    @Synchronized fun onSettings(s: WatchSettings) { cachedSettings = s }
    @Synchronized fun settings(): WatchSettings? = cachedSettings
    @Synchronized fun malformedCount(): Long = malformed
    @Synchronized fun droppedCount(): Long = dropped

    @Synchronized fun onPostOutcome(outcome: PostOutcome, nowMs: Long) {
        lastOutcome = outcome
        when (outcome) {
            PostOutcome.UNREACHABLE -> { consecutiveFailures++; okStreak = 0 }
            PostOutcome.WRONG_DATASOURCE, PostOutcome.OSD_PARSE_ERROR -> {
                consecutiveFailures = 0; okStreak = 0; latched = outcome
            }
            PostOutcome.OK -> {
                consecutiveFailures = 0; lastPostOkAtMs = nowMs
                if (latched != null && ++okStreak >= LATCH_CLEAR_OK_STREAK) { latched = null; okStreak = 0 }
            }
            PostOutcome.SEND_SETTINGS -> { consecutiveFailures = 0; lastPostOkAtMs = nowMs }
        }
    }

    @Synchronized fun fault(nowMs: Long): BridgeFault =
        evaluate(nowMs, lastWatchMsgAtMs, lastPostOkAtMs, consecutiveFailures, latched ?: lastOutcome)
}
