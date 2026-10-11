package com.seizureguard.phone.bridge

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/** Real [WatchLink]: node discovery and sends like [WearAlarmSender]. Blocking: call off the main thread. */
internal class WearWatchLink(private val context: Context) : WatchLink {

    override fun connectedNodes(): List<WatchNode>? = try {
        Tasks.await(Wearable.getNodeClient(context).connectedNodes, TIMEOUT_S, TimeUnit.SECONDS)
            .map { WatchNode(it.id, it.displayName) }
    } catch (e: Exception) {
        Log.w(TAG, "connectedNodes check failed", e)
        null
    }

    override fun sendResume(nodeId: String): Boolean = try {
        Tasks.await(
            Wearable.getMessageClient(context).sendMessage(nodeId, OsdBridgeService.PATH_RESUME_MONITORING, ByteArray(0)),
            TIMEOUT_S, TimeUnit.SECONDS,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "resume_monitoring send failed", e)
        false
    }

    private companion object {
        const val TAG = "WearWatchLink"
        const val TIMEOUT_S = 3L
    }
}
