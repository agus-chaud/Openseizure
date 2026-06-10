package com.seizureguard.wear.service

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSensor

/**
 * Fase 1.1 — Tests del ForegroundService (Robolectric, sin dispositivo).
 *
 * Qué verifican estos tests:
 *
 *   1. El Service arranca sin crash — onCreate() y startForeground() funcionan.
 *   2. La notificación persistente se registra en el NotificationManager.
 *   3. onBind() devuelve null — es un started service, no bound.
 *   4. ACTION_STOP llama stopSelf() — el Service puede detenerse.
 *
 * Qué NO verifican estos tests (y por qué):
 *
 *   - Que el Service sobrevive 8 horas → requiere test de integración en dispositivo real.
 *   - Que el WakeLock funciona → Robolectric mockea PowerManager; se verifica en Fase 1.2.
 *   - Que la notificación se muestra visualmente → requiere emulador o watch real.
 *
 * Por qué Robolectric y no tests instrumented aquí:
 *   Los tests instrumented requieren el watch físico conectado por ADB.
 *   Para verificar el lifecycle básico del Service (onCreate, onStartCommand),
 *   Robolectric es suficiente y más rápido (corre en la JVM, sin watch).
 *
 * Analogía Python:
 *   `Robolectric.buildService(SeizureMonitorService::class.java)` es como
 *   usar `unittest.mock.patch` para instanciar una clase sin su infraestructura
 *   real — el OS Android está simulado en memoria.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SeizureMonitorServiceTest {

    /**
     * Verifica que el Service se puede crear sin crash.
     * Robolectric instancia el Service y llama onCreate() automáticamente.
     */
    @Test
    fun service_onCreate_doesNotCrash() {
        val controller = Robolectric.buildService(SeizureMonitorService::class.java)
            .create()

        assertNotNull("El service debe instanciarse correctamente", controller.get())
    }

    /**
     * Verifica que la notificación persistente se registra al arrancar el Service.
     * NotificationManager es simulado por Robolectric — podemos consultar sus notificaciones.
     */
    @Test
    fun service_onCreate_registersNotification() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        Robolectric.buildService(SeizureMonitorService::class.java)
            .create()
            .startCommand(0, 1)

        val notificationManager =
            context.getSystemService(NotificationManager::class.java)

        val activeNotifications = notificationManager.activeNotifications
        assertTrue(
            "Debe haber al menos una notificación activa (la del monitoreo)",
            activeNotifications.isNotEmpty()
        )
    }

    /**
     * Verifica que onBind() devuelve null — este es un started service, no bound.
     * Un bound service expondría una interfaz AIDL; este no lo necesita.
     */
    @Test
    fun service_onBind_returnsNull() {
        val service = Robolectric.buildService(SeizureMonitorService::class.java)
            .create()
            .get()

        val binder = service.onBind(Intent())
        assertTrue("onBind debe devolver null para un started service", binder == null)
    }

    /**
     * Verifica que ACTION_START no lanza excepciones.
     * En Fase 1.1 onMonitoringStart() está vacío — el test es un smoke test.
     * En Fase 1.3 se agrega lógica de sensores y este test se expande.
     */
    @Test
    fun service_actionStart_doesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val startIntent = SeizureMonitorService.startIntent(context)

        Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)
            .get()
        // Si llega acá sin excepción, el test pasa
    }

    /**
     * Verifica que startIntent() y stopIntent() producen Intents con las acciones correctas.
     * Son factory methods del companion object — testeamos que el contrato no cambia.
     */
    @Test
    fun serviceCompanion_startIntent_hasCorrectAction() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val intent = SeizureMonitorService.startIntent(context)

        assertTrue(
            "startIntent debe tener ACTION_START",
            intent.action == SeizureMonitorService.ACTION_START
        )
    }

    @Test
    fun serviceCompanion_stopIntent_hasCorrectAction() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val intent = SeizureMonitorService.stopIntent(context)

        assertTrue(
            "stopIntent debe tener ACTION_STOP",
            intent.action == SeizureMonitorService.ACTION_STOP
        )
    }

    // ─── Tests de Fase 1.2: WakeLock ─────────────────────────────────────────

    /**
     * Verifica que el WakeLock está activo (isHeld = true) después de ACTION_START.
     *
     * Qué testea: que onMonitoringStart() → acquireWakeLock() realmente adquiere el lock.
     *
     * Cómo funciona en Robolectric:
     *   ShadowPowerManager simula el PowerManager del OS. Cuando el Service llama
     *   powerManager.newWakeLock(...).acquire(...), el shadow lo registra en memoria.
     *   `ShadowPowerManager.getLatestWakeLock()` (estático en 4.12.2) devuelve el último
     *   WakeLock creado en el proceso.
     *
     * Analogía Python:
     *   Es como verificar que threading.Lock() está locked() después de acquire().
     */
    @Test
    fun wakeLock_afterActionStart_isHeld() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startIntent = SeizureMonitorService.startIntent(context)

        Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        val latestWakeLock = ShadowPowerManager.getLatestWakeLock()

        assertNotNull("Debe haberse creado un WakeLock al iniciar el monitoreo", latestWakeLock)
        assertTrue(
            "El WakeLock debe estar activo (isHeld=true) mientras el monitoreo corre",
            latestWakeLock.isHeld
        )
    }

    /**
     * Verifica que el WakeLock se libera (isHeld = false) después de onDestroy().
     *
     * Qué testea: que releaseWakeLock() en onDestroy() efectivamente suelta el lock.
     * Esto evita el power leak donde el reloj nunca duerme después de que el Service muere.
     *
     * Por qué es importante testearlo separado de la adquisición:
     *   El bug clásico es adquirir correctamente pero olvidar liberar. Este test fuerza
     *   el camino completo: acquire → release.
     */
    @Test
    fun wakeLock_afterOnDestroy_isReleased() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startIntent = SeizureMonitorService.startIntent(context)

        val controller = Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        // Verificamos que estaba held antes del destroy
        assertTrue(
            "Precondición: el WakeLock debe estar held antes del destroy",
            ShadowPowerManager.getLatestWakeLock().isHeld
        )

        // Destruimos el Service
        controller.destroy()

        // Ahora debe estar liberado
        assertTrue(
            "El WakeLock debe liberarse (isHeld=false) cuando el Service se destruye",
            !ShadowPowerManager.getLatestWakeLock().isHeld
        )
    }

    /**
     * Verifica que el WakeLock se adquiere con nivel PARTIAL_WAKE_LOCK.
     *
     * Qué testea: que acquireWakeLock() usa el nivel correcto.
     * PARTIAL_WAKE_LOCK = solo CPU activa, pantalla puede dormir.
     * Un error aquí (usar FULL_WAKE_LOCK) sería difícil de detectar en runtime
     * pero destruiría la batería del reloj en horas.
     *
     * Por qué un test de contrato sobre la constante y no una verificación en runtime:
     *   Robolectric 4.12.2 NO preserva el nivel del WakeLock. ShadowWakeLock no expone los
     *   flags, y el WakeLock real se instancia sin correr su constructor → mFlags queda en 0
     *   (verificado empíricamente). No hay forma de leer el nivel del lock ya creado.
     *   Por eso el nivel se extrae a la constante SeizureMonitorService.WAKE_LOCK_LEVEL, que
     *   acquireWakeLock() pasa a newWakeLock(), y este test blinda esa constante contra
     *   regresiones a FULL_WAKE_LOCK. Mismo patrón que los tests de WAKE_LOCK_TAG y
     *   SENSOR_SAMPLING_PERIOD_US de este archivo.
     */
    @Test
    fun wakeLock_isPartialWakeLock() {
        assertEquals(
            "WAKE_LOCK_LEVEL debe ser PARTIAL (CPU activa, pantalla puede dormir). " +
                "FULL_WAKE_LOCK destruiría la batería del reloj en una noche.",
            PowerManager.PARTIAL_WAKE_LOCK,
            SeizureMonitorService.WAKE_LOCK_LEVEL
        )
    }

    /**
     * Verifica que el tag del WakeLock es exactamente "SeizureGuard::MonitoringWakeLock".
     *
     * Qué testea: que el tag de diagnóstico tiene el formato correcto.
     * El tag aparece en `adb shell dumpsys power` — si es incorrecto o genérico,
     * es imposible identificar qué parte de la app está manteniendo la CPU despierta
     * cuando se diagnostican problemas de batería en el futuro.
     *
     * El formato "AppName::Reason" es la convención recomendada por Android.
     */
    @Test
    fun wakeLock_hasCorrectTag() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startIntent = SeizureMonitorService.startIntent(context)

        Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        val latestWakeLock = ShadowPowerManager.getLatestWakeLock()
        val shadowWakeLock = shadowOf(latestWakeLock)

        assertEquals(
            "El tag del WakeLock debe seguir el formato 'AppName::Reason' para diagnósticos",
            SeizureMonitorService.WAKE_LOCK_TAG,
            shadowWakeLock.tag
        )
    }

    // ─── Tests de Fase 1.3: SensorManager ────────────────────────────────────

    /**
     * Verifica que el SensorEventListener queda registrado en el SensorManager
     * después de ACTION_START.
     *
     * Qué testea (comportamiento): que cuando el usuario inicia el monitoreo,
     * el Service efectivamente se suscribe al acelerómetro del reloj.
     * Sin este registro, el Service correría sin recibir ningún dato de sensor.
     *
     * Cómo funciona ShadowSensorManager en Robolectric 4.12.2:
     *   Robolectric reemplaza el SensorManager real con un shadow (simulacro).
     *   `hasListener(SensorEventListener)` / `hasListener(listener, sensor)` y `getListeners()`
     *   son la API real — NO existe `hasListener(Int)`. Además, `getDefaultSensor(type)` solo
     *   devuelve un Sensor si fue agregado antes con `addSensor()`; por eso el test crea el
     *   acelerómetro con `ShadowSensor.newInstance()` y lo registra en el shadow.
     *
     * Analogía Python:
     *   Es como verificar que tu callback está en la lista de observadores de un evento:
     *   assert my_callback in event_emitter.listeners('sensor_data')
     */
    @Test
    fun sensorManager_afterActionStart_isRegistered() {
        // Arrange
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startIntent = SeizureMonitorService.startIntent(context)
        val shadowSensorManager = shadowOf(
            context.getSystemService(SensorManager::class.java)
        )
        // Sin un acelerómetro registrado, getDefaultSensor(TYPE_ACCELEROMETER) devuelve null
        // y el Service no registraría ningún listener.
        shadowSensorManager.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))

        // Act
        Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        // Assert
        assertTrue(
            "Después de ACTION_START, debe haber un listener registrado para " +
                    "TYPE_ACCELEROMETER — sin esto el Service no recibe datos del sensor",
            shadowSensorManager.listeners.isNotEmpty()
        )
    }

    /**
     * Verifica que el SensorEventListener se desregistra después de ACTION_STOP.
     *
     * Qué testea (comportamiento): que cuando el usuario para el monitoreo,
     * el Service deja de recibir datos del sensor. Si el listener quedara activo
     * después de detener el monitoreo, seguiría consumiendo batería y CPU del reloj.
     *
     * Por qué es importante testearlo separado del registro:
     *   El bug clásico es olvidar el unregister. Si solo testeas el registro,
     *   puedes tener un leak de sensor que no es visible hasta que el reloj
     *   muestra batería anormalmente baja a la mañana siguiente.
     */
    @Test
    fun sensorManager_afterActionStop_isUnregistered() {
        // Arrange
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startIntent = SeizureMonitorService.startIntent(context)
        val stopIntent  = SeizureMonitorService.stopIntent(context)
        val shadowSensorManager = shadowOf(
            context.getSystemService(SensorManager::class.java)
        )
        shadowSensorManager.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))

        val controller = Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        // Precondición: el listener está registrado antes del stop
        assertTrue(
            "Precondición: el listener debe estar registrado antes de ACTION_STOP",
            shadowSensorManager.listeners.isNotEmpty()
        )

        // Act — enviar ACTION_STOP al mismo Service
        controller.get().onStartCommand(stopIntent, 0, 2)

        // Assert
        assertTrue(
            "Después de ACTION_STOP, el listener debe desregistrarse — " +
                    "el Service no debe seguir consumiendo datos del sensor",
            shadowSensorManager.listeners.isEmpty()
        )
    }

    /**
     * Verifica que el SensorEventListener se desregistra cuando el Service se destruye.
     *
     * Qué testea (comportamiento): que onDestroy() limpia correctamente el sensor,
     * independientemente de si ACTION_STOP fue enviado o no.
     * onDestroy() es el callback de "última oportunidad" del lifecycle de Android.
     *
     * Por qué testearlo separado de ACTION_STOP:
     *   Hay dos caminos para parar el monitoreo:
     *   1. El usuario toca "Parar" → ACTION_STOP → stopSelf() → onDestroy()
     *   2. El OS mata el Service por memoria extrema → onDestroy() directamente
     *   En el caso 2, ACTION_STOP nunca se envió. El unregister en onDestroy()
     *   es la red de seguridad para este camino.
     */
    @Test
    fun sensorManager_afterOnDestroy_isUnregistered() {
        // Arrange
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startIntent = SeizureMonitorService.startIntent(context)
        val shadowSensorManager = shadowOf(
            context.getSystemService(SensorManager::class.java)
        )
        shadowSensorManager.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))

        val controller = Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        // Precondición: el listener está activo antes del destroy
        assertTrue(
            "Precondición: el listener debe estar registrado antes del destroy",
            shadowSensorManager.listeners.isNotEmpty()
        )

        // Act
        controller.destroy()

        // Assert
        assertTrue(
            "Después de onDestroy(), el listener debe desregistrarse — " +
                    "el cleanup de onDestroy() es la red de seguridad ante kills del OS",
            shadowSensorManager.listeners.isEmpty()
        )
    }

    /**
     * Verifica que la constante SENSOR_SAMPLING_PERIOD_US es exactamente 40,000 microsegundos.
     *
     * Qué testea (contrato de pipeline): la constante documenta el contrato de frecuencia
     * del sensor. 40,000 µs = 40ms = 25Hz = 125 muestras cada ~5s (chunk watch) y
     * 750 muestras en 30s (ventana de modelo en phone). Si alguien cambia este valor,
     * se rompe la correspondencia temporal del pipeline.
     *
     * Por qué testear una constante:
     *   Las constantes "obvias" son las que más se cambian por error en refactors.
     *   Este test sirve de documentación ejecutable: "este valor NO es arbitrario,
     *   existe por el contrato con el modelo DeepEpiCnn Run24".
     *
     * Analogía Python:
     *   assert SAMPLE_RATE_HZ == 25, "CNN was trained on 25Hz data — do not change"
     *   assert CHUNK_SIZE == 125,    "watch transport expects ~5s chunks at 25Hz"
     *   assert WINDOW_SIZE == 750,   "phone model expects 30s at 25Hz"
     */
    @Test
    fun sensorManager_samplingPeriod_is25Hz() {
        // 25Hz = 1/25 segundos = 40,000 microsegundos
        // Este valor define el contrato temporal del pipeline watch → phone → modelo
        val expectedPeriodUs = 40_000

        assertEquals(
            "SENSOR_SAMPLING_PERIOD_US debe ser 40,000µs (40ms = 25Hz). " +
                    "El pipeline requiere 125 muestras cada ~5s en watch y 750 muestras cada 30s en phone. " +
                    "Cambiar este valor invalida la temporalidad esperada del modelo.",
            expectedPeriodUs,
            SeizureMonitorService.SENSOR_SAMPLING_PERIOD_US
        )
    }

    /**
     * Verifica que el Service usa TYPE_ACCELEROMETER (raw, con gravedad) y NO TYPE_LINEAR_ACCELERATION.
     *
     * Qué testea (corrección del pipeline ML): que el tipo de sensor registrado
     * es el correcto para el modelo DeepEpiCnn Run24. Este es el test más crítico de la Fase 1.3.
     *
     * Por qué TYPE_ACCELEROMETER y no TYPE_LINEAR_ACCELERATION:
     *   El modelo DeepEpiCnn Run24 fue entrenado con datos de Garmin y PineTime que reportan
     *   aceleración cruda con gravedad incluida (confirmado por Graham Jones, creador de
     *   OpenSeizureDetector). Con el reloj en reposo sobre la mesa, un eje debe mostrar
     *   ~1000 milli-g (= 1g de gravedad). Eso solo es posible con TYPE_ACCELEROMETER.
     *
     *   TYPE_LINEAR_ACCELERATION (sin gravedad) daría magnitud ≈ 0 milli-g en reposo —
     *   fuera de la distribución de entrenamiento del modelo, lo que invalidaría las
     *   predicciones. El CNN aprendió a detectar convulsiones sobre el baseline de ~1000 milli-g.
     *
     * Cómo verificarlo en Robolectric 4.12.2:
     *   `hasListener(listener, sensor)` chequea si ese listener está registrado contra ese
     *   Sensor específico (el shadow guarda un Multimap listener→sensores). Creamos ambos
     *   sensores con `ShadowSensor.newInstance()`, dejamos que el Service registre el suyo,
     *   y verificamos el par correcto (acelerómetro) Y la ausencia del incorrecto (lineal).
     */
    @Test
    fun sensorManager_usesRawAccelerometer_notLinearAcceleration() {
        // Arrange
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startIntent = SeizureMonitorService.startIntent(context)
        val shadowSensorManager = shadowOf(
            context.getSystemService(SensorManager::class.java)
        )
        val accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        val linearAcceleration = ShadowSensor.newInstance(Sensor.TYPE_LINEAR_ACCELERATION)
        shadowSensorManager.addSensor(accelerometer)
        shadowSensorManager.addSensor(linearAcceleration)

        // Act
        Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        // El Service registró exactamente un listener — lo recuperamos para chequear contra qué
        // Sensor concreto quedó suscrito.
        val listener = shadowSensorManager.listeners.single()

        // Assert — el sensor correcto está registrado
        assertTrue(
            "El Service debe registrar TYPE_ACCELEROMETER (con gravedad). " +
                    "El modelo DeepEpiCnn Run24 fue entrenado con aceleración cruda — magnitud ~1000 milli-g en reposo.",
            shadowSensorManager.hasListener(listener, accelerometer)
        )

        // Assert — el sensor incorrecto NO está registrado
        assertFalse(
            "El Service NO debe registrar TYPE_LINEAR_ACCELERATION (sin gravedad). " +
                    "Daría magnitud ≈ 0 milli-g en reposo, fuera del dominio de entrenamiento del modelo.",
            shadowSensorManager.hasListener(listener, linearAcceleration)
        )
    }

    // ─── Tests de contrato — Milestone 0 / T1 ────────────────────────────────
    //
    // Estos tests describen el comportamiento CORRECTO que el Service todavía NO
    // tiene. Están marcados @Ignore porque HOY fallan (rojos) — son la red de
    // seguridad para refactorizar el camino crítico sin romperlo.
    //
    //   - Los tests de C1 se vuelven verdes al cerrar T4 (manejar restart con
    //     intent=null sin quedar en estado zombie).
    //   - El test de H1 se vuelve verde al cerrar T5 (onMonitoringStart idempotente).
    //
    // CUANDO SE CIERREN T4/T5: quitar el @Ignore de cada test y verificar que pasa.
    // Si pasa, el fix funciona. Si falla, el fix está incompleto. Es así de fácil.

    /**
     * C1 (Critical) — Restart por START_STICKY no debe dejar un servicio zombie.
     *
     * Escenario real:
     *   El OS mata el Service por presión de memoria → onDestroy() libera WakeLock y
     *   desregistra el sensor. Como onStartCommand devuelve START_STICKY, el OS recrea
     *   el Service y llama onCreate() (que hace startForeground → notificación visible)
     *   y luego onStartCommand(intent=null, ...).
     *
     * Bug actual:
     *   onStartCommand() hace `when (intent?.action)` — con intent=null ninguna rama
     *   matchea, así que NO se readquiere el WakeLock ni se re-registra el sensor.
     *   Resultado: notificación persistente diciendo "monitoreando" mientras NO se
     *   captura ni una sola muestra. El paciente cree que está protegido y no lo está.
     *   Este es el peor estado posible en software de seguridad de vida.
     *
     * Comportamiento esperado (post-T4):
     *   Si el reloj estaba monitoreando antes del kill, el restart con intent=null
     *   reanuda el monitoreo: WakeLock held + sensor registrado de nuevo.
     */
    @Test
    fun service_restartWithNullIntent_whileWasMonitoring_resumesMonitoring() {
        // Arrange — primer ciclo de monitoreo normal
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startIntent = SeizureMonitorService.startIntent(context)
        val shadowSensorManager = shadowOf(
            context.getSystemService(SensorManager::class.java)
        )
        shadowSensorManager.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))

        // El usuario inició el monitoreo y el Service corrió normalmente…
        val firstRun = Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)
        // …y el OS lo mató por memoria (onDestroy libera todo).
        firstRun.destroy()

        // Act — el OS recrea el Service (START_STICKY) y entrega intent=null
        val restarted = Robolectric.buildService(SeizureMonitorService::class.java)
            .create()
            .get()
        restarted.onStartCommand(null, 0, 2)

        // Assert — el monitoreo debe haberse reanudado, no quedar zombie
        assertTrue(
            "Tras un restart con intent=null estando monitoreando, el sensor debe " +
                    "re-registrarse — sin esto la notificación miente y no se captura nada.",
            shadowSensorManager.listeners.isNotEmpty()
        )
        assertNotNull(
            "Tras el restart debe readquirirse un WakeLock para sostener la CPU.",
            ShadowPowerManager.getLatestWakeLock()
        )
        assertTrue(
            "El WakeLock readquirido debe estar held.",
            ShadowPowerManager.getLatestWakeLock().isHeld
        )
    }

    /**
     * C1 (Critical) — Restart sin haber estado monitoreando debe apagarse, no quedar zombie.
     *
     * Escenario:
     *   El OS recrea el Service con intent=null pero el usuario NUNCA inició el monitoreo
     *   (o lo había detenido antes del kill). No hay nada que reanudar.
     *
     * Comportamiento esperado (post-T4):
     *   El Service llama stopSelf() y no adquiere WakeLock. No tiene sentido mantener vivo
     *   un foreground service que no monitorea — solo gastaría batería y mostraría una
     *   notificación falsa.
     */
    @Test
    fun service_restartWithNullIntent_whenNotMonitoring_stopsSelf() {
        // Arrange — Service recién creado, nunca recibió ACTION_START
        val service = Robolectric.buildService(SeizureMonitorService::class.java)
            .create()
            .get()

        // Act — restart del OS con intent=null sin monitoreo previo
        service.onStartCommand(null, 0, 1)

        // Assert — debe apagarse solo, sin sostener la CPU
        assertTrue(
            "Sin monitoreo previo, un restart con intent=null debe llamar stopSelf() " +
                    "en lugar de quedar como foreground service zombie.",
            shadowOf(service).isStoppedBySelf
        )
    }

    /**
     * H1 (High) — onMonitoringStart() debe ser idempotente (no readquirir WakeLock).
     *
     * Bug actual:
     *   Dos ACTION_START seguidos (p. ej. el usuario toca "Iniciar" dos veces, o el OS
     *   reenvía el intent) ejecutan onMonitoringStart() dos veces. acquireWakeLock()
     *   reasigna el campo `wakeLock` a un WakeLock NUEVO y lo adquiere, dejando el
     *   primero held para siempre (power leak): onDestroy() solo libera el último.
     *   También duplica los listeners de MessageClient.
     *
     * Por qué este test mide el WakeLock y NO el listener del sensor:
     *   El sensor usa SIEMPRE el mismo objeto `sensorEventListener`, y tanto Android
     *   como ShadowSensorManager DEDUPLICAN el registro de un listener idéntico — así
     *   que contar listeners de sensor da 1 aunque el bug exista (falso verde). El leak
     *   real es el WakeLock, y ahí sí se ve: cada arranque crea una instancia distinta.
     *
     * Comportamiento esperado (post-T5):
     *   El segundo ACTION_START es un no-op → NO se crea un segundo WakeLock, así que
     *   getLatestWakeLock() devuelve la MISMA instancia antes y después del segundo start.
     */
    @Test
    @Ignore("H1: rojo verificado hoy (assert falla). Quitar @Ignore al cerrar T5. Ver audit/wear-2026-06.")
    fun service_doubleActionStart_doesNotAcquireSecondWakeLock() {
        // Arrange
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startIntent = SeizureMonitorService.startIntent(context)
        val shadowSensorManager = shadowOf(
            context.getSystemService(SensorManager::class.java)
        )
        shadowSensorManager.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))

        // Act — primer ACTION_START
        val controller = Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)
        val wakeLockAfterFirstStart = ShadowPowerManager.getLatestWakeLock()

        // …y un segundo ACTION_START sobre el MISMO Service
        controller.get().onStartCommand(startIntent, 0, 2)
        val wakeLockAfterSecondStart = ShadowPowerManager.getLatestWakeLock()

        // Assert — debe ser la MISMA instancia: el segundo start no adquirió nada nuevo.
        // Comparamos la identidad con === y asertamos sobre el booleano (no con assertSame
        // pasando los WakeLocks directo): WakeLock.toString() de Robolectric lanza NPE al
        // construir el mensaje de fallo, ocultando la causa real. Así el rojo queda limpio.
        assertTrue(
            "Un segundo ACTION_START no debe adquirir un nuevo WakeLock — debe ser no-op. " +
                    "Adquirir uno nuevo abandona el primero held para siempre (power leak).",
            wakeLockAfterFirstStart === wakeLockAfterSecondStart
        )
    }
}
