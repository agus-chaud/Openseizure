package com.seizureguard.phone.bridge

import android.content.Context
import android.util.Log

// ── Signed constants (DEC-070) ────────────────────────────────────────────────

/**
 * Silence from the watch after which the first recovery retry runs. Signed 2026-10-11, CLINICAL_SIGNOFF.md.
 * Lower = earlier recovery attempt but more retries wasted on short outages (e.g. a charging watch).
 */
const val NO_DATA_RETRY_AFTER_MS = 3L * 60 * 60 * 1000

/** Gap between consecutive retries (retries at 3 h, 4 h, 5 h). Signed 2026-10-11, CLINICAL_SIGNOFF.md. */
const val NO_DATA_RETRY_INTERVAL_MS = 60L * 60 * 1000

/** Retries per outage; the counter only resets when valid data returns. Signed 2026-10-11, CLINICAL_SIGNOFF.md. */
const val NO_DATA_MAX_RETRIES = 3

/**
 * Silence from the watch after which the one vibrating alert is posted (partial exception to DEC-057).
 * Signed 2026-10-11, CLINICAL_SIGNOFF.md. Lower = earlier warning but more false "no data" alerts; higher =
 * a longer stretch of unprotected sleep before anyone is told.
 */
const val NO_DATA_ALERT_AFTER_MS = 8L * 60 * 60 * 1000

/**
 * Longest scheduled daytime pause the phone accepts from `/osd/monitoring_paused` (DEC-072). The real pause is 12 h
 * (10:00 to 22:00, 13 h across a DST change); a payload further out than this is treated as corrupt and ignored, so a
 * bad message can never silence the escalation for days. A safety guard, not a clinical threshold.
 */
const val MAX_SCHEDULED_PAUSE_MS = 16L * 60 * 60 * 1000

/** Record-keeping only (not clinical): how often the last-valid-data stamp is written to disk. */
const val NO_DATA_WRITE_INTERVAL_MS = 60_000L

enum class NoDataAction { NONE, RETRY, ALERT }

/**
 * Pure escalation policy for a long stretch without valid watch data.
 *
 *  - [stoppedByUser]: the watch said the user stopped monitoring on purpose -> nothing, ever, until data returns.
 *  - ALERT as soon as the outage reaches [NO_DATA_ALERT_AFTER_MS] and none was posted yet (exactly one per outage).
 *  - RETRY number n (0-based) is due at [NO_DATA_RETRY_AFTER_MS] + n x [NO_DATA_RETRY_INTERVAL_MS], never more than
 *    [NO_DATA_MAX_RETRIES]. If the app was not running when a retry came due, the missed retries still run, but
 *    never closer than [NO_DATA_RETRY_INTERVAL_MS] to the previous one ([lastRetryAtMs]), so a late wake-up cannot
 *    burn the whole budget in three ticks. A retry may still run after the alert was posted.
 *  - A clock that went backwards ([nowMs] before [outageStartMs]) counts as zero elapsed time.
 *  - [pausedUntilMs] (DEC-072): the watch announced the scheduled daytime pause. Before that instant -> NONE. After
 *    it, the outage clock starts at the pause end (never earlier than [outageStartMs]), so a watch that does not
 *    resume at 22:00 gets its retries at +3 h and the alert at +8 h counted from 22:00, with the signed constants.
 */
fun noDataAction(
    nowMs: Long,
    outageStartMs: Long,
    retriesDone: Int,
    alertPosted: Boolean,
    stoppedByUser: Boolean,
    lastRetryAtMs: Long? = null,
    pausedUntilMs: Long = 0L,
): NoDataAction {
    if (stoppedByUser) return NoDataAction.NONE
    if (pausedUntilMs > 0L && nowMs < pausedUntilMs) return NoDataAction.NONE
    val baseline = if (pausedUntilMs > 0L) maxOf(outageStartMs, pausedUntilMs) else outageStartMs
    val elapsed = (nowMs - baseline).coerceAtLeast(0L)
    if (!alertPosted && elapsed >= NO_DATA_ALERT_AFTER_MS) return NoDataAction.ALERT
    if (retriesDone < NO_DATA_MAX_RETRIES && elapsed >= NO_DATA_RETRY_AFTER_MS + retriesDone * NO_DATA_RETRY_INTERVAL_MS) {
        val sinceLast = lastRetryAtMs?.let { nowMs - it }
        if (sinceLast == null || sinceLast < 0 || sinceLast >= NO_DATA_RETRY_INTERVAL_MS) return NoDataAction.RETRY
    }
    return NoDataAction.NONE
}

