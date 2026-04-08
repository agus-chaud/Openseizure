package com.seizureguard.wear.logging

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger de datos del acelerómetro a CSV para análisis y validación.
 *
 * Propósito:
 *   Antes de conectar el TFLite Interpreter (Fase 2.1), necesitamos verificar
 *   que el pipeline de sensores produce datos correctos:
 *   - Frecuencia real ≈ 25Hz (una muestra cada ~40ms)
 *   - Magnitud en reposo ≈ 1000 milli-g (TYPE_ACCELEROMETER con gravedad incluida)
 *   - Sin gaps prolongados cuando el reloj ahorra batería
 *
 * Analogía Python:
 *   ```python
 *   with open("raw_accel.csv", "w") as f:
 *       f.write("timestamp_ms,x,y,z,magnitude\n")
 *       for sample in sensor_stream:
 *           f.write(f"{sample.ts},{sample.x},{sample.y},{sample.z},{sample.mag}\n")
 *   ```
 *
 * Ubicación del archivo:
 *   getExternalFilesDir() → accesible sin root via:
 *   ```
 *   adb pull /sdcard/Android/data/com.seizureguard.wear/files/logs/
 *   ```
 *
 * Thread safety:
 *   write() se llama desde el thread del sensor. BufferedWriter con synchronized
 *   evita escrituras entrelazadas. close() también es synchronized.
 *
 * Ver DEC-029, DEC-030, DEC-031 en DECISIONS.md para las decisiones de diseño.
 *
 * @param context Contexto Android para resolver getExternalFilesDir()
 */
class CsvLogger(private val context: Context) {

    private var writer: BufferedWriter? = null
    private val lock = Any()
    private var isOpen = false

    /**
     * Abre el archivo CSV y escribe el header.
     * El nombre del archivo incluye timestamp para no sobrescribir sesiones anteriores.
     *
     * Comportamiento si ya está abierto: retorna null sin abrir un segundo archivo.
     * Esto protege contra llamadas duplicadas desde onMonitoringStart().
     *
     * @return El path absoluto del archivo creado, o null si ya estaba abierto o falló la creación.
     */
    fun open(): String? {
        synchronized(lock) {
            if (isOpen) return null

            return try {
                val logsDir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(logsDir, "raw_accel_$timestamp.csv")

                writer = BufferedWriter(FileWriter(file))
                writer?.write("timestamp_ms,x,y,z,magnitude\n")
                writer?.flush()
                isOpen = true

                Log.i(TAG, "CSV logging iniciado: ${file.absolutePath}")
                file.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo abrir el archivo CSV", e)
                null
            }
        }
    }

    /**
     * Escribe una muestra del acelerómetro al CSV.
     * No-op silencioso si el logger no está abierto — seguro de llamar siempre.
     *
     * Por qué no hay flush() aquí:
     *   BufferedWriter acumula datos en un buffer interno y los escribe al disco en bloques.
     *   Llamar flush() en cada muestra (25 veces/segundo × 8 horas = 720,000 flushes)
     *   derrotaría el propósito del buffer. El flush final ocurre en close().
     *   Ver DEC-031 para el análisis de tradeoffs.
     *
     * @param timestampMs Timestamp en milisegundos (System.currentTimeMillis())
     * @param x Aceleración eje X en m/s² (con gravedad incluida, TYPE_ACCELEROMETER)
     * @param y Aceleración eje Y en m/s²
     * @param z Aceleración eje Z en m/s²
     * @param magnitude √(x²+y²+z²) convertida a milli-g — calculada en SeizureMonitorService antes de llamar aquí
     */
    fun write(timestampMs: Long, x: Float, y: Float, z: Float, magnitude: Float) {
        synchronized(lock) {
            if (!isOpen) return
            try {
                writer?.write("$timestampMs,$x,$y,$z,$magnitude\n")
            } catch (e: Exception) {
                Log.e(TAG, "Error al escribir muestra CSV", e)
            }
        }
    }

    /**
     * Flush y cierre del archivo. Llamar siempre en onDestroy() del Service.
     * Idempotente — llamar varias veces no lanza excepción.
     *
     * Por qué flush() explícito antes de close():
     *   close() llama flush() internamente en la mayoría de las implementaciones de
     *   BufferedWriter, pero el contrato de Java no lo garantiza si ocurre una excepción
     *   durante el cierre. El flush() explícito asegura que los datos llegaron al OS
     *   antes de cerrar el file descriptor.
     *
     * Ver DEC-032 para la discusión del orden sensor → csvLogger → WakeLock en onDestroy().
     */
    fun close() {
        synchronized(lock) {
            if (!isOpen) return
            try {
                writer?.flush()
                writer?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error al cerrar el CSV logger", e)
            } finally {
                writer = null
                isOpen = false
                Log.i(TAG, "CSV logging cerrado")
            }
        }
    }

    /**
     * true si el logger está activo y escribiendo al archivo.
     *
     * Analogía Python:
     *   Es como verificar `not f.closed` en un file object abierto.
     */
    val isLogging: Boolean
        get() = synchronized(lock) { isOpen }

    companion object {
        private const val TAG = "CsvLogger"
    }
}
