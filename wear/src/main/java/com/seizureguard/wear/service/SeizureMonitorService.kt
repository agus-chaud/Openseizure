package com.seizureguard.wear.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seizureguard.wear.BuildConfig
import com.seizureguard.wear.MainActivity
import com.seizureguard.wear.R
import com.seizureguard.wear.data.WearDataLayerManager
import com.seizureguard.wear.logging.CsvLogger
import com.seizureguard.wear.ml.CircularBuffer
import com.google.android.gms.wearable.MessageClient
import kotlin.math.sqrt
import com.seizureguard.wear.alarm.AlarmStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fase 1.1 — Foreground Service de monitoreo nocturno.
 *
 * Un ForegroundService es un Service que el OS Android considera "visible para
 * el usuario" gracias a la notificación persistente. El OS no lo mata cuando
 * la pantalla del reloj se apaga o cuando hay presión de memoria — condición
 * crítica para el monitoreo overnight de 8 horas.
 *
 * Lifecycle del Service:
 *
 *   MainActivity.startService() ──► onCreate()
 *                                       └── createNotificationChannel()
 *                                       └── startForeground(notification)
 *                               ──► onStartCommand()   ← cada vez que se llama startService()
 *                                       └── ACTION_START: reservado para Fase 1.3 (sensores)
 *                                       └── ACTION_STOP: llama stopSelf()
 *                               ──► onDestroy()        ← stopSelf() o kill del OS
 *                                       └── serviceScope.cancel()  ← cancela todas las coroutines
 *
 * Threading model:
 *   - El Service vive en el main thread (como toda app Android).
 *   - serviceScope corre en Dispatchers.Default (pool de threads CPU).
 *   - En Fase 1.3: la captura del acelerómetro y la inferencia TFLite
 *     van a correr como coroutines dentro de serviceScope.
 *
 * Por qué START_STICKY:
 *   Si el OS mata el Service por memoria baja (raro con WakeLock, pero posible),
 *   Android lo reinicia automáticamente con intent=null.
 *   El monitoreo nocturno NUNCA debe quedar detenido sin que el usuario lo sepa.
 */
class SeizureMonitorService : Service() {

    /**
     * CoroutineScope vinculado al lifecycle del Service.
     * Se cancela en onDestroy() — todas las coroutines hijas se cancelan automáticamente.
     * SupervisorJob: si una coroutine falla, las demás no se cancelan.
     */
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Ring buffer circular para chunks de transporte reloj → teléfono.
     *
     * Acumula la magnitud vectorial √(x²+y²+z²) en milli-g de cada muestra del acelerómetro.
     * Se envía un chunk cuando se acumulan [TRANSPORT_CHUNK_SIZE] muestras (~5s a 25Hz).
     *
     * IMPORTANTE: este buffer ya no representa la ventana del modelo (750 timesteps).
     * La ventana de inferencia se arma en phone al concatenar múltiples chunks.
     */
    private val accelerometerBuffer = CircularBuffer(capacity = BUFFER_CAPACITY)
    private var samplesSinceLastChunk = 0
    private var sequentialSampleCounter = 0L

    /**
     * Fase 1.6 — Logger de muestras del acelerómetro a CSV.
     *
     * Solo activo en builds de desarrollo (BuildConfig.DEBUG = true).
     * En release, isLoggingEnabled = false y este objeto nunca abre un archivo.
     *
     * Ver DEC-029, DEC-030, DEC-031, DEC-032 en DECISIONS.md.
     */
    private val csvLogger = CsvLogger(this)

    /**
     * Fase 2.1 — Wear Data Layer: comunicación reloj → teléfono.
     *
     * Encapsula el MessageClient y el protocolo OSD (/osd/accel_data, /osd/alarm_state).
     * Inicializado aquí con `this` como contexto; el Service es un Context válido.
     */
    private val dataLayerManager = WearDataLayerManager(this)

    /**
     * Fase 2.2 — Gestiona la respuesta háptica según el alarmState del teléfono.
     * Ver AlarmStateManager para el mapping de valores a efectos de vibración.
     */
    private val alarmStateManager = AlarmStateManager(this)

