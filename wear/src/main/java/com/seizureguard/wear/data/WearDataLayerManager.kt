package com.seizureguard.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gestiona la comunicación Wear Data Layer entre el reloj y la app OpenSeizureDetector V5.0.
 *
 * IMPORTANTE (Fase A, 2026-06-05): el formato de los mensajes se ADAPTA a lo que
 * `SdDataSourceAw.java` de OSD ya espera (no al revés). Por eso:
 *
 *   /osd/accel_data   → reloj envía JSON {"samples":[m0, m1, ...]} (magnitudes en milli-g, UTF-8).
 *                       OSD intenta parsear este formato PRIMERO (json.has("samples")).
 *   /osd/alarm_state  → OSD responde JSON {"alarm_state": <int>, "alarm_phrase": "<texto>"} (UTF-8).
 *
 * Antes el reloj mandaba floats binarios LE crudos y esperaba 1 byte de vuelta — eso NO matcheaba
 * el parser de OSD (que en su fallback binario lee int16, no float32, y responde JSON). Ver engram
 * `architecture/seizureguard-aw-contract`.
 *
 * @param context Contexto Android. Debe ser el applicationContext del Service.
 */
class WearDataLayerManager(private val context: Context) {

    private val messageClient: MessageClient by lazy {
        Wearable.getMessageClient(context)
    }

    /**
     * Envía una ventana de datos del acelerómetro a la app OSD como JSON {"samples":[...]}.
     *
     * @param samples FloatArray de magnitud en milli-g (típicamente 125 por chunk de transporte;
     *                en modo secuencial debug puede llevar numeración continua para verificar orden).
     */
    suspend fun sendAccelData(samples: FloatArray) {
        val bytes = samplesToJsonBytes(samples)
        sendToAllNodes(PATH_ACCEL_DATA, bytes)
    }

    /**
     * Registra un listener para recibir el alarmState de la app OSD.
     * OSD envía JSON: {"alarm_state": <int>, "alarm_phrase": "<texto>"}.
     *
     * @param onAlarmState Callback que recibe el alarmState como Int (0=OK, 1=WARNING, 2+=ALARM).
     * @return El listener registrado — guardarlo para poder removerlo después.
     */
    fun addAlarmStateListener(
        onAlarmState: (Int) -> Unit
    ): MessageClient.OnMessageReceivedListener {
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == PATH_ALARM_STATE && event.data.isNotEmpty()) {
                val alarmState = parseAlarmState(event.data)
                if (alarmState != null) {
                    Log.d(TAG, "alarmState recibido de OSD: $alarmState")
                    onAlarmState(alarmState)
                } else {
                    // No crasheamos el listener ante un payload ilegible: lo logueamos fuerte.
                    // (Un mensaje malformado de OSD NO debe tumbar la recepción de futuros estados.)
                    Log.e(TAG, "alarm_state ilegible (no es el JSON OSD esperado): " +
                        String(event.data, Charsets.UTF_8))
                }
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
     * Serializa las magnitudes (milli-g) a JSON {"samples":[...]} en UTF-8.
     * Es el formato que SdDataSourceAw.java intenta parsear primero (json.has("samples"),
     * leyendo cada valor con samples.getDouble(i)).
     */
    fun samplesToJsonBytes(samples: FloatArray): ByteArray {
        val arr = JSONArray()
        for (s in samples) arr.put(s.toDouble())
        val obj = JSONObject().put("samples", arr)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Parsea el alarm_state que envía OSD: {"alarm_state": <int>, "alarm_phrase": "..."}.
     * Retorna null si el payload no es el JSON esperado (el caller loguea y lo ignora,
     * sin crashear — preferimos perder un mensaje malformado a tumbar el listener).
     */
    fun parseAlarmState(data: ByteArray): Int? = try {
        JSONObject(String(data, Charsets.UTF_8)).getInt("alarm_state")
    } catch (e: Exception) {
        null
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
