package com.seizureguard.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gestiona la comunicación Wear Data Layer entre el reloj y el teléfono.
 *
 * Protocolo OSD (compatible con SdDataSourceAw.java de OpenSeizureDetector V5):
 *   /osd/accel_data   → reloj envía 750 floats (30s de magnitud en milli-g)
 *   /osd/alarm_state  → teléfono responde con 1 byte (0=OK, 1=WARNING, 2=ALARM)
 *
 * Analogía Python:
 *   Es como un socket cliente que envía bytes y recibe una respuesta.
 *   El Wear Data Layer maneja la reconexión Bluetooth automáticamente.
 *
 * Cómo funciona internamente:
 *   Wearable.getMessageClient() devuelve un cliente que envía mensajes
 *   al nodo (node) conectado — en este caso, el teléfono Android pareado.
 *   `getConnectedNodes()` descubre el nodo automáticamente.
 *
 * @param context Contexto Android. Debe ser el applicationContext del Service.
 */
class WearDataLayerManager(private val context: Context) {

    private val messageClient: MessageClient by lazy {
        Wearable.getMessageClient(context)
    }

    /**
     * Envía una ventana de datos del acelerómetro al teléfono.
     *
     * El array de floats se serializa a ByteBuffer little-endian antes de enviar.
     * El teléfono deserializa en el mismo orden.
     *
     * @param samples FloatArray de exactamente 750 muestras en milli-g.
     *                En modo debug, pasar FloatArray(750) { it.toFloat() + 1 }
     *                (números 1.0..750.0) para verificar orden de llegada.
     */
    suspend fun sendAccelData(samples: FloatArray) {
        val bytes = floatsToBytes(samples)
        sendToAllNodes(PATH_ACCEL_DATA, bytes)
    }

    /**
     * Registra un listener para recibir el alarmState del teléfono.
     * El teléfono envía 1 byte: el alarmState (0=OK, 1=WARNING, 2=ALARM, etc.)
     *
     * @param onAlarmState Callback que recibe el alarmState como Int.
     * @return El listener registrado — guardarlo para poder removerlo después.
     */
    fun addAlarmStateListener(
        onAlarmState: (Int) -> Unit
    ): MessageClient.OnMessageReceivedListener {
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == PATH_ALARM_STATE && event.data.isNotEmpty()) {
                val alarmState = event.data[0].toInt()
                Log.d(TAG, "alarmState recibido del teléfono: $alarmState")
                onAlarmState(alarmState)
            }
        }
        messageClient.addListener(listener)
        return listener
    }

    /**
     * Remueve el listener registrado con [addAlarmStateListener].
     * Llamar en onDestroy() del Service para evitar leaks.
     */
    fun removeAlarmStateListener(listener: MessageClient.OnMessageReceivedListener) {
        messageClient.removeListener(listener)
    }

    /**
     * Serializa un FloatArray a ByteArray en formato little-endian.
     * Little-endian es el orden que usa SdDataSourceAw.java al deserializar.
     *
     * Analogía Python:
     *   import struct
     *   bytes = struct.pack(f'<{len(samples)}f', *samples)
     */
    fun floatsToBytes(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    /**
     * Deserializa un ByteArray a FloatArray (little-endian).
     * Útil para tests y para verificar round-trip de datos.
     */
    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.getFloat() }
    }

    private suspend fun sendToAllNodes(path: String, data: ByteArray) {
        try {
            val nodes = Wearable.getNodeClient(context)
                .connectedNodes
                .await()

            if (nodes.isEmpty()) {
                Log.w(TAG, "No hay nodos conectados — el teléfono no está disponible")
                return
            }

            nodes.forEach { node ->
                messageClient.sendMessage(node.id, path, data).await()
                Log.d(TAG, "Enviado $path a ${node.displayName} (${data.size} bytes)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando mensaje Wear Data Layer: $path", e)
        }
    }

    companion object {
        const val PATH_ACCEL_DATA  = "/osd/accel_data"
        const val PATH_ALARM_STATE = "/osd/alarm_state"
        private const val TAG = "WearDataLayerManager"
    }
}