// ── Persisted outage clock ────────────────────────────────────────────────────

/** What the last node check saw: decides the cause text of the alert. UNKNOWN = never checked or the check failed. */
enum class NodeCheck { CONNECTED, NOT_CONNECTED, UNKNOWN }

data class OutageState(
    /** Start of the outage clock: the last valid data, or the end of a scheduled pause if that is later. */
    val outageStartMs: Long,
    val retriesDone: Int,
    val lastRetryAtMs: Long?,
    val alertPosted: Boolean,
    val stoppedByUser: Boolean,
    val lastNodeCheck: NodeCheck,
    /** End of the announced scheduled pause (epoch ms), 0 when none. Informational: [outageStartMs] already uses it. */
    val pausedUntilMs: Long = 0L,
)

/**
 * The outage clock. Wall clock on purpose (a process restart or a phone reboot must not reset it), persisted through
 * [StringStore], and the last-valid-data stamp is written at most once per [NO_DATA_WRITE_INTERVAL_MS]. Every other
 * field is written the moment it changes (rare: a retry, the alert, a stop, a recovery).
 *
 * Thread-safe: valid data arrives on the main thread, the health tick on a worker.
 */
class NoDataTracker(
    private val store: StringStore,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private var loaded = false
    private var lastValidMs = 0L
    private var retriesDone = 0
    private var lastRetryAtMs: Long? = null
    private var alertPosted = false
    private var stoppedByUser = false
    private var lastNodeCheck = NodeCheck.UNKNOWN
    private var pausedUntilMs = 0L
    private var lastWriteMono = Long.MIN_VALUE

    /** Service start: with no persisted stamp the baseline is "now"; an existing one is kept (the outage goes on). */
    @Synchronized
    fun onServiceStart() {
        load()
        if (lastValidMs <= 0L) {
            lastValidMs = wallClockMs()
            persistAll()
        }
    }

    /** The user pressed "Start bridge": a fresh baseline and clean counters. */
    @Synchronized
    fun onUserStart() {
        load()
        pausedUntilMs = 0L
        lastValidMs = wallClockMs()
        clearEscalation()
        persistAll()
    }

    /**
     * A valid accel chunk arrived. Returns true when it ended an escalation (retries, an alert or a user stop were
     * pending), so the caller can take the alert down.
     */
    @Synchronized
    fun onValidData(): Boolean {
        load()
        lastValidMs = wallClockMs()
        val escalated = retriesDone > 0 || lastRetryAtMs != null || alertPosted || stoppedByUser || pausedUntilMs > 0L
        if (escalated) {
            clearEscalation()
            persistAll()
            return true
        }
        val mono = monotonicMs()
        if (lastWriteMono == Long.MIN_VALUE || mono - lastWriteMono >= NO_DATA_WRITE_INTERVAL_MS) {
            lastWriteMono = mono
            store.put(KEY_LAST_VALID, lastValidMs.toString())
        }
        return false
    }

    /** The watch reported a deliberate stop: no retries, no alert until valid data returns. Any posted alert is void. */
    @Synchronized
    fun onStoppedByUser() {
        load()
        stoppedByUser = true
        alertPosted = false
        persistAll()
    }

    /**
     * The watch announced the scheduled daytime pause (DEC-072), ending at [untilMs]. Ignored (returns false) unless
     * [untilMs] is in the future and within [MAX_SCHEDULED_PAUSE_MS]. Counters reset and any posted alert is void: the
     * outage clock restarts at [untilMs]. Valid data ends the pause early (the watch was started by hand).
     */
    @Synchronized
    fun onScheduledPause(untilMs: Long): Boolean {
        load()
        val now = wallClockMs()
        if (untilMs <= now || untilMs - now > MAX_SCHEDULED_PAUSE_MS) return false
        clearEscalation()
        pausedUntilMs = untilMs
        persistAll()
        return true
    }

    /** End of the pause when one is in force right now, else 0. */
    @Synchronized
    fun activePauseUntilMs(): Long {
        load()
        return if (pausedUntilMs > wallClockMs()) pausedUntilMs else 0L
    }

    @Synchronized
    fun decide(): NoDataAction {
        load()
        return noDataAction(wallClockMs(), lastValidMs, retriesDone, alertPosted, stoppedByUser, lastRetryAtMs, pausedUntilMs)
    }

    @Synchronized
    fun recordRetry(check: NodeCheck) {
        load()
        retriesDone++
        lastRetryAtMs = wallClockMs()
        lastNodeCheck = check
        persistAll()
    }

    @Synchronized
    fun recordNodeCheck(check: NodeCheck) {
        load()
        lastNodeCheck = check
        persistAll()
    }

    @Synchronized
    fun recordAlertPosted() {
        load()
        alertPosted = true
        persistAll()
    }

    @Synchronized
    fun snapshot(): OutageState {
        load()
        return OutageState(
            if (pausedUntilMs > 0L) maxOf(lastValidMs, pausedUntilMs) else lastValidMs, retriesDone, lastRetryAtMs, alertPosted, stoppedByUser, lastNodeCheck,
            pausedUntilMs,
        )
    }

    private fun clearEscalation() {
        retriesDone = 0
        lastRetryAtMs = null
        alertPosted = false
        stoppedByUser = false
        lastNodeCheck = NodeCheck.UNKNOWN
        pausedUntilMs = 0L
    }

    private fun load() {
        if (loaded) return
        loaded = true
        lastValidMs = store.get(KEY_LAST_VALID)?.toLongOrNull() ?: 0L
        retriesDone = (store.get(KEY_RETRIES)?.toIntOrNull() ?: 0).coerceIn(0, NO_DATA_MAX_RETRIES)
        lastRetryAtMs = store.get(KEY_LAST_RETRY)?.toLongOrNull()
        alertPosted = store.get(KEY_ALERT) == "1"
        stoppedByUser = store.get(KEY_STOPPED) == "1"
        lastNodeCheck = NodeCheck.values().firstOrNull { it.name == store.get(KEY_NODE_CHECK) } ?: NodeCheck.UNKNOWN
        pausedUntilMs = (store.get(KEY_PAUSED_UNTIL)?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
    }

    private fun persistAll() {
        store.put(KEY_LAST_VALID, lastValidMs.toString())
        store.put(KEY_RETRIES, retriesDone.toString())
        store.put(KEY_LAST_RETRY, lastRetryAtMs?.toString() ?: "")
        store.put(KEY_ALERT, if (alertPosted) "1" else "0")
        store.put(KEY_STOPPED, if (stoppedByUser) "1" else "0")
        store.put(KEY_NODE_CHECK, lastNodeCheck.name)
        store.put(KEY_PAUSED_UNTIL, pausedUntilMs.toString())
        lastWriteMono = monotonicMs()
    }

    private companion object {
        const val KEY_LAST_VALID = "nd_last_valid_ms"
        const val KEY_RETRIES = "nd_retries"
        const val KEY_LAST_RETRY = "nd_last_retry_ms"
        const val KEY_ALERT = "nd_alert_posted"
        const val KEY_STOPPED = "nd_stopped_by_user"
        const val KEY_NODE_CHECK = "nd_node_check"
        const val KEY_PAUSED_UNTIL = "nd_paused_until_ms"
    }
}

/** Process-wide tracker shared by the service and SetupActivity (one in-memory copy of the persisted clock). */
internal object NoDataTracking {
    @Volatile private var instance: NoDataTracker? = null

    fun of(context: Context): NoDataTracker = instance ?: synchronized(this) {
        instance ?: NoDataTracker(PrefsStringStore(context)).also { instance = it }
    }

    /** Tests only: forget the cached tracker so the next [of] reloads from storage. */
    internal fun resetForTests() {
        instance = null
    }
}

// ── Retry / alert actions ─────────────────────────────────────────────────────

data class WatchNode(val id: String, val name: String)

/** Seam over the Wear Data Layer so the escalation runs on the JVM. Both calls block: never on the main thread. */
interface WatchLink {
    /** Connected nodes, or null when the check itself failed. */
    fun connectedNodes(): List<WatchNode>?

    /** Sends `/osd/resume_monitoring` (empty payload) to [nodeId]; true when the Data Layer accepted it. */
    fun sendResume(nodeId: String): Boolean
}

/** Where the 8 h alert is shown; implementations must not throw. */
interface NoDataAlertSink {
    fun post(sinceMs: Long, cause: NodeCheck, retriesTried: Int)
    fun cancel()
}

/**
 * Runs the retry and the alert chosen by [noDataAction] (DEC-070). [onTick] is called from the 10 s health tick on a
 * worker thread; the other entry points are called from the message listener. Nothing here may throw into the
 * service: a failing retry is logged and still counted, so a broken Data Layer cannot loop every tick.
 */
class NoDataEscalator(
    private val tracker: NoDataTracker,
    private val link: WatchLink,
    private val alert: NoDataAlertSink,
    private val faultLog: FaultLog,
    private val relisten: () -> Unit,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    /** Service start: keep or create the baseline, and put back an alert a reboot or a restart took off the screen. */
    fun onServiceStart() {
        tracker.onServiceStart()
        val s = tracker.snapshot()
        if (s.alertPosted && !s.stoppedByUser) {
            Log.i(TAG, "Restoring the no-data alert after a restart (outage since ${s.outageStartMs})")
            alert.post(s.outageStartMs, s.lastNodeCheck, s.retriesDone)
        }
    }

    /** A valid accel chunk arrived. Cheap: one rate-limited write at most. */
    fun onValidData() {
        if (tracker.onValidData()) {
            Log.i(TAG, "Watch data is back: no-data escalation reset")
            alert.cancel()
        }
    }

    /** `/osd/monitoring_stopped`: the user stopped the watch on purpose. */
    fun onStoppedByUser() {
        tracker.onStoppedByUser()
        alert.cancel()
        Log.i(TAG, "Watch reported a deliberate stop: no retries or alert until data returns")
    }

    /**
     * `/osd/monitoring_paused` (DEC-072): the watch stopped on schedule and will start again at [untilMs]. Silent: the
     * pause is expected, nothing is retried or alerted until then, and a posted alert is taken down.
     */
    fun onScheduledPause(untilMs: Long) {
        if (tracker.onScheduledPause(untilMs)) {
            alert.cancel()
            Log.i(TAG, "Watch announced the scheduled pause until $untilMs: no retries or alert until then")
        } else {
            Log.w(TAG, "Ignored a scheduled-pause message with an unusable end time ($untilMs)")
        }
    }

    /** End of the scheduled pause in force now (epoch ms), or 0: drives the paused status text. */
    fun activePauseUntilMs(): Long = runCatching { tracker.activePauseUntilMs() }.getOrDefault(0L)

    fun onTick() {
        when (tracker.decide()) {
            NoDataAction.NONE -> Unit
            NoDataAction.RETRY -> retry()
            NoDataAction.ALERT -> alertNow()
        }
    }

    private fun retry() {
        val number = tracker.snapshot().retriesDone + 1
        Log.i(TAG, "No watch data: retry $number of $NO_DATA_MAX_RETRIES (re-register listener, ask the watch to resume)")
        runCatching { relisten() }.onFailure { Log.w(TAG, "Re-registering the listener failed", it) }
        val nodes = runCatching { link.connectedNodes() }.getOrNull()
        val check = checkOf(nodes)
        var sent = 0
        if (check == NodeCheck.CONNECTED) {
            nodes?.forEach { n -> if (runCatching { link.sendResume(n.id) }.getOrDefault(false)) sent++ }
        }
        tracker.recordRetry(check)
        val kind = if (check == NodeCheck.CONNECTED) FaultKind.NO_DATA_RETRY_WATCH_CONNECTED
        else FaultKind.NO_DATA_RETRY_WATCH_NOT_CONNECTED
        record(kind)
        Log.i(TAG, "Retry $number done: watch=$check, resume request accepted by $sent node(s)")
    }

    private fun alertNow() {
        val before = tracker.snapshot()
        val fresh = checkOf(runCatching { link.connectedNodes() }.getOrNull())
        val cause = if (fresh != NodeCheck.UNKNOWN) fresh else before.lastNodeCheck
        if (fresh != NodeCheck.UNKNOWN) tracker.recordNodeCheck(fresh)
        Log.i(TAG, "No watch data since ${before.outageStartMs}: posting the alert (cause=$cause, retries=${before.retriesDone})")
        alert.post(before.outageStartMs, cause, before.retriesDone)
        tracker.recordAlertPosted()
        record(FaultKind.NO_DATA_ALERT)
    }

    private fun checkOf(nodes: List<WatchNode>?) = when {
        nodes == null -> NodeCheck.UNKNOWN
        nodes.isEmpty() -> NodeCheck.NOT_CONNECTED
        else -> NodeCheck.CONNECTED
    }

    /** Point events (start == end): see [FaultKind.isEvent]; the morning summary never counts them. */
    private fun record(kind: FaultKind) {
        val now = wallClockMs()
        runCatching { faultLog.add(FaultPeriod(kind, now, now)) }.onFailure { Log.w(TAG, "Could not log $kind", it) }
    }

    private companion object {
        const val TAG = "NoDataEscalator"
    }
}
