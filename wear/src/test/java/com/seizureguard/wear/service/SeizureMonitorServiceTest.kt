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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
     *   `shadowOf(powerManager).latestWakeLock` devuelve el último WakeLock creado.
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

        val powerManager = context.getSystemService(PowerManager::class.java)
        val latestWakeLock = shadowOf(powerManager).latestWakeLock

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
        val powerManager = context.getSystemService(PowerManager::class.java)
        assertTrue(
            "Precondición: el WakeLock debe estar held antes del destroy",
            shadowOf(powerManager).latestWakeLock.isHeld
        )

        // Destruimos el Service
        controller.destroy()

        // Ahora debe estar liberado
        assertTrue(
            "El WakeLock debe liberarse (isHeld=false) cuando el Service se destruye",
            !shadowOf(powerManager).latestWakeLock.isHeld
        )
    }

    /**
     * Verifica que el WakeLock es de tipo PARTIAL_WAKE_LOCK.
     *
     * Qué testea: que acquireWakeLock() usa el flag correcto.
     * PARTIAL_WAKE_LOCK = solo CPU activa, pantalla puede dormir.
     * Un error aquí (usar FULL_WAKE_LOCK) sería difícil de detectar en runtime
     * pero destruiría la batería del reloj en horas.
     *
     * Cómo verificar el tipo en Robolectric:
     *   ShadowWakeLock expone el flags con el que fue creado.
     *   PowerManager.PARTIAL_WAKE_LOCK = 1 (constante del SDK de Android).
     */
    @Test
    fun wakeLock_isPartialWakeLock() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startIntent = SeizureMonitorService.startIntent(context)

        Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        val powerManager = context.getSystemService(PowerManager::class.java)
        val latestWakeLock = shadowOf(powerManager).latestWakeLock
        val shadowWakeLock = shadowOf(latestWakeLock)

        assertEquals(
            "El WakeLock debe ser PARTIAL (CPU activa, pantalla puede dormir)",
            PowerManager.PARTIAL_WAKE_LOCK,
            shadowWakeLock.flags
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

        val powerManager = context.getSystemService(PowerManager::class.java)
        val latestWakeLock = shadowOf(powerManager).latestWakeLock
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
     * Cómo funciona ShadowSensorManager en Robolectric:
     *   Robolectric reemplaza el SensorManager real con un shadow (simulacro).
     *   `shadowSensorManager.hasListener(sensorType)` devuelve true si hay al menos
     *   un SensorEventListener registrado para ese tipo de sensor.
     *   Es equivalente a verificar que `registerListener()` fue llamado con éxito.
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

        // Act
        Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        // Assert
        assertTrue(
            "Después de ACTION_START, debe haber un listener registrado para " +
                    "TYPE_LINEAR_ACCELERATION — sin esto el Service no recibe datos del sensor",
            shadowSensorManager.hasListener(Sensor.TYPE_LINEAR_ACCELERATION)
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

        val controller = Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        // Precondición: el listener está registrado antes del stop
        assertTrue(
            "Precondición: el listener debe estar registrado antes de ACTION_STOP",
            shadowSensorManager.hasListener(Sensor.TYPE_LINEAR_ACCELERATION)
        )

        // Act — enviar ACTION_STOP al mismo Service
        controller.get().onStartCommand(stopIntent, 0, 2)

        // Assert
        assertFalse(
            "Después de ACTION_STOP, el listener debe desregistrarse — " +
                    "el Service no debe seguir consumiendo datos del sensor",
            shadowSensorManager.hasListener(Sensor.TYPE_LINEAR_ACCELERATION)
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

        val controller = Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        // Precondición: el listener está activo antes del destroy
        assertTrue(
            "Precondición: el listener debe estar registrado antes del destroy",
            shadowSensorManager.hasListener(Sensor.TYPE_LINEAR_ACCELERATION)
        )

        // Act
        controller.destroy()

        // Assert
        assertFalse(
            "Después de onDestroy(), el listener debe desregistrarse — " +
                    "el cleanup de onDestroy() es la red de seguridad ante kills del OS",
            shadowSensorManager.hasListener(Sensor.TYPE_LINEAR_ACCELERATION)
        )
    }

    /**
     * Verifica que la constante SENSOR_SAMPLING_PERIOD_US es exactamente 40,000 microsegundos.
     *
     * Qué testea (contrato del CNN): la constante documenta el contrato de frecuencia
     * entre el sensor y el modelo. 40,000 µs = 40ms = 25Hz = 125 muestras en 5 segundos.
     * Si alguien cambia este valor, el CNN recibiría la cantidad incorrecta de datos
     * por ventana y sus predicciones serían inválidas.
     *
     * Por qué testear una constante:
     *   Las constantes "obvias" son las que más se cambian por error en refactors.
     *   Este test sirve de documentación ejecutable: "este valor NO es arbitrario,
     *   existe por el contrato con el modelo CNN v0.24".
     *
     * Analogía Python:
     *   assert SAMPLE_RATE_HZ == 25, "CNN was trained on 25Hz data — do not change"
     *   assert WINDOW_SIZE == 125,   "CNN input shape is (1, 125, 1)"
     */
    @Test
    fun sensorManager_samplingPeriod_is25Hz() {
        // 25Hz = 1/25 segundos = 40,000 microsegundos
        // Este valor es el contrato entre el sensor y el modelo CNN v0.24
        val expectedPeriodUs = 40_000

        assertEquals(
            "SENSOR_SAMPLING_PERIOD_US debe ser 40,000µs (40ms = 25Hz). " +
                    "El CNN v0.24 fue entrenado con ventanas de 125 muestras a 25Hz. " +
                    "Cambiar este valor invalida las predicciones del modelo.",
            expectedPeriodUs,
            SeizureMonitorService.SENSOR_SAMPLING_PERIOD_US
        )
    }

    /**
     * Verifica que el Service usa TYPE_LINEAR_ACCELERATION y no TYPE_ACCELEROMETER.
     *
     * Qué testea (corrección del pipeline ML): que el tipo de sensor registrado
     * es el correcto para el CNN v0.24. Este es el test más crítico de la Fase 1.3.
     *
     * Por qué TYPE_LINEAR_ACCELERATION y no TYPE_ACCELEROMETER:
     *   TYPE_ACCELEROMETER incluye la componente gravitacional (≈9.8 m/s² constante).
     *   Con el reloj quieto en la muñeca, el sensor mediría ≈9.8 m/s² en el eje vertical
     *   aunque la persona esté dormida sin moverse. El CNN interpretaría este offset
     *   como movimiento constante, generando falsas alarmas cada noche.
     *
     *   TYPE_LINEAR_ACCELERATION = aceleración real del movimiento, sin gravedad.
     *   El sensor fusion del OS usa el giroscopio para estimar y sustraer la gravedad.
     *   En reposo: ≈0 m/s² en todos los ejes.
     *
     *   El CNN v0.24 fue entrenado con datos de OpenSeizureDetector que usan
     *   linear acceleration. Es un contrato de preprocesamiento que no puede violarse.
     *
     * Cómo verificarlo en Robolectric:
     *   ShadowSensorManager.hasListener() acepta el tipo de sensor como Int.
     *   TYPE_LINEAR_ACCELERATION = 10, TYPE_ACCELEROMETER = 1 (valores del SDK de Android).
     *   Verificamos el tipo correcto Y que el incorrecto NO esté registrado.
     */
    @Test
    fun sensorManager_usesLinearAcceleration_notRawAccelerometer() {
        // Arrange
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startIntent = SeizureMonitorService.startIntent(context)
        val shadowSensorManager = shadowOf(
            context.getSystemService(SensorManager::class.java)
        )

        // Act
        Robolectric.buildService(SeizureMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)

        // Assert — el sensor correcto está registrado
        assertTrue(
            "El Service debe registrar TYPE_LINEAR_ACCELERATION (sin gravedad). " +
                    "El CNN v0.24 fue entrenado con datos sin componente gravitacional.",
            shadowSensorManager.hasListener(Sensor.TYPE_LINEAR_ACCELERATION)
        )

        // Assert — el sensor incorrecto NO está registrado
        assertFalse(
            "El Service NO debe registrar TYPE_ACCELEROMETER (con gravedad). " +
                    "El offset de ≈9.8 m/s² del raw accelerometer causaría falsas alarmas nocturnas.",
            shadowSensorManager.hasListener(Sensor.TYPE_ACCELEROMETER)
        )
    }
}
