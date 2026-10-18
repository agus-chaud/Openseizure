package com.seizureguard.wear.schedule

import android.content.Context
import android.util.Log
import com.seizureguard.wear.data.WearDataLayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends `/osd/monitoring_paused` (DEC-072) from a process-lifetime scope: the service that triggers it is about to
 * call stopSelf() (which cancels its own scope), and the alarm receiver returns immediately. Bounded and never
 * throws; a lost message fails safe (the phone behaves as before the schedule existed).
 */
object MonitoringPausedMessage {
    private const val TAG = "MonitoringPausedMessage"
    const val TIMEOUT_MS = 5_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun send(context: Context, nextStartMs: Long) {
        val manager = WearDataLayerManager(context.applicationContext)
        scope.launch {
            val delivered = try {
                withTimeoutOrNull(TIMEOUT_MS) { manager.sendMonitoringPaused(nextStartMs) }
            } catch (e: Exception) {
                Log.e(TAG, "Fallo enviando monitoring_paused", e)
                null
            }
            Log.i(TAG, "monitoring_paused (until $nextStartMs) enviado al teléfono: ${delivered ?: "sin confirmar"}")
        }
    }
}
