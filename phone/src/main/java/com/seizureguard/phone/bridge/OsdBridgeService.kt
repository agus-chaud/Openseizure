package com.seizureguard.phone.bridge

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Seam wired to AlarmStateRelay + BridgeNotifications in onCreate. Called from background threads. */
internal interface BridgeObserver {
    /** Raw `GET /data` body after a poll, or null if the poll failed. */
    fun onAlarmDataPolled(body: String?)

    /** Current fault, every [HEALTH_TICK_MS]. */
    fun onHealthTick(fault: BridgeFault)

    companion object {
        val NONE = object : BridgeObserver {
            override fun onAlarmDataPolled(body: String?) = Unit
            override fun onHealthTick(fault: BridgeFault) = Unit
        }
    }
}

/**
 * Foreground service bridging the watch's DEC-046 Data Layer messages to OSD's Garmin HTTP ingress
 * (watch-osd-message-delivery, design decisions #2/#3/#4/#8).
 *
 * Concurrency model:
 *  - MessageClient callbacks (main thread) only validate + drop into a [LatestSlot] and signal; they
 *    never block. Malformed payloads are dropped and counted, never forwarded.
 *  - One worker coroutine drains the slots sequentially (settings first, then accel) and does the
 *    blocking POSTs on Dispatchers.IO. A slow OSD (up to 4 s connect + 4 s read) therefore delays only
 *    this worker; at most ONE unconsumed chunk waits, a newer chunk replaces it (counted in
 *    droppedCount). No queue, no retry.
 *  - An idle-poll coroutine and the 10 s health tick run independently; polls are mutually exclusive
 *    (a poll already in flight makes another skip).
 */
class OsdBridgeService : Service() {