    /**
     * Referencia al listener de alarmState para poder removerlo en onDestroy().
     * Sin esto, el MessageClient mantiene una referencia al Service vivo después de
     * que se destruyó → memory leak.
     */
    private var alarmStateListener: MessageClient.OnMessageReceivedListener? = null

    /**
     * Controla si el logging a CSV está activo.
     *
     * BuildConfig.DEBUG es true en builds de debug (Android Studio, Gradle debug variant)
     * y false en builds de release (APK de producción).
     *
     * Por qué una property y no una constante:
     *   Una property permite en el futuro cambiar la lógica (ej: activar via SharedPreferences)
     *   sin cambiar los sitios de uso. Por ahora, el binding a DEBUG es suficiente.
     *
     * Ver DEC-030 en DECISIONS.md para el análisis de por qué solo en DEBUG.
     */
    private val isLoggingEnabled: Boolean
        get() = BuildConfig.DEBUG

    /**
     * Fase 1.3 — SensorManager: captura del acelerómetro 3D a 25Hz.
     *
     * Android usa SensorManager para acceder a todos los sensores del dispositivo.
     * En Wear OS, los sensores del reloj (acelerómetro, frecuencia cardíaca, etc.)
     * se acceden a través de este mismo mecanismo.
     *
     * Por qué TYPE_ACCELEROMETER y no TYPE_LINEAR_ACCELERATION:
     *   - El modelo DeepEpiCnn Run24 fue entrenado con datos de Garmin y PineTime que
     *     reportan aceleración cruda con gravedad incluida (confirmado por Graham Jones,
     *     creador de OpenSeizureDetector).
     *   - Con el reloj en reposo sobre la mesa, un eje muestra ~1000 milli-g (= 1g de gravedad).
     *     Eso solo es posible con TYPE_ACCELEROMETER.
     *   - El CNN aprendió a detectar convulsiones sobre este baseline de ~1000 milli-g.
     *     Darle TYPE_LINEAR_ACCELERATION (magnitud en reposo ≈ 0) sería input fuera de
     *     la distribución de entrenamiento. Ver DEC-023.
     *
     * Analogía Python:
     *   TYPE_ACCELEROMETER = data_raw (con gravedad incluida — ~1000 milli-g en reposo)
     *   TYPE_LINEAR_ACCELERATION = data_raw - gravity_vector (en reposo ≈ 0 milli-g)
     *
     * Se inicializa como null y se asigna en startSensorCollection().
     * El nullable (SensorManager?) protege contra el caso donde stopSensorCollection()
     * se llame antes de que el monitoreo comenzara.
     */
    private var sensorManager: SensorManager? = null

    /**
     * Referencia al sensor de aceleración cruda (con gravedad) del reloj.
     * Null si el hardware no lo soporta (ver manejo en startSensorCollection).
     */
    private var accelerometerSensor: Sensor? = null

