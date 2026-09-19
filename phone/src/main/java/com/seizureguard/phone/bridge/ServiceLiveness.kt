package com.seizureguard.phone.bridge

const val SERVICE_DOWN_THRESHOLD_MS = 120_000L
const val ALIVE_WRITE_INTERVAL_MS = 60_000L

/** A gap counts as downtime only if the user intended bridging and did not press Stop. */
fun serviceDownPeriod(lastAliveMs: Long, nowMs: Long, cleanStop: Boolean, wasBridging: Boolean): FaultPeriod? =
    if (lastAliveMs > 0 && wasBridging && !cleanStop && nowMs - lastAliveMs > SERVICE_DOWN_THRESHOLD_MS) {
        FaultPeriod(FaultKind.SERVICE_DOWN, lastAliveMs, nowMs)
    } else null

/** Persists a "last alive" wall-clock stamp (at most once per [ALIVE_WRITE_INTERVAL_MS]) and the clean-stop flag. */
class ServiceLiveness(
    private val store: StringStore,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private var lastWriteMono = Long.MIN_VALUE

    @Synchronized
    fun touch() {
        val mono = monotonicMs()
        if (lastWriteMono != Long.MIN_VALUE && mono - lastWriteMono < ALIVE_WRITE_INTERVAL_MS) return
        lastWriteMono = mono
        store.put(KEY_ALIVE, wallClockMs().toString())
    }

    /** Called when the service starts: returns the downtime since the previous run, if any, then re-arms. */
    @Synchronized
    fun onServiceStart(wasBridging: Boolean): FaultPeriod? {
        val alive = store.get(KEY_ALIVE)?.toLongOrNull() ?: 0L
        val period = serviceDownPeriod(alive, wallClockMs(), store.get(KEY_CLEAN) == "1", wasBridging)
        store.put(KEY_CLEAN, "0")
        lastWriteMono = Long.MIN_VALUE
        touch()
        return period
    }

    /** The user pressed Stop: the coming gap is intentional, not downtime. */
    @Synchronized
    fun markCleanStop() {
        store.put(KEY_CLEAN, "1")
    }

    fun isCleanStop(): Boolean = store.get(KEY_CLEAN) == "1"

    fun lastAliveMs(): Long = store.get(KEY_ALIVE)?.toLongOrNull() ?: 0L

    private companion object {
        const val KEY_ALIVE = "last_alive_ms"
        const val KEY_CLEAN = "clean_stop"
    }
}

/** Process-wide history shared by the service and SetupActivity (same process, one in-memory cache). */
internal object BridgeHistory {
    @Volatile private var instance: Pair<FaultLog, ServiceLiveness>? = null

    fun of(context: android.content.Context): Pair<FaultLog, ServiceLiveness> = instance ?: synchronized(this) {
        instance ?: PrefsStringStore(context).let { Pair(FaultLog(it), ServiceLiveness(it)) }.also { instance = it }
    }

    fun onServiceStart(context: android.content.Context) {
        val (log, liveness) = of(context)
        val lastAlive = liveness.lastAliveMs()
        val period = liveness.onServiceStart(BridgePrefs.wasBridging(context))
        log.closeOpen(if (lastAlive > 0) lastAlive else System.currentTimeMillis())
        period?.let(log::add)
    }

    fun onCleanStop(context: android.content.Context) {
        val (log, liveness) = of(context)
        liveness.markCleanStop()
        log.closeOpen(System.currentTimeMillis())
    }
}
