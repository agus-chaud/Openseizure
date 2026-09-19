package com.seizureguard.phone.bridge

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import org.json.JSONObject

const val PATH_ALARM_STATE = "/osd/alarm_state"
const val ALARM_KEEP_ALIVE_MS = 10_000L

data class AlarmState(val state: Int, val phrase: String)

/** Parses OSD's `GET /data` body (`alarmState`, `alarmPhrase`); null when absent/garbled. Never throws. */
fun parseAlarmState(body: String?): AlarmState? {
    if (body.isNullOrBlank()) return null
    return try {
        val o = JSONObject(body)
        val state = (o.opt("alarmState") as? Number)?.toLong() ?: return null
        if (state < 0 || state > Int.MAX_VALUE) return null
        AlarmState(state.toInt(), o.optString("alarmPhrase", ""))
    } catch (e: Exception) {
        null
    }
}

/** Watch-side wire body (DEC-046): `{"alarm_state":<int>,"alarm_phrase":<str>}`. */
fun alarmStateJson(s: AlarmState): ByteArray =
    JSONObject().put("alarm_state", s.state).put("alarm_phrase", s.phrase).toString().toByteArray(Charsets.UTF_8)

/**
 * Relays OSD's alarm state to the watch on change and as a keep-alive.
 * Life-safety: when OSD cannot be read (null/garbled body, no `alarmState`) NOTHING is sent. The watch's
 * staleness watchdog is the detector; a fabricated FAULT code would vibrate as a false ALARM (design #6).
 * Fail-loud asymmetry (safety-review-pre-batch7 F1): a non-OK state is ALWAYS relayed; `0` (OK, and its keep-alive)
 * is relayed only while the bridge is healthy AND OSD's data is fresh. Otherwise silence lets the watch's staleness
 * watchdog degrade instead of a stale "all OK" masking a dead pipeline.
 * [send] returns true when at least one node accepted the message; a failed send is retried next poll.
 */
class AlarmStateRelay(
    private val send: (ByteArray) -> Boolean,
    private val nowMs: () -> Long,
    private val faultProvider: () -> BridgeFault,
    private val freshness: OsdDataFreshness = OsdDataFreshness(nowMs),
) {
    private var lastSent: AlarmState? = null
    private var lastSentAtMs = 0L

    fun onAlarmDataPolled(body: String?) {
        val s = parseAlarmState(body) ?: return
        freshness.onSample(parseDataTime(body))
        if (s.state == 0 && (faultProvider() != BridgeFault.NONE || !freshness.isFresh())) return
        val now = nowMs()
        val due = s != lastSent || now - lastSentAtMs >= ALARM_KEEP_ALIVE_MS
        if (due && send(alarmStateJson(s))) {
            lastSent = s
            lastSentAtMs = now
        }
    }
}

/** Sends to every connected node, like the watch does. Blocking: call off the main thread. */
internal class WearAlarmSender(private val context: Context) {
    fun send(data: ByteArray): Boolean = try {
        val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes, SEND_TIMEOUT_S, TimeUnit.SECONDS)
        val client = Wearable.getMessageClient(context)
        nodes.count { n ->
            try {
                Tasks.await(client.sendMessage(n.id, PATH_ALARM_STATE, data), SEND_TIMEOUT_S, TimeUnit.SECONDS)
                true
            } catch (e: Exception) {
                Log.w(TAG, "alarm_state send to ${n.displayName} failed", e)
                false
            }
        } > 0
    } catch (e: Exception) {
        Log.w(TAG, "alarm_state relay failed", e)
        false
    }

    private companion object {
        const val TAG = "WearAlarmSender"
        const val SEND_TIMEOUT_S = 3L
    }
}
