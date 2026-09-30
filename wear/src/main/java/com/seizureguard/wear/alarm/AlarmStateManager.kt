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
 * AlarmState (OSD V5 protocol) and what the watch does with it (DEC-057, WCT-3, safety finding F2):
 *   0 = OK       -> no haptic
 *   1 = WARNING  -> short soft pulse (100 ms)
 *   2 = ALARM, 3 = FALL, 5 = MANUAL -> strong repeated pattern (3 x 500 ms, 200 ms pauses)
 *   4 = FAULT, 7 = NETFAULT         -> SILENT system fault: no haptic, no sound, visible + logged
 *   6 = MUTE     -> no haptic
 *   anything else (negative, 8+)    -> unknown: treated as a SILENT system fault, never as ALARM
 *
 * The only vibration in the whole system is a real emergency. The previous rule "vibrate ALARM for
 * any state >= 2" is gone: it turned an OSD fault (or a garbled value) into a false seizure alarm.
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
        val severity = classify(alarmState)
        Log.i(TAG, "Handling alarmState=$alarmState -> $severity")
        when (severity) {
            Severity.OK, Severity.MUTE -> { /* no haptic */ }
            Severity.WARNING -> vibrateWarning()
            Severity.ALARM -> vibrateAlarm()
            // Visual only + logged. Never vibrates: a fault must not be mistaken for a seizure
            // alarm at 3 am (DEC-057). The visible indication is rendered from the same
            // classification by the UI.
            Severity.SYSTEM_FAULT ->
                Log.e(TAG, "OSD system fault (alarmState=$alarmState): silent, no haptic")
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

    // There is intentionally NO haptic for pipeline DEGRADED (DEC-057, WCT-5): the only vibration in
    // the system is a real emergency. DEGRADED is visual only (notification), see
    // SeizureMonitorService.checkPipelineHealth.

    /**
     * What the watch does for an OSD alarm state. Pure and total: every Int maps to exactly one
     * value, so no input can fall through to a vibration by accident.
     */
    enum class Severity { OK, WARNING, ALARM, MUTE, SYSTEM_FAULT }

    companion object {
        const val ALARM_OK      = 0
        const val ALARM_WARNING = 1
        const val ALARM_ALARM   = 2
        const val ALARM_FALL    = 3
        const val ALARM_FAULT   = 4
        const val ALARM_MANUAL  = 5
        const val ALARM_MUTE    = 6
        const val ALARM_NETFAULT = 7

        /**
         * Maps a raw OSD alarm state to the watch behaviour (DEC-057 table):
         * 0 OK, 1 WARNING, 2/3/5 ALARM, 6 MUTE, 4/7 and any unknown value SYSTEM_FAULT.
         */
        fun classify(alarmState: Int): Severity = when (alarmState) {
            ALARM_OK -> Severity.OK
            ALARM_WARNING -> Severity.WARNING
            ALARM_ALARM, ALARM_FALL, ALARM_MANUAL -> Severity.ALARM
            ALARM_MUTE -> Severity.MUTE
            else -> Severity.SYSTEM_FAULT   // 4, 7 and every unknown/out-of-range value
        }

        private const val WARNING_DURATION_MS = 100L
        private const val WARNING_AMPLITUDE   = 80
        private const val TAG = "AlarmStateManager"
    }
}
