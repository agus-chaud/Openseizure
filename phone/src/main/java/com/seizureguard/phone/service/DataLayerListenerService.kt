package com.seizureguard.phone.service

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.seizureguard.phone.ml.PhoneAccelChunkProcessor
import com.seizureguard.phone.ml.PhoneCircularBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Recibe `/osd/accel_data` del watch, acumula en [PhoneCircularBuffer] y responde
 * `/osd/alarm_state` con 1 byte (contrato DEC-039).
 *
 * La inferencia real TFLite se integra en un PR posterior; aquí solo stub + logs.
 */
class DataLayerListenerService : WearableListenerService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val buffer = PhoneCircularBuffer(inputSize = DEFAULT_INPUT_SIZE)
    private val processor by lazy {
        PhoneAccelChunkProcessor(
            buffer = buffer,
            infer = { window ->
                Log.d(TAG, "inference_stub len=${window.size}")
                STUB_ALARM_OK
            },
            structuredLog = { key, detail -> Log.d(TAG, "$key $detail") }
        )
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != PATH_ACCEL_DATA) return

        val result = processor.processChunk(messageEvent.data)
        if (!result.didInfer) return

        val nodeId = messageEvent.sourceNodeId
        serviceScope.launch {
            try {
                Wearable.getMessageClient(this@DataLayerListenerService)
                    .sendMessage(nodeId, PATH_ALARM_STATE, byteArrayOf(result.alarmState))
                    .await()
                Log.d(TAG, "alarm_state_sent node=$nodeId value=${result.alarmState}")
            } catch (e: Exception) {
                Log.e(TAG, "alarm_state_send_failed", e)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel("service destroyed")
        super.onDestroy()
    }

    companion object {
        const val PATH_ACCEL_DATA = "/osd/accel_data"
        const val PATH_ALARM_STATE = "/osd/alarm_state"
        private const val TAG = "SeizurePhoneDL"
        private const val DEFAULT_INPUT_SIZE = 750
        private const val STUB_ALARM_OK: Byte = 0
    }
}
