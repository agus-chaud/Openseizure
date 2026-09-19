package com.seizureguard.phone.bridge

import org.json.JSONObject

/** Max age of OSD's analysis timestamp for `alarm_state=0` to still be trusted (watch feeds OSD every ~5 s). */
const val OSD_DATA_FRESH_MS = 15_000L

/**
 * OSD's analysis time from `GET /data`: `dataTimeStr` (`yyyyMMdd'T'HHmmss`, second resolution, device-local time;
 * SdData.toDataString). Opaque on purpose: it is only compared for inequality, so timezone and format do not matter.
 */
fun parseDataTime(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return try {
        JSONObject(body).opt("dataTimeStr") as? String
    } catch (e: Exception) {
        null
    }
}

/**
 * Tracks when OSD's data timestamp last CHANGED. The clock starts "now" so startup gets a grace window, not a
 * false stale. A first sample only records the value (one sample cannot prove OSD is currently analysing).
 */
class OsdDataFreshness(
    private val nowMs: () -> Long,
    private val freshMs: Long = OSD_DATA_FRESH_MS,
) {
    private var last: String? = null
    private var changedAtMs = nowMs()

    @Synchronized fun onSample(timestamp: String?) {
        if (timestamp == null || timestamp == last) return
        if (last != null) changedAtMs = nowMs()
        last = timestamp
    }

    @Synchronized fun isFresh(): Boolean = nowMs() - changedAtMs <= freshMs
}
