package com.seizureguard.wear.alarm

import android.app.Application
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import com.seizureguard.wear.service.SeizureMonitorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Fase 2.2 — Tests de AlarmStateManager (Robolectric, sin dispositivo).
 *
 * Qué verifican estos tests:
 *   1. alarmState=0 (OK)      → sin vibración
 *   2. alarmState=1 (WARNING) → vibra
 *   3. alarmState=2 (ALARM)   → vibra
 *   4. alarmState=5 (>= 2)    → tratado como ALARM, vibra
 *   5. WARNING usa duración corta (100ms)
 *   6. StateFlow inicial es ALARM_OK (0)
 *   7. Constantes ALARM_OK y ALARM_WARNING siguen el protocolo OSD
 *
 * Por qué Robolectric y no tests instrumented:
 *   ShadowVibrator permite verificar si se llamó al vibrator sin necesitar el hardware real.
 *   Corre en la JVM, sin reloj físico conectado.
 *
 * Por qué no se mockea el Vibrator:
 *   AlarmStateManager instancia el Vibrator directamente desde el Context.
 *   Robolectric ya lo shadowea automáticamente — no hay necesidad de mockear nada extra.
 *   Mockear el Vibrator sería testear el mock, no el comportamiento real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AlarmStateManagerTest {

    private lateinit var manager: AlarmStateManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        manager = AlarmStateManager(context)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun shadowVibrator() = shadowOf(
        ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(Vibrator::class.java)
    )

    // ─── Tests de vibración por alarmState ────────────────────────────────────

    /**
     * alarmState=0 (OK): sin vibración.
     *
     * Qué testea: que el estado normal de monitoreo no molesta al usuario.
     * Si OK vibrara, el reloj vibraría constantemente mientras monitorea — inaceptable.
     */
    @Test
    fun alarmState_ok_doesNotVibrate() {
        // Act
        manager.handleAlarmState(AlarmStateManager.ALARM_OK)

        // Assert
        assertFalse(
            "alarmState=0 (OK) no debe disparar vibración — el monitoreo normal es silencioso",
            shadowVibrator().isVibrating
        )
    }

    /**
     * alarmState=1 (WARNING): vibra.
     *
     * Qué testea: que cuando el teléfono detecta movimiento sospechoso,
     * el reloj emite una señal háptica al usuario.
     */
    @Test
    fun alarmState_warning_vibrates() {
        // Act
        manager.handleAlarmState(AlarmStateManager.ALARM_WARNING)

        // Assert
        assertTrue(
            "alarmState=1 (WARNING) debe disparar vibración — señal al usuario de movimiento sospechoso",
            shadowVibrator().isVibrating
        )
    }

    /**
     * alarmState=2 (ALARM): vibra.
     *
     * Qué testea: que la alarma real activa la vibración fuerte.
     */
    @Test
    fun alarmState_alarm_vibrates() {
        // Act
        manager.handleAlarmState(2)

        // Assert
        assertTrue(
            "alarmState=2 (ALARM) debe disparar vibración — posible convulsión detectada",
            shadowVibrator().isVibrating
        )
    }

    /**
     * alarmState=5 (MANUAL): tratado como ALARM, vibra.
     *
     * Nota (DEC-057): antes este test decía "cualquier valor >= 2 activa la alarma". Esa regla se
     * eliminó: solo 2, 3 y 5 vibran como ALARM; 4, 7 y los desconocidos son falla silenciosa
     * (ver los tests "Batch 7" al final del archivo). El assert de este test no cambió.
     */
    @Test
    fun alarmState_aboveAlarm_treatedAsAlarm() {
        // Act
        manager.handleAlarmState(5)

        // Assert
        assertTrue(
            "alarmState=5 (>= 2) debe tratarse como ALARM y disparar vibración",
            shadowVibrator().isVibrating
        )
    }

    /**
     * WARNING usa VibrationEffect — verifica que el efecto fue aplicado.
     *
     * Qué testea: que vibrateWarning() usa la API moderna VibrationEffect
     * (no el Vibrator.vibrate(long) deprecated de API < 26).
     *
     * ShadowVibrator.getMilliseconds() registra la duración del VibrationEffect.createOneShot()
     * que usó vibrateWarning(). Un valor == WARNING_DURATION_MS (100ms) confirma que:
     *   1. Se usó la API moderna VibrationEffect (no el deprecated Vibrator.vibrate(long)).
     *   2. La duración es la corta de WARNING (100ms), no el patrón largo de ALARM.
     */
    @Test
    fun alarmState_warning_usesShortDuration() {
        // Act
        manager.handleAlarmState(AlarmStateManager.ALARM_WARNING)

        // Assert
        val durationMs = shadowVibrator().milliseconds
        assertEquals(
            "WARNING debe usar VibrationEffect.createOneShot con duración corta de 100ms " +
                "(API moderna, no el deprecated Vibrator.vibrate(long))",
            100L,
            durationMs
        )
    }

    // ─── Tests del StateFlow ──────────────────────────────────────────────────

    /**
     * El StateFlow empieza en ALARM_OK (0).
     *
     * Qué testea: el valor inicial del flow antes de que llegue cualquier alarmState del teléfono.
     * Si empezara en un valor != 0, la UI mostraría un estado de alarma falso al iniciar el app.
     */
    @Test
    fun alarmStateFlow_initialValue_isOk() {
        // Assert — sin llamar nada, el valor inicial debe ser ALARM_OK
        assertEquals(
            "El StateFlow debe iniciar en ALARM_OK (0) — la UI no debe mostrar alarma al iniciar",
            AlarmStateManager.ALARM_OK,
            SeizureMonitorService.alarmState.value
        )
    }

    // ─── Tests de contrato del protocolo OSD ─────────────────────────────────

    /**
     * Las constantes siguen el protocolo OSD V5.
     *
     * Qué testea: que ALARM_OK == 0 y ALARM_WARNING == 1 son los valores correctos.
     * Este es un test de contrato — si alguien cambia estas constantes,
     * el protocolo con el teléfono se rompe silenciosamente.
     *
     * Analogía Python: assert ALARM_OK == 0, "OSD protocol: 0=OK, do not change"
     */
    @Test
    fun alarmStateConstants_matchOsdProtocol() {
        assertEquals(
            "ALARM_OK debe ser 0 según el protocolo OSD V5",
            0,
            AlarmStateManager.ALARM_OK
        )
        assertEquals(
            "ALARM_WARNING debe ser 1 según el protocolo OSD V5",
            1,
            AlarmStateManager.ALARM_WARNING
        )
    }

    // --- Batch 7 / T7.5: OSD alarm-state policy (DEC-057, WCT-3) ---------------------------

    private fun vibrates(alarmState: Int): Boolean {
        val vibrator = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(Vibrator::class.java)
        vibrator.cancel()                       // start each case from a silent vibrator
        manager.handleAlarmState(alarmState)
        return shadowOf(vibrator).isVibrating
    }

    @Test
    fun alarmStates_2_3_5_vibrateAsAlarm() {
        for (state in intArrayOf(2, 3, 5)) {
            assertTrue("alarmState=$state must vibrate (real emergency)", vibrates(state))
        }
    }

    @Test
    fun alarmState_1_warning_vibrates() {
        assertTrue(vibrates(AlarmStateManager.ALARM_WARNING))
    }

    @Test
    fun faultStates_4_and_7_areSilent() {
        assertFalse("FAULT (4) must never vibrate", vibrates(AlarmStateManager.ALARM_FAULT))
        assertFalse("NETFAULT (7) must never vibrate", vibrates(AlarmStateManager.ALARM_NETFAULT))
    }

    @Test
    fun muteState_6_isSilent() {
        assertFalse("MUTE (6) must not vibrate", vibrates(AlarmStateManager.ALARM_MUTE))
    }

    @Test
    fun okState_0_isSilent() {
        assertFalse(vibrates(AlarmStateManager.ALARM_OK))
    }

    @Test
    fun unknownStates_areSilentFault_neverAlarm() {
        for (state in intArrayOf(8, 99, 255, -1, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertFalse("unknown alarmState=$state must NOT vibrate", vibrates(state))
        }
    }

    @Test
    fun classify_mapsEveryOsdValueAsSpecified() {
        val expected = mapOf(
            0 to AlarmStateManager.Severity.OK,
            1 to AlarmStateManager.Severity.WARNING,
            2 to AlarmStateManager.Severity.ALARM,
            3 to AlarmStateManager.Severity.ALARM,
            4 to AlarmStateManager.Severity.SYSTEM_FAULT,
            5 to AlarmStateManager.Severity.ALARM,
            6 to AlarmStateManager.Severity.MUTE,
            7 to AlarmStateManager.Severity.SYSTEM_FAULT,
            8 to AlarmStateManager.Severity.SYSTEM_FAULT,
            99 to AlarmStateManager.Severity.SYSTEM_FAULT,
            -1 to AlarmStateManager.Severity.SYSTEM_FAULT
        )
        for ((state, severity) in expected) {
            assertEquals("classify($state)", severity, AlarmStateManager.classify(state))
        }
    }
}