    /**
     * Listener que recibe las muestras del acelerómetro.
     *
     * Por qué `object : SensorEventListener` (clase anónima) y no implementar
     * SensorEventListener en el Service directamente:
     *   Mantiene el código más limpio — el listener es un componente separado
     *   conceptualmente. El Service no "es" un listener; tiene uno.
     *   (Principio de composición sobre herencia.)
     *
     * Por qué verificar `event.sensor.type`:
     *   Si en el futuro se registran múltiples sensores, este callback puede recibir
     *   eventos de cualquiera de ellos. La verificación asegura que solo procesamos
     *   muestras del sensor correcto.
     */
    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                onAccelerometerSample(x, y, z)
            }
        }

        /**
         * Llamado cuando cambia la precisión del sensor (calibración).
         * No usamos la precisión en el pipeline actual — es suficiente con los valores.
         * En Fase 2 se podría loggear si la precisión baja a SENSOR_STATUS_UNRELIABLE.
         */
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            // no-op por ahora — se evalúa en Fase 2 si la precisión afecta la inferencia
        }
    }

    /**
     * Fase 1.2 — WakeLock de CPU para monitoreo nocturno.
     *
     * Un WakeLock es un "candado" que le dice al OS que NO apague la CPU del reloj
     * mientras el WakeLock esté activo. Sin esto, el CPU del Galaxy Watch 8 entra
     * en modo de suspensión a los pocos minutos de que la pantalla se apague —
     * deteniendo la captura del acelerómetro y la inferencia TFLite silenciosamente.
     *
     * Analogía Python:
     *   Es como un context manager que mantiene un proceso activo:
     *
     *   with keep_awake_context():   # acquire WakeLock
     *       run_overnight_monitor()
     *   # release WakeLock          # onDestroy()
     *
     * Se inicializa como null y se asigna en acquireWakeLock().
     * El tipo nullable (PowerManager.WakeLock?) protege contra el caso donde
     * onDestroy() se llama antes de que el monitoreo comenzara.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> onMonitoringStart()
            ACTION_STOP  -> {
                // Desregistrar el sensor ANTES de stopSelf() para evitar que el
                // callback siga llegando durante el shutdown del Service.
                stopSensorCollection()
                // Fase 1.6: cerrar el CSV logger antes de que el Service muera.
                // flush() garantiza que los últimos datos llegan al disco.
                csvLogger.close()
                // Limpiar estado de transporte: el próximo monitoreo empieza desde cero.
                // Sin esto, las primeras ventanas del siguiente ciclo mezclarían
                // datos del monitoreo anterior con datos nuevos.
                accelerometerBuffer.reset()
                samplesSinceLastChunk = 0
                sequentialSampleCounter = 0L
                stopSelf()
            }
        }
        // START_STICKY: si el OS mata el service, lo reinicia con intent=null
        return START_STICKY
    }

    override fun onDestroy() {
        // Orden de limpieza: sensor → csvLogger → WakeLock → coroutines
        //
        // Por qué este orden:
        //   1. stopSensorCollection() primero: el callback onSensorChanged() puede
        //      llegar en cualquier thread del OS (el SensorManager usa threads internos).
        //      Si el sensor sigue activo mientras cancelamos serviceScope, un callback
        //      tardío podría intentar lanzar una coroutine en un scope cancelado →
        //      IllegalStateException. Desregistrar primero corta el flujo de datos.
        //
        //   2. csvLogger.close() segundo: flush + cierre del archivo CSV.
        //      DEBE ir antes de releaseWakeLock() porque BufferedWriter puede tener
        //      datos en su buffer interno que todavía no se escribieron a disco.
        //      Si el WakeLock se libera antes del flush, el CPU puede entrar en
        //      suspensión a mitad del volcado → archivo CSV incompleto o corrupto.
        //      Ver DEC-032 en DECISIONS.md.
        //
        //   3. releaseWakeLock() tercero: una vez que el sensor está parado y los datos
        //      CSV están en disco, ya no hay trabajo que proteger. Liberamos la CPU.
        //
        //   4. serviceScope.cancel() cuarto: cancela todas las coroutines hijas. Al llegar
        //      acá, el sensor ya no envía datos y el WakeLock ya se liberó. Es seguro.
        //
        //   5. super.onDestroy() al final: convención de Android — super siempre último
        //      en onDestroy() (a diferencia de onCreate() donde va primero).
        //
        // Nota Fase 2.1: el alarmStateListener va antes de stopSensorCollection() porque
        // podría llegar un mensaje del teléfono exactamente en el momento del shutdown.
        // Removerlo primero evita que el callback intente usar un Service ya destruido.
        alarmStateListener?.let { dataLayerManager.removeAlarmStateListener(it) }
        stopSensorCollection()
        csvLogger.close()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // No es un Bound Service — no hay clientes que se "conecten" a él.
    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Lógica de monitoreo ──────────────────────────────────────────────────

    /**
     * Punto de entrada del monitoreo activo.
     * En Fase 1.2: adquiere el WakeLock para mantener el CPU despierto toda la noche.
     * En Fase 1.3: inicia la captura del acelerómetro a 25Hz.
     * En Fase 2.1: aquí se carga el Interpreter TFLite y el pipeline de inferencia.
     */
    private fun onMonitoringStart() {
        acquireWakeLock()
        startSensorCollection()
        // Fase 1.6: iniciar logging a CSV en builds de debug
        if (isLoggingEnabled) {
            val path = csvLogger.open()
            Log.i(TAG, "CSV logging activo: $path")
        }
        // Fase 2.1: registrar listener de alarmState del teléfono
        alarmStateListener = dataLayerManager.addAlarmStateListener { alarmState ->
            onAlarmStateReceived(alarmState)
        }
    }

    // ─── SensorManager ────────────────────────────────────────────────────────

    /**
     * Registra el listener del acelerómetro en el SensorManager del sistema.
     *
     * Por qué `SENSOR_SAMPLING_PERIOD_US` en lugar de las constantes predefinidas
     * de Android (`SENSOR_DELAY_NORMAL`, `SENSOR_DELAY_GAME`, etc.):
     *   Las constantes predefinidas son hints genéricos que no garantizan frecuencias exactas:
     *   - SENSOR_DELAY_NORMAL  ≈ 200ms = 5Hz
     *   - SENSOR_DELAY_UI      ≈ 67ms  = 15Hz
     *   - SENSOR_DELAY_GAME    ≈ 20ms  = 50Hz
     *   - SENSOR_DELAY_FASTEST ≈ 0ms   (máximo del hardware, varía por dispositivo)
     *
     *   El CNN requiere exactamente 25Hz (750 samples en 30 segundos de ventana).
     *   Ninguna constante predefinida da 25Hz. Por eso usamos el valor numérico:
     *   40,000 microsegundos = 40ms = 25 muestras por segundo.
     *
     *   IMPORTANTE: Android trata este valor como un "hint" — el OS puede entregar
     *   muestras a una frecuencia levemente distinta según la carga del sistema.
     *   En Fase 1.6 (logging CSV) se medirá la frecuencia real para verificar que
     *   estamos efectivamente cerca de 25Hz antes de conectar el CNN.
     *
     * Manejo del caso donde TYPE_ACCELEROMETER no está disponible:
     *   Aunque es extremadamente raro (todo hardware con Wear OS lo soporta), algunos
     *   emuladores Robolectric pueden no tenerlo. En ese caso logueamos una advertencia
     *   y retornamos sin registrar. En Fase 1.4 se evalúa el Samsung Health SDK como
     *   alternativa con mejor acceso a sensores.
     *
     * @return true si el listener se registró exitosamente, false si no hay sensor disponible.
     */
    private fun startSensorCollection() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometerSensor == null) {
            // Fallback documentado: en Fase 1.4 se evalúa Samsung Health SDK
            Log.w(TAG, "TYPE_ACCELEROMETER no disponible en este dispositivo. " +
                    "El monitoreo de sensores no está activo. Ver DEC-023 y Fase 1.4.")
            return
        }

        val registered = sensorManager?.registerListener(
            sensorEventListener,
            accelerometerSensor,
            SENSOR_SAMPLING_PERIOD_US
        ) ?: false

        if (!registered) {
            Log.e(TAG, "SensorManager.registerListener() retornó false — " +
                    "el listener no fue registrado. Verificar permisos BODY_SENSORS.")
        } else {
            Log.d(TAG, "Acelerómetro registrado a ${1_000_000 / SENSOR_SAMPLING_PERIOD_US}Hz " +
                    "(período hint: ${SENSOR_SAMPLING_PERIOD_US}µs)")
        }
    }

    /**
     * Desregistra el listener del acelerómetro y limpia las referencias.
     *
     * Llamado desde dos lugares:
     *   1. `onStartCommand(ACTION_STOP)` — el usuario detiene el monitoreo.
     *   2. `onDestroy()` — el Service se está destruyendo (limpieza de emergencia).
     *
     * Es seguro llamar este método múltiples veces (idempotente):
     *   - Si `sensorManager` es null, el operador `?.` hace que `unregisterListener`
     *     no se llame, sin NullPointerException.
     *   - Después de llamarlo, sensorManager = null garantiza que llamadas
     *     subsecuentes no hacen nada.
     *
     * Por qué poner sensorManager = null después de unregister:
     *   Permite al GC liberar la referencia al SensorManager del sistema.
     *   Y convierte este método en idempotente — se puede llamar dos veces sin riesgo.
     */
    private fun stopSensorCollection() {
        sensorManager?.unregisterListener(sensorEventListener)
        sensorManager = null
        accelerometerSensor = null
    }

    /**
     * Procesa cada muestra del acelerómetro (x, y, z) en m/s² con gravedad incluida.
     *
     * Calcula la magnitud vectorial en milli-g y la agrega al ring buffer.
     * Cada [TRANSPORT_CHUNK_SIZE] muestras (~5 segundos a 25Hz), dispara onWindowReady().
     *
     * Los valores x, y, z vienen en m/s² de TYPE_ACCELEROMETER (con gravedad).
     * La magnitud se convierte a milli-g antes de guardarse en el buffer, que es
     * la unidad esperada por el modelo DeepEpiCnn Run24. En reposo: ~1000 milli-g.
     *
     * Por qué calcular la magnitud aquí y no dentro de CircularBuffer:
     *   El buffer solo sabe de FloatArray — es una estructura de datos genérica.
     *   El cálculo de magnitud es conocimiento del dominio ML (preprocesamiento del CNN).
     *   Mezclarlos violaría la separación de responsabilidades: el buffer no debería
     *   saber nada sobre acelerómetros ni sobre el modelo.
     *
     * Analogía Python:
     *   magnitude_milli_g = np.sqrt(x**2 + y**2 + z**2) * (1000.0 / 9.81)
     *   ring_buffer.append(magnitude_milli_g)
     *
     * @param x Aceleración en el eje X (m/s²), con gravedad incluida.
     * @param y Aceleración en el eje Y (m/s²), con gravedad incluida.
     * @param z Aceleración en el eje Z (m/s²), con gravedad incluida.
     */
    private fun onAccelerometerSample(x: Float, y: Float, z: Float) {
        // Conversión m/s² → milli-g: 1g = 9.81 m/s² = 1000 milli-g
        val magnitudeMilliG = sqrt(x * x + y * y + z * z) * MS2_TO_MILLIG
        accelerometerBuffer.add(magnitudeMilliG)
        samplesSinceLastChunk++

        // Fase 1.6: loggear muestra a CSV para análisis offline con Python.
        // En release, isLoggingEnabled = false → este bloque no ejecuta.
        if (isLoggingEnabled) {
            csvLogger.write(System.currentTimeMillis(), x, y, z, magnitudeMilliG)
        }

        if (samplesSinceLastChunk >= TRANSPORT_CHUNK_SIZE && accelerometerBuffer.isFull) {
            val window = accelerometerBuffer.snapshot()
            onWindowReady(window)
            samplesSinceLastChunk = 0
        }
    }

    /**
     * Recibe un chunk y lo envía al teléfono via Wear Data Layer.
     *
     * En modo debug con [isSequentialMode] = true (paso 1 del protocolo de validación de
     * Graham Jones): envía números secuenciales continuos entre chunks en lugar de datos
     * reales para verificar orden end-to-end multi-chunk.
     *
     * En modo normal: envía las magnitudes reales del acelerómetro.
     *
     * La coroutine corre en serviceScope (Dispatchers.Default) — la llamada a
     * sendAccelData() suspende en el thread del pool, sin bloquear el callback del sensor.
     */
    private fun onWindowReady(window: FloatArray) {
        Log.d(TAG, "Chunk listo: ${window.size} muestras, magnitud media=${window.average()}")
        serviceScope.launch {
            val dataToSend = if (BuildConfig.DEBUG && isSequentialMode) {
                // Modo validación (Graham's protocol step 1):
                // Numeración continua entre chunks para validar orden global.
                FloatArray(window.size) { i -> (sequentialSampleCounter + i + 1).toFloat() }
            } else {
                window
            }
            dataLayerManager.sendAccelData(dataToSend)
            if (BuildConfig.DEBUG && isSequentialMode) {
                sequentialSampleCounter += window.size.toLong()
            }
        }
    }

    /**
     * Procesa el alarmState recibido del teléfono via /osd/alarm_state.
     *
     * @param alarmState Valor 0-7 según la especificación OSD:
     *   0 = OK, 1 = WARNING, 2 = ALARM, 3 = SEIZURE_DETECTED, ...
     *
     * En Fase 2.2: actualizar UI y disparar vibración según el estado.
     */
    private fun onAlarmStateReceived(alarmState: Int) {
        Log.i(TAG, "alarmState recibido: $alarmState")
        alarmStateManager.handleAlarmState(alarmState)
        _alarmState.value = alarmState
    }

    // ─── WakeLock ─────────────────────────────────────────────────────────────

    /**
     * Adquiere un PARTIAL_WAKE_LOCK con timeout de seguridad de 10 horas.
     *
     * Por qué PARTIAL_WAKE_LOCK (no FULL_WAKE_LOCK):
     *   PARTIAL → mantiene la CPU activa, permite que la pantalla duerma.
     *   FULL    → mantiene pantalla + CPU encendidos → destruye la batería en minutos.
     *   Para monitoreo nocturno, la pantalla DEBE apagarse. Solo necesitamos la CPU.
     *
     * Por qué timeout de 10 horas (no acquire() indefinido):
     *   Si el OS mata el Service sin llamar onDestroy() (raro pero posible en memoria
     *   extrema), un WakeLock sin timeout permanece activo PARA SIEMPRE — el reloj
     *   nunca duerme, la batería muere en horas. El timeout de 10h actúa como red de
     *   seguridad: 10h > 8h de sueño nocturno → en uso normal nunca expira por timeout.
     *
     * Por qué el tag "SeizureGuard::MonitoringWakeLock":
     *   El formato "AppName::Reason" es recomendado por Android para poder identificar
     *   WakeLocks en herramientas de diagnóstico (Battery Historian, `dumpsys power`).
     *
     * Analogía Python:
     *   Esto es como adquirir un threading.Lock() con timeout:
     *   wakelock.acquire(timeout=10*60*60)  # el OS lo libera automáticamente si olvidamos
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    /**
     * Libera el WakeLock si está activo.
     *
     * La verificación `it.isHeld` es obligatoria — llamar `release()` sobre un
     * WakeLock que ya fue liberado lanza `RuntimeException: WakeLock under-locked`.
     * Esto podría pasar si el timeout de 10h expira antes de que el Service se destruya.
     *
     * Analogía Python:
     *   lock = threading.Lock()
     *   if lock.locked():
     *       lock.release()
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // ─── Notificación ─────────────────────────────────────────────────────────

    /**
     * Crea el canal de notificación (obligatorio en Android 8+ / API 26+).
     * En Wear OS, IMPORTANCE_LOW es el nivel correcto para notificaciones persistentes
     * de servicio — no hace sonido ni interrumpe, pero es visible en el centro de notif.
     *
     * El canal se crea una sola vez; llamarlo de nuevo con el mismo CHANNEL_ID no hace nada.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * Construye la notificación persistente del monitoreo.
     * setOngoing(true): el usuario NO puede deslizarla para cerrarla.
     * setCategory(CATEGORY_SERVICE): el OS la trata como notificación de servicio activo.
     * El tap en la notificación abre la MainActivity.
     */
    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_monitoring_title))
        .setContentText(getString(R.string.notification_monitoring_text))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    // ─── Constantes ───────────────────────────────────────────────────────────

    companion object {
        const val CHANNEL_ID      = "seizure_monitor_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START    = "com.seizureguard.wear.START_MONITORING"
        const val ACTION_STOP     = "com.seizureguard.wear.STOP_MONITORING"

        /**
         * Fase 2.2 — Estado actual del monitoreo observable desde la UI.
         * La MainActivity colecta este flow para actualizar la pantalla.
         *
         * Por qué StateFlow en el companion object:
         *   El Service y la Activity viven en el mismo proceso — compartir estado
         *   via companion object es la forma más simple sin introducir un ViewModel
         *   o un patrón de binding complejo en esta fase.
         *   En Fase 3+ se puede migrar a un ViewModel compartido.
         *
         * Valores posibles: ALARM_OK (0), ALARM_WARNING (1), ALARM_ALARM (2+)
         */
        private val _alarmState = MutableStateFlow(AlarmStateManager.ALARM_OK)
        val alarmState: StateFlow<Int> = _alarmState.asStateFlow()

        /**
         * Capacidad del ring buffer = número de muestras por chunk de transporte.
         *
         * 125 muestras × (1/25Hz) = 5 segundos por mensaje.
         * Se mantiene separado del tamaño de ventana del modelo (750), que hoy vive en phone.
         *
         * Analogía Python:
         *   CHUNK_SIZE = 125
         *   buffer = deque(maxlen=CHUNK_SIZE)
         */
        const val TRANSPORT_CHUNK_SIZE = 125
        const val BUFFER_CAPACITY = TRANSPORT_CHUNK_SIZE
        const val MODEL_WINDOW_SIZE = 750

        /**
         * Factor de conversión de m/s² a milli-g.
         *
         * 1g = 9.81 m/s² = 1000 milli-g (por definición del sistema SI).
         * El modelo DeepEpiCnn Run24 fue entrenado con magnitudes en milli-g.
         * Con el reloj en reposo, la magnitud esperada es ~1000 milli-g (1g de gravedad).
         */
        private const val MS2_TO_MILLIG = 1000f / 9.81f

        /**
         * Tag de logging — formato "ClassName" estándar de Android.
         * Visible en `adb logcat -s SeizureMonitorService:V` para ver todos los logs
         * del Service, incluyendo los logs verbose de las muestras del acelerómetro.
         */
        const val TAG = "SeizureMonitorService"

        /**
         * Período de muestreo del acelerómetro en microsegundos.
         *
         * 25Hz = 25 muestras por segundo = 1 muestra cada 40ms = 40,000 microsegundos.
         *
         * Por qué 25Hz:
         *   - El modelo DeepEpiCnn Run24 fue entrenado con ventanas de 750 muestras a 25Hz = 30 segundos.
         *   - Darle datos a 50Hz sin reentrenar el modelo rompería el contrato de input shape.
         *   - Darle datos a 10Hz perdería resolución temporal de las convulsiones tónico-clónicas
         *     (frecuencia característica: 1-3Hz de movimientos rítmicos, necesitamos 25Hz para
         *     capturar correctamente según el criterio de Nyquist: 25Hz > 2 × 3Hz = 6Hz).
         *
         * Por qué microsegundos y no Hz:
         *   La API de SensorManager acepta el período en microsegundos (un "delay" entre muestras),
         *   no una frecuencia en Hz. La conversión es: period_us = 1_000_000 / frequency_hz.
         *
         * Por qué no usar `SensorManager.SENSOR_DELAY_GAME` (≈20ms = 50Hz):
         *   Ninguna constante predefinida da exactamente 40ms. Ver DEC-024.
         */
        const val SENSOR_SAMPLING_PERIOD_US = 40_000  // 40ms = 25Hz

        /**
         * Tag del WakeLock — formato "AppName::Reason" requerido por Android.
         * Visible en `adb shell dumpsys power` para diagnosticar problemas de batería.
         */
        const val WAKE_LOCK_TAG        = "SeizureGuard::MonitoringWakeLock"

        /**
         * Timeout de seguridad: 10 horas en milisegundos.
         * Red de seguridad contra WakeLock leak si onDestroy() nunca se llama.
         * 10h > 8h de sueño nocturno → en uso normal, releaseWakeLock() siempre
         * se llama en onDestroy() antes de que el timeout expire.
         */
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L

        /**
         * Fase 2.1 — Modo de validación del transporte (protocolo de Graham Jones).
         *
         * Cuando es true, onWindowReady() envía números secuenciales continuos
         * entre chunks en lugar de datos reales. Permite verificar que el teléfono
         * recibe los floats en el orden correcto antes de conectar datos reales.
         *
         * Paso 1 (isSequentialMode = true): verificar orden de llegada.
         * Paso 2 (isSequentialMode = false): reloj quieto → ~1000 milli-g en un eje.
         *
         * Cambiar a false cuando el transporte esté verificado end-to-end.
         */
        var isSequentialMode: Boolean = BuildConfig.DEBUG

        /** Crea el Intent para iniciar el monitoreo desde cualquier parte de la app. */
        fun startIntent(context: android.content.Context) =
            Intent(context, SeizureMonitorService::class.java).apply {
                action = ACTION_START
            }

        /** Crea el Intent para detener el monitoreo. */
        fun stopIntent(context: android.content.Context) =
            Intent(context, SeizureMonitorService::class.java).apply {
                action = ACTION_STOP
            }
    }
}
