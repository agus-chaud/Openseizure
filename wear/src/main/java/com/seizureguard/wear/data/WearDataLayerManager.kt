package com.seizureguard.wear.data

import android.content.Context
import android.util.Log
import com.seizureguard.wear.BuildConfig
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
 * TRANSPORT (watch-osd-message-delivery, Batch 7 / T7.2): the peer of these messages is the phone
 * *companion* (`:phone` module), which translates them into OSD's HTTP ingest. The message format
 * (`/osd/...` paths + DEC-046 JSON) is IDENTICAL in both modes: the Wear Data Layer routes by
 * AppKey = applicationId + signing certificate, so the effective destination is fixed by the
 * build flavor's `applicationId` (`companion` -> this same package, shared with `:phone`;
 * `osdDirect` -> `uk.org.openseizuredetector`), not by an address in code. The direct-to-OSD path
 * is retained behind [BuildConfig.OSD_DIRECT_MODE] and was NOT deleted.
 *
 * @param context Android context. Must be the Service's applicationContext.
 * @param osdDirectMode true only in the `osdDirect` flavor (messages go straight to OSD). Defaults
 *   to [BuildConfig.OSD_DIRECT_MODE]; a parameter so both modes are testable.
 */
class WearDataLayerManager(
    private val context: Context,
    osdDirectMode: Boolean = BuildConfig.OSD_DIRECT_MODE
) {

    /** Active transport mode (fixed at compile time by the `transport` flavor). */
    val transportMode: TransportMode = TransportMode.from(osdDirectMode)

    /**
     * Message peer for each transport mode: the `:phone` companion (default) or the OSD app
     * directly. The Data Layer routes by AppKey, so the flavor's `applicationId` is what actually
     * decides who receives the messages; [peerPackage] documents and logs that expectation.
     */
    enum class TransportMode(val peerPackage: String) {
        COMPANION("com.seizureguard.wear"),
        OSD_DIRECT("uk.org.openseizuredetector");

        companion object {
            fun from(osdDirectMode: Boolean): TransportMode =
                if (osdDirectMode) OSD_DIRECT else COMPANION
        }
    }

    private val messageClient: MessageClient by lazy {
        Wearable.getMessageClient(context)
    }

    /**
     * Envía una ventana de datos del acelerómetro a la app OSD como JSON {"samples":[...]}.
     *
     * @param samples FloatArray de magnitud en milli-g (típicamente 125 por chunk de transporte;
     *                en modo secuencial debug puede llevar numeración continua para verificar orden).
     */
    suspend fun sendAccelData(samples: FloatArray): Boolean {
        val bytes = samplesToJsonBytes(samples)
        return sendToAllNodes(PATH_ACCEL_DATA, bytes)
    }

    /**
     * Registra un listener para recibir el alarmState de la app OSD.
     * OSD envía JSON: {"alarm_state": <int>, "alarm_phrase": "<texto>"}.
     *
     * @param onAlarmState Callback receiving the RAW alarmState Int (0-7 from OSD, or any other
     *   value). Interpretation (what vibrates vs. what is a silent fault) belongs to AlarmStateManager.
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
     * Envía el estado/configuración del reloj a OSD por /osd/settings.
     * OSD lo espera para marcar `haveSettings = true` y dejar de reportar "Data source fault".
     * Formato que parsea SdDataSourceAw.handleSettings(): {"battery":<0-100>, "sample_freq":25}.
     *
     * @param batteryPct nivel de batería del reloj (0-100).
     * @param sampleFreq frecuencia de muestreo en Hz (25 por defecto).
     */
    suspend fun sendSettings(batteryPct: Int, sampleFreq: Int = 25) {
        sendToAllNodes(PATH_SETTINGS, settingsToJsonBytes(batteryPct, sampleFreq))
    }

    /** Serializa los settings al JSON que espera OSD: {"battery":N,"sample_freq":F}. */
    fun settingsToJsonBytes(batteryPct: Int, sampleFreq: Int): ByteArray {
        val obj = JSONObject()
            .put("battery", batteryPct)
            .put("sample_freq", sampleFreq)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Registra un listener para el pedido de settings de OSD (/osd/send_settings con payload "start").
     * Cuando OSD arranca la fuente Android Wear, manda "start"; el reloj debe responder con /osd/settings.
     *
     * @param onStartRequest callback que se invoca cuando OSD pide los settings.
     * @return el listener registrado — guardarlo para removerlo en onDestroy().
     */
    fun addSendSettingsListener(
        onStartRequest: () -> Unit
    ): MessageClient.OnMessageReceivedListener {
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == PATH_SEND_SETTINGS) {
                val payload = String(event.data, Charsets.UTF_8).trim()
                Log.d(TAG, "send_settings recibido de OSD: '$payload'")
                if (payload == "start") onStartRequest()
            }
        }
        messageClient.addListener(listener)
        return listener
    }

    /** Remueve el listener registrado con [addSendSettingsListener]. */
    fun removeSendSettingsListener(listener: MessageClient.OnMessageReceivedListener) {
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
        val raw = JSONObject(String(data, Charsets.UTF_8)).get("alarm_state")
        toAlarmState(raw)
    } catch (e: Exception) {
        null
    }

    /**
     * Exact conversion of the JSON value to an Int. `JSONObject.getInt` silently wraps numbers
     * outside the Int range (4294967298 -> 2 = ALARM) and truncates 2.5 -> 2, which would turn a
     * garbled value into a vibrating alarm. Here an out-of-range or non-integral number becomes
     * [ALARM_STATE_INVALID], which `AlarmStateManager.classify` treats as a silent SYSTEM_FAULT.
     * Non-numeric values stay unreadable (null), as before.
     */
    private fun toAlarmState(raw: Any?): Int? {
        val text = when (raw) {
            is Number, is String -> raw.toString().trim()
            else -> return null
        }
        val number = text.toBigDecimalOrNull()
            ?: return if (raw is Number) ALARM_STATE_INVALID else null   // NaN / Infinity
        return try {
            number.intValueExact()
        } catch (e: ArithmeticException) {
            ALARM_STATE_INVALID
        }
    }

    /**
     * @return true si el mensaje se entregó al menos a un nodo, false si no había nodos
     *   conectados o si hubo una excepción. El watchdog del Service usa este booleano para
     *   detectar desconexiones prolongadas (antes este método se tragaba todo en silencio).
     *
     *   NOTE: `true` means ONLY "GMS accepted the message" (transport ack). It does NOT prove the
     *   companion or OSD processed it: the true end-to-end liveness signal is the arrival of
     *   `/osd/alarm_state` (see ALARM_STATE_STALE_MS in SeizureMonitorService).
     */
    private suspend fun sendToAllNodes(path: String, data: ByteArray): Boolean {
        return try {
            val nodes = Wearable.getNodeClient(context)
                .connectedNodes
                .await()

            if (nodes.isEmpty()) {
                Log.w(TAG, "No hay nodos conectados — el teléfono no está disponible")
                return false
            }

            var anyDelivered = false
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, path, data).await()
                Log.d(TAG, "Enviado $path a ${node.displayName} (${data.size} bytes) " +
                    "[transport=$transportMode, peer=${transportMode.peerPackage}]")
                anyDelivered = true
            }
            anyDelivered
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando mensaje Wear Data Layer: $path", e)
            false
        }
    }

    companion object {
        const val PATH_ACCEL_DATA    = "/osd/accel_data"
        const val PATH_ALARM_STATE   = "/osd/alarm_state"
        const val PATH_SETTINGS      = "/osd/settings"       // reloj → OSD: batería + freq
        const val PATH_SEND_SETTINGS = "/osd/send_settings"  // OSD → reloj: pide settings ("start")
        /**
         * Returned by [parseAlarmState] for a numeric alarm_state that is not an exact Int
         * (out of range or fractional). Outside every known OSD state, so it classifies as a
         * silent SYSTEM_FAULT and can never vibrate.
         */
        const val ALARM_STATE_INVALID = Int.MIN_VALUE
        private const val TAG = "WearDataLayerManager"
    }
}
