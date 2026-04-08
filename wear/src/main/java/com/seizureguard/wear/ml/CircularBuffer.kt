package com.seizureguard.wear.ml

/**
 * Buffer circular de capacidad fija para acumular muestras del acelerómetro.
 *
 * Contexto del pipeline:
 *   El modelo DeepEpiCnn Run24 requiere exactamente 750 muestras (30 segundos a 25Hz)
 *   para hacer una inferencia. Este buffer mantiene siempre los últimos
 *   30 segundos de magnitud de aceleración en milli-g.
 *
 * Analogía Python:
 *   from collections import deque
 *   buffer = deque(maxlen=750)
 *   buffer.append(valor)  # descarta el más antiguo automáticamente
 *
 * Thread safety:
 *   Los métodos add() e isFull están sincronizados porque onSensorChanged()
 *   llega en un thread del OS distinto al que lee el buffer para inferencia.
 *   Sin sincronización: condición de carrera → datos corruptos → falsa alarma
 *   o convulsión no detectada a las 3am.
 *
 * @param capacity Número de muestras. Por defecto 750 (30s × 25Hz — input shape del modelo DeepEpiCnn Run24).
 */
class CircularBuffer(val capacity: Int = 750) {
    private val buffer = FloatArray(capacity)
    private var writeIndex = 0
    private var count = 0
    private val lock = Any()

    /**
     * Agrega una muestra al buffer, reemplazando la más antigua si está lleno.
     *
     * @param value Magnitud de aceleración √(x²+y²+z²) en milli-g
     */
    fun add(value: Float) {
        synchronized(lock) {
            buffer[writeIndex] = value
            writeIndex = (writeIndex + 1) % capacity
            if (count < capacity) count++
        }
    }

    /**
     * Retorna true cuando el buffer tiene exactamente [capacity] muestras.
     * El CNN no debe inferir con un buffer parcial — los primeros 5 segundos
     * de monitoreo se descartan hasta que haya suficientes datos.
     */
    val isFull: Boolean
        get() = synchronized(lock) { count == capacity }

    /**
     * Copia el contenido del buffer en orden cronológico (más antiguo → más reciente).
     * Retorna un FloatArray nuevo — el caller es dueño de su propia copia.
     *
     * Por qué copia y no referencia directa:
     *   El buffer sigue recibiendo datos mientras el modelo infiere.
     *   Una referencia directa podría leer datos a medio actualizar.
     *
     * Cómo funciona el índice de inicio:
     *   writeIndex apunta siempre a la PRÓXIMA posición a escribir,
     *   que es también la posición de la muestra MÁS ANTIGUA (la que va a ser
     *   sobreescrita en el próximo add()). Recorrer desde writeIndex hacia adelante
     *   da el orden cronológico correcto.
     *
     *   Ejemplo con capacity=5 y 7 elementos agregados (0,1,2,3,4,5,6):
     *     buffer interno: [5, 6, 2, 3, 4]
     *     writeIndex = 2  ← próximo a escribir, también el más antiguo
     *     snapshot()  → [2, 3, 4, 5, 6]  ← orden cronológico correcto
     *
     * @return FloatArray de [capacity] elementos en orden cronológico,
     *         o FloatArray vacío si el buffer no está lleno todavía.
     */
    fun snapshot(): FloatArray {
        synchronized(lock) {
            if (count < capacity) return FloatArray(0)
            val result = FloatArray(capacity)
            val startIndex = writeIndex  // el próximo a escribir = el más antiguo
            for (i in 0 until capacity) {
                result[i] = buffer[(startIndex + i) % capacity]
            }
            return result
        }
    }

    /** Cantidad de muestras actualmente en el buffer (0..capacity). */
    val size: Int
        get() = synchronized(lock) { count }

    /** Reinicia el buffer a estado vacío. Útil cuando el Service se detiene. */
    fun reset() {
        synchronized(lock) {
            buffer.fill(0f)
            writeIndex = 0
            count = 0
        }
    }
}
