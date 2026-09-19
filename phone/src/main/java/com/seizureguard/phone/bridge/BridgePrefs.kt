package com.seizureguard.phone.bridge

import android.content.Context

/** Persists whether the user left the bridge running, so BootReceiver knows whether to resume it. */
object BridgePrefs {
    private const val FILE = "bridge_prefs"
    private const val KEY_WAS_BRIDGING = "was_bridging"

    fun wasBridging(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_WAS_BRIDGING, false)

    fun setWasBridging(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_WAS_BRIDGING, value).apply()
    }
}
