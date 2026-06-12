package com.seizureguard.wear.alarm

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Gestiona la respuesta háptica del reloj según el alarmState recibido del teléfono.
 *
 * AlarmState (protocolo OSD V5):
 *   0 = OK      → sin vibración
 *   1 = WARNING → pulso corto suave (100ms)
 *   2 = ALARM   → patrón repetido fuerte (3 × 500ms con 200ms de pausa)
 *   3+= otros   → tratado como ALARM
 *
 * Por qué VibrationEffect y no el constructor deprecated:
 *   Vibrator.vibrate(long) está deprecated en API 26+.
 *   VibrationEffect.createOneShot() y createWaveform() son la API actual
 *   y permiten controlar amplitud además de duración.
 *
 * @param context applicationContext del Service
 */
class AlarmStateManager(private val context: Context) {

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * Ejecuta la respuesta háptica correspondiente al alarmState.
     * No-op si alarmState == 0 (OK).
     *
     * @param alarmState Entero recibido del teléfono via /osd/alarm_state
     */
    fun handleAlarmState(alarmState: Int) {
        Log.i(TAG, "Manejando alarmState=$alarmState")
        when (alarmState) {
            ALARM_OK      -> { /* Sin vibración — monitoreo normal */ }
            ALARM_WARNING -> vibrateWarning()
            else          -> vibrateAlarm()   // ALARM (2) y cualquier valor >= 2
        }
    }

    /**
     * Pulso corto suave para WARNING.
     * Amplitud 80/255 (~31%) — notable pero no alarmante.
     */
    private fun vibrateWarning() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                WARNING_DURATION_MS,
                WARNING_AMPLITUDE
            )
        )
    }

    /**
     * Patrón repetido fuerte para ALARM.
     * 3 pulsos de 500ms con 200ms de pausa entre ellos.
     * Amplitud máxima (255) — debe despertar al cuidador.
     *
     * createWaveform(timings, amplitudes, repeat):
     *   timings:    [pausa_inicial, on, off, on, off, on]
     *   amplitudes: [0,  255, 0,   255, 0,   255]
     *   repeat:     -1 (no repetir automáticamente — el Service controla el ciclo)
     */
    private fun vibrateAlarm() {
        if (!vibrator.hasVibrator()) return
        val timings    = longArrayOf(0, 500, 200, 500, 200, 500)
        val amplitudes = intArrayOf(  0, 255,   0, 255,   0, 255)
        vibrator.vibrate(
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        )
    }

    /**
     * Aviso háptico de "monitoreo DEGRADADO" (T8 — watchdog).
     *
     * Patrón distinto del de WARNING (un pulso corto) y del de ALARM (3 pulsos fuertes):
     * dos pulsos medios, para que el usuario distinga "el monitoreo dejó de funcionar"
     * de "posible convulsión". No es una alarma clínica — es un aviso de mal funcionamiento.
     */
    fun vibrateDegraded() {
        if (!vibrator.hasVibrator()) return
        val timings    = longArrayOf(0, 250, 150, 250)
        val amplitudes = intArrayOf(  0, 140,   0, 140)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    companion object {
        const val ALARM_OK      = 0
        const val ALARM_WARNING = 1
        // 2+ se trata como ALARM

        private const val WARNING_DURATION_MS = 100L
        private const val WARNING_AMPLITUDE   = 80
        private const val TAG = "AlarmStateManager"
    }
}
