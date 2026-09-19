package com.seizureguard.phone.bridge

import org.json.JSONObject

/** DEC-046 `/osd/settings` payload as sent by the watch: {"battery":0-100,"sample_freq":Hz}. */
data class WatchSettings(val battery: Int, val sampleFreq: Int)

/**
 * Defensive validation of inbound Data Layer payloads (design Threat Matrix row 2).
 * Anything that is not exactly the DEC-046 shape returns null and must be dropped and counted,
 * never forwarded to OSD. Never throws.
 */
object WatchMessageParser {
    const val ACCEL_CHUNK_SAMPLES = 125          // 5 s at 25 Hz: one OSD analysis window per chunk
    const val MAX_PAYLOAD_BYTES = 8 * 1024       // a valid chunk is ~2.5 KB; reject absurd sizes early
    const val MAX_ABS_MILLI_G = 100_000.0        // sanity bound, far above any real sensor reading
    private const val MAX_SAMPLE_FREQ = 200

    /** `{"samples":[125 finite numbers]}` -> milli-g samples, or null if malformed. */
    fun parseAccel(bytes: ByteArray?): DoubleArray? = parse(bytes) { o ->
        val arr = o.optJSONArray("samples")
        if (arr == null || arr.length() != ACCEL_CHUNK_SAMPLES) return@parse null
        val out = DoubleArray(ACCEL_CHUNK_SAMPLES)
        for (i in 0 until ACCEL_CHUNK_SAMPLES) {
            val d = (arr.opt(i) as? Number)?.toDouble() ?: return@parse null
            if (d.isNaN() || d.isInfinite() || Math.abs(d) > MAX_ABS_MILLI_G) return@parse null
            out[i] = d
        }
        out
    }

    /** `{"battery":0..100,"sample_freq":1..200}` (both integers, both required), or null. */
    fun parseSettings(bytes: ByteArray?): WatchSettings? = parse(bytes) { o ->
        val battery = wholeNumber(o.opt("battery")) ?: return@parse null
        val freq = wholeNumber(o.opt("sample_freq")) ?: return@parse null
        if (battery !in 0..100 || freq !in 1..MAX_SAMPLE_FREQ) return@parse null
        WatchSettings(battery, freq)
    }

    private fun wholeNumber(v: Any?): Int? {
        val d = (v as? Number)?.toDouble() ?: return null
        return if (!d.isNaN() && !d.isInfinite() && d == Math.floor(d) && Math.abs(d) < Int.MAX_VALUE) d.toInt() else null
    }

    private inline fun <T> parse(bytes: ByteArray?, block: (JSONObject) -> T?): T? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_PAYLOAD_BYTES) return null
        return try {
            block(JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (e: Exception) {
            null
        }
    }
}
