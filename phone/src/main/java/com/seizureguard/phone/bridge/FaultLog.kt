package com.seizureguard.phone.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

const val FAULT_LOG_RETENTION_MS = 14L * 24 * 60 * 60 * 1000
const val FAULT_LOG_MAX_ENTRIES = 300

enum class FaultKind {
    NO_WATCH_DATA, OSD_UNREACHABLE, OSD_WRONG_DATASOURCE, OSD_REJECTS_DATA, OSD_DATA_STALE, SERVICE_DOWN,

    /** Watch and companion transport-contract versions differ (Batch 8b). Not a [BridgeFault]: see [FaultLog.onVersionCompatibility]. */
    VERSION_MISMATCH;

    companion object {
        fun from(fault: BridgeFault): FaultKind? = if (fault == BridgeFault.NONE) null else valueOf(fault.name)
    }
}

/** Wall-clock period (comparable across reboots); [endMs] null means still open. */
data class FaultPeriod(val kind: FaultKind, val startMs: Long, val endMs: Long?)

/** Minimal persistence seam so the log and liveness logic run on the JVM without Android. */
interface StringStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

internal class PrefsStringStore(context: Context) : StringStore {
    private val prefs = context.applicationContext.getSharedPreferences("bridge_history", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
}

/** Bounded, persisted list of fault periods. Written only on transitions, never per tick. */
class FaultLog(private val store: StringStore, private val wallClockMs: () -> Long = System::currentTimeMillis) {
    private var periods: MutableList<FaultPeriod>? = null

    /** Tracks the single open [BridgeFault]-derived period; a [FaultKind.VERSION_MISMATCH] period is never touched here. */
    @Synchronized
    fun onFault(fault: BridgeFault) {
        val list = load()
        val kind = FaultKind.from(fault)
        val open = list.lastOrNull { it.endMs == null && it.kind != FaultKind.VERSION_MISMATCH }
        if (open?.kind == kind) return
        val now = wallClockMs()
        if (open != null) list[list.indexOf(open)] = open.copy(endMs = now)
        if (kind != null) list += FaultPeriod(kind, now, null)
        save(list, now)
    }

    /**
     * Tracks the contract-version period independently of [onFault]: it opens on the first incompatible result,
     * closes ONLY on a [VersionCompatibility.MATCH] from a real watch settings message, and never closes, replaces or
     * hides another open period. Writes only on a transition.
     */
    @Synchronized
    fun onVersionCompatibility(result: VersionCompatibility) {
        val list = load()
        val open = list.lastOrNull { it.endMs == null && it.kind == FaultKind.VERSION_MISMATCH }
        val now = wallClockMs()
        when {
            result.isIncompatible && open == null -> list += FaultPeriod(FaultKind.VERSION_MISMATCH, now, null)
            !result.isIncompatible && open != null -> list[list.indexOf(open)] = open.copy(endMs = maxOf(now, open.startMs))
            else -> return
        }
        save(list, now)
    }

    /**
     * Closes any open [BridgeFault]-derived period at [endMs] (clean stop, or a restart after the process died).
     * A [FaultKind.VERSION_MISMATCH] period is NOT closed here: the incompatibility outlives the bridge process and is
     * only resolved by a [VersionCompatibility.MATCH] from a real watch message (Batch 8c, R10-F3).
     */
    @Synchronized
    fun closeOpen(endMs: Long) {
        val list = load()
        var changed = false
        list.forEachIndexed { i, p ->
            if (p.endMs == null && p.kind != FaultKind.VERSION_MISMATCH) { list[i] = p.copy(endMs = maxOf(endMs, p.startMs)); changed = true }
        }
        if (changed) save(list, wallClockMs())
    }

    @Synchronized
    fun add(period: FaultPeriod) {
        val list = load()
        list += period
        save(list, wallClockMs())
    }

    @Synchronized
    fun periods(): List<FaultPeriod> = load().toList()

    private fun load(): MutableList<FaultPeriod> = periods ?: parse(store.get(KEY)).toMutableList().also { periods = it }

    private fun save(list: MutableList<FaultPeriod>, nowMs: Long) {
        list.removeAll { it.endMs != null && it.endMs < nowMs - FAULT_LOG_RETENTION_MS }
        while (list.size > FAULT_LOG_MAX_ENTRIES) {
            // An open version period is live state, not history: drop the oldest other entry instead.
            val evict = list.indexOfFirst { !(it.kind == FaultKind.VERSION_MISMATCH && it.endMs == null) }
            list.removeAt(if (evict >= 0) evict else 0)
        }
        store.put(KEY, JSONArray().also { arr ->
            list.forEach { arr.put(JSONObject().put("k", it.kind.name).put("s", it.startMs).apply { it.endMs?.let { e -> put("e", e) } }) }
        }.toString())
    }

    private fun parse(json: String?): List<FaultPeriod> {
        if (json == null) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val kind = FaultKind.values().firstOrNull { it.name == o.optString("k") } ?: return@mapNotNull null
                FaultPeriod(kind, o.getLong("s"), if (o.has("e")) o.getLong("e") else null)
            }
        } catch (e: Exception) {
            emptyList() // a corrupt log must never crash the service
        }
    }

    private companion object {
        const val KEY = "fault_periods"
    }
}
