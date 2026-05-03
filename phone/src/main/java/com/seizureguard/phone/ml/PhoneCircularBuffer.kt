package com.seizureguard.phone.ml

/**
 * Buffer circular en el teléfono para acumular magnitudes (milli-g) hasta [inputSize]
 * y exponer ventanas deslizantes para inferencia (DEC-040).
 *
 * API alineada conceptualmente con [com.seizureguard.wear.ml.CircularBuffer] del wear,
 * con [addAll] para ingestar chunks completos por mensaje Wear.
 *
 * Thread safety: todas las mutaciones van bajo el mismo lock (mensajes Data Layer
 * pueden llegar en distinto hilo según GMS).
 *
 * @param inputSize Tamaño de la ventana para inferencia (default 750 = 30 s a 25 Hz).
 */
class PhoneCircularBuffer(val inputSize: Int = 750) {
    private val buffer = FloatArray(inputSize)
    private var writeIndex = 0
    private var count = 0
    private val lock = Any()

    fun addAll(values: FloatArray) {
        synchronized(lock) {
            for (v in values) {
                buffer[writeIndex] = v
                writeIndex = (writeIndex + 1) % inputSize
                if (count < inputSize) count++
            }
        }
    }

    val isFull: Boolean
        get() = synchronized(lock) { count == inputSize }

    /**
     * Copia en orden cronológico (más antiguo → más reciente). Vacío si aún no está lleno.
     */
    fun snapshot(): FloatArray {
        synchronized(lock) {
            if (count < inputSize) return FloatArray(0)
            val result = FloatArray(inputSize)
            val startIndex = writeIndex
            for (i in 0 until inputSize) {
                result[i] = buffer[(startIndex + i) % inputSize]
            }
            return result
        }
    }

    val size: Int
        get() = synchronized(lock) { count }

    fun reset() {
        synchronized(lock) {
            buffer.fill(0f)
            writeIndex = 0
            count = 0
        }
    }
}