    internal var observer: BridgeObserver = BridgeObserver.NONE
    private val forwarder = OsdHttpForwarder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val accelSlot = LatestSlot<DoubleArray>()
    private val settingsSlot = LatestSlot<WatchSettings>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pollMutex = Mutex()
    private lateinit var state: BridgeState
    private lateinit var freshness: OsdDataFreshness
    private var wakeLock: PowerManager.WakeLock? = null
    private val listenerLock = Any()
    private var messageListener: MessageClient.OnMessageReceivedListener? = null // guarded by listenerLock
    private var listenerDestroyed = false                                        // guarded by listenerLock
    private var listenerRetryDelayMs: Long? = null                               // guarded by listenerLock
    private var lastReregisterAtMs: Long? = null                                 // health-tick coroutine only
    private var started = false
    @Volatile private var lastPollAtMs = 0L
    private var lastFault = BridgeFault.NONE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Health clocks start "now", not 0, so the first tick cannot raise a false fault.
        freshness = OsdDataFreshness({ SystemClock.elapsedRealtime() })
        state = BridgeState(SystemClock.elapsedRealtime(), freshness)
        lastPollAtMs = SystemClock.elapsedRealtime()
        observer = defaultObserver()
    }

    private fun defaultObserver(): BridgeObserver {
        val relay = AlarmStateRelay(
            WearAlarmSender(this)::send, { SystemClock.elapsedRealtime() },
            { state.fault(SystemClock.elapsedRealtime()) }, freshness, // live fault, not the 10 s-old tick value
        )
        val notifications = BridgeNotifications(this)
        val (faultLog, liveness) = BridgeHistory.of(this)
        return object : BridgeObserver {
            override fun onAlarmDataPolled(body: String?) = relay.onAlarmDataPolled(body)
            override fun onHealthTick(fault: BridgeFault) {
                notifications.onHealthTick(fault)
                faultLog.onFault(fault)
                liveness.touch()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_STICKY // idempotent: a redelivered start must not re-acquire/re-register
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        runCatching { BridgeHistory.onServiceStart(this) }.onFailure { Log.e(TAG, "Could not record service start", it) }
        acquireWakeLock()
        registerMessageListener()
        scope.launch { safeLoop("worker") { runWorker() } }
        scope.launch { safeLoop("idlePoll") { runIdlePoll() } }
        scope.launch { safeLoop("healthTick") { runHealthTick() } }
        return START_STICKY
    }

    override fun onDestroy() {
        // Order (DEC-022/DEC-025): stop producers -> release WakeLock -> cancel scope -> super last.
        synchronized(listenerLock) {
            listenerDestroyed = true // stops retries and re-registration
            messageListener?.let { Wearable.getMessageClient(this).removeListener(it) }
            messageListener = null
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wake.close()
        scope.cancel()
        super.onDestroy()
    }

    // ── Foreground / wake lock ────────────────────────────────────────────────

    /** Returns false (after alerting loudly) when the OS refuses the connectedDevice FGS (DV-1). */
    private fun enterForeground(): Boolean {
        val btGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (foregroundPrerequisiteMissing(Build.VERSION.SDK_INT, btGranted)) {
            Log.e(TAG, "BLUETOOTH_CONNECT not granted: connectedDevice FGS cannot start")
            BridgeNotifications.postStartFailure(this)
            return false
        }
        return try {
            ServiceCompat.startForeground(
                this, BridgeNotifications.STATUS_NOTIFICATION_ID,
                BridgeNotifications.buildStatus(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (e: Exception) { // SecurityException / ForegroundServiceStartNotAllowedException
            Log.e(TAG, "startForeground refused", e)
            BridgeNotifications.postStartFailure(this)
            false
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SeizureGuard:OsdBridge")
            .also { it.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    // ── Inbound (main thread, non-blocking) ───────────────────────────────────

    private fun registerMessageListener() {
        synchronized(listenerLock) {
            if (listenerDestroyed) return
            val listener = messageListener
                ?: MessageClient.OnMessageReceivedListener { onMessage(it) }.also { messageListener = it }
            Wearable.getMessageClient(this).addListener(listener)
                .addOnSuccessListener { synchronized(listenerLock) { listenerRetryDelayMs = null } }
                .addOnFailureListener { onListenerRegistrationFailed(it) }
        }
    }

    private fun onListenerRegistrationFailed(e: Exception) {
        val wait = synchronized(listenerLock) {
            nextListenerRetryDelayMs(listenerRetryDelayMs).also { listenerRetryDelayMs = it }
        }
        Log.w(TAG, "MessageClient.addListener failed, retrying in ${wait}ms", e)
        scope.launch { delay(wait); registerMessageListener() }
    }

    private fun reregisterMessageListener() {
        synchronized(listenerLock) {
            if (listenerDestroyed) return
            messageListener?.let { Wearable.getMessageClient(this).removeListener(it) }
            registerMessageListener()
        }
    }

    private fun onMessage(event: MessageEvent) {
        when (event.path) {
            PATH_ACCEL -> {
                val samples = WatchMessageParser.parseAccel(event.data)
                if (samples == null) {
                    state.onMalformed()
                    Log.w(TAG, "Dropped malformed accel message (total=${state.malformedCount()})")
                    return
                }
                state.onValidAccel(SystemClock.elapsedRealtime())
                if (accelSlot.offer(samples)) {
                    state.onDropped() // a slow OSD let a chunk go stale: recorded, never silent
                    Log.w(TAG, "Replaced an unsent chunk (total=${state.droppedCount()})")
                }
                wake.trySend(Unit)
            }
            PATH_SETTINGS -> {
                val s = WatchMessageParser.parseSettings(event.data)
                if (s == null) {
                    state.onMalformed()
                    Log.w(TAG, "Dropped malformed settings message (total=${state.malformedCount()})")
                    return
                }
                state.onSettings(s)
                settingsSlot.offer(s)
                wake.trySend(Unit)
            }
        }
    }

    // ── Loops ─────────────────────────────────────────────────────────────────

    private suspend fun runWorker() {
        while (true) {
            wake.receiveCatching().getOrNull() ?: return // channel closed on destroy
            while (true) {
                val settings = settingsSlot.take()
                val accel = accelSlot.take()
                if (settings == null && accel == null) break
                settings?.let { postSettings(it) }
                accel?.let { postAccel(it) }
            }
        }
    }

    private suspend fun postSettings(s: WatchSettings) {
        val body = OsdPayloadCodec.formBody(OsdPayloadCodec.settingsJson(s.battery, s.sampleFreq))
        record(withContext(Dispatchers.IO) { forwarder.post("/settings", body) })
    }

    private suspend fun postAccel(samples: DoubleArray) {
        val body = OsdPayloadCodec.formBody(OsdPayloadCodec.rawDataJson(samples))
        val outcome = withContext(Dispatchers.IO) { forwarder.post("/data", body) }
        record(outcome)
        when {
            outcome == PostOutcome.SEND_SETTINGS -> postSettings(handshakeSettings(state.settings()))
            shouldPollAfterPost(outcome) -> { delay(SETTLE_POLL_DELAY_MS); poll() }
        }
    }

    private fun record(outcome: PostOutcome) {
        state.onPostOutcome(outcome, SystemClock.elapsedRealtime())
        if (outcome != PostOutcome.OK) Log.i(TAG, "POST outcome: $outcome")
    }

    private suspend fun poll() {
        if (!pollMutex.tryLock()) return
        try {
            val body = withContext(Dispatchers.IO) { forwarder.getData() }
            lastPollAtMs = SystemClock.elapsedRealtime()
            observer.onAlarmDataPolled(body)
        } finally {
            pollMutex.unlock()
        }
    }

    private suspend fun runIdlePoll() {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val wait = idlePollDelayMs(now, lastPollAtMs)
            if (wait > 0) { delay(wait); continue }
            poll()
            lastPollAtMs = SystemClock.elapsedRealtime() // also on lock-skip: avoid a busy loop
        }
    }

    private suspend fun runHealthTick() {
        while (true) {
            delay(HEALTH_TICK_MS)
            wakeLock?.let { if (it.isHeld) it.acquire(WAKE_LOCK_TIMEOUT_MS) } // renew the 10 h timeout
            val fault = state.fault(SystemClock.elapsedRealtime())
            if (fault != lastFault) Log.w(TAG, "Bridge fault: $lastFault -> $fault")
            lastFault = fault
            observer.onHealthTick(fault)
            val now = SystemClock.elapsedRealtime()
            if (shouldReregisterListener(now, state.lastWatchMessageAtMs(), lastReregisterAtMs)) {
                lastReregisterAtMs = now
                Log.w(TAG, "No valid watch message for over ${LISTENER_REREGISTER_AFTER_MS}ms: re-registering listener")
                reregisterMessageListener()
            }
        }
    }

    /** Restarts [body] after an unexpected error so one bug cannot silently kill a night-long loop. */
    private suspend fun safeLoop(name: String, body: suspend () -> Unit) {
        while (true) {
            try {
                body()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!scope.isActive) return
                Log.e(TAG, "$name loop failed, restarting", e)
                delay(1_000)
            }
        }
    }

    companion object {
        private const val TAG = "OsdBridgeService"
        const val PATH_ACCEL = "/osd/accel_data"
        const val PATH_SETTINGS = "/osd/settings"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OsdBridgeService::class.java))
        }
    }
}
