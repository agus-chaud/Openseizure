package com.seizureguard.wear.ml

/**
 * Decide, por timestamp del sensor, qué muestras entran al pipeline para sostener una grilla
 * fija de un período ([periodNs]), sin importar a qué frecuencia entrega Android el acelerómetro.
 *
 * Por qué existe (DEC-068): el acelerómetro del hardware es compartido. Si otro cliente del
 * sistema (p. ej. el detector de inclinación de Samsung) lo registra a 50 Hz, Android entrega
 * 50 Hz también a nuestro listener aunque pidamos 25 Hz. Los chunks se arman por cantidad de
 * muestras, así que 125 muestras abarcarían 2.5 s en vez de 5 s y OSD vería un espectro duplicado.
 *
 * Algoritmo (grilla anclada):
 *   - La primera muestra se conserva e inicializa la grilla.
 *   - Se conserva una muestra si `ts >= nextDueNs - tolerancia`; al conservar, `nextDueNs += período`.
 *     Al estar anclada, la tasa conservada a largo plazo es exactamente 1/período para cualquier
 *     entrada >= 25 Hz (50 Hz: una de cada dos).
 *   - Tolerancia = período/4 (10 ms a 25 Hz): absorbe el jitter observado del sensor nativo
 *     (intervalos de 37.8 a 42.3 ms) para no descartar muestras de un reloj que ya va a 25 Hz.
 *   - Si la muestra llega con más de un período de atraso respecto de la grilla (sensor detenido
 *     o ráfaga de FIFO), se conserva y la grilla se re-sincroniza en `ts + período`: nunca hay
 *     ráfaga de muestras aceptadas para "alcanzar" el atraso.
 *   - Timestamp duplicado o que retrocede: se descarta, la grilla no se mueve hacia atrás.
 *
 * Sin filtro anti-aliasing: con entrada a 50 Hz el contenido sobre 12.5 Hz puede plegarse; la
 * banda de análisis de OSD es 3-8 Hz (ver DEC-068).
 *
 * No es thread-safe: se usa solo desde el callback del sensor (un único hilo) y desde el
 * registro del listener, que ocurre antes de que lleguen eventos.
 *
 * @param periodNs período objetivo en nanosegundos (derivado de SENSOR_SAMPLING_PERIOD_US).
 */
class SampleRateDecimator(private val periodNs: Long) {

    private val toleranceNs = periodNs / 4

    private var started = false
    private var lastTsNs = 0L
    private var nextDueNs = 0L

    /**
     * @param timestampNs `SensorEvent.timestamp` (ns, monótono).
     * @return true si la muestra se conserva.
     */
    fun shouldKeep(timestampNs: Long): Boolean {
        if (!started) {
            started = true
            lastTsNs = timestampNs
            nextDueNs = timestampNs + periodNs
            return true
        }
        if (timestampNs <= lastTsNs) return false
        lastTsNs = timestampNs

        if (timestampNs > nextDueNs + periodNs) {
            // Hueco: re-sincronizar sin ráfaga.
            nextDueNs = timestampNs + periodNs
            return true
        }
        if (timestampNs >= nextDueNs - toleranceNs) {
            nextDueNs += periodNs
            return true
        }
        return false
    }

    /** Olvida la grilla; la próxima muestra se conserva y la reinicia. */
    fun reset() {
        started = false
        lastTsNs = 0L
        nextDueNs = 0L
    }
}
