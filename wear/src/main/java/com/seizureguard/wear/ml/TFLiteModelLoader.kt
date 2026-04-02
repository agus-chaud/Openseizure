package com.seizureguard.wear.ml

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Carga el modelo TFLite desde assets/ como un MappedByteBuffer memory-mapped.
 *
 * Ownership contract:
 *   - Este loader retorna el buffer. El caller es responsable de crear el Interpreter
 *     (ver TFLiteInferenceEngine, Fase 2.1).
 *   - El MappedByteBuffer sobrevive al cierre del FileChannel — es independiente.
 *   - El Interpreter debe vivir en el serviceScope del SeizureMonitorService y
 *     cerrarse en onDestroy(). Ver TODO-002.
 *
 * Threading:
 *   - suspend fun garantiza que la carga corre en Dispatchers.IO, nunca en main thread.
 *
 * Error handling:
 *   - Si el archivo no existe en assets/, lanza ModelLoadException.
 *   - Si el APK tiene el modelo comprimido (aaptOptions mal configurado),
 *     channel.map() falla con IOException envuelto en ModelLoadException.
 */
object TFLiteModelLoader {

    /**
     * Carga [modelFileName] desde assets/ y retorna un MappedByteBuffer listo para
     * pasarle al Interpreter en Fase 2.1.
     *
     * @throws ModelLoadException si el archivo no existe o no puede ser memory-mapped.
     */
    suspend fun load(context: Context, modelFileName: String): MappedByteBuffer =
        withContext(Dispatchers.IO) {
            try {
                val assetFileDescriptor = context.assets.openFd(modelFileName)
                assetFileDescriptor.use { afd ->
                    FileInputStream(afd.fileDescriptor).channel.use { channel ->
                        // MappedByteBuffer es independiente del FileChannel después de map().
                        // El canal se cierra (use{}), el buffer queda válido.
                        channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            afd.startOffset,
                            afd.declaredLength
                        )
                    }
                }
            } catch (e: IOException) {
                throw ModelLoadException(
                    message = "No se pudo cargar el modelo '$modelFileName' desde assets/. " +
                        "Verificá que el archivo existe y que aaptOptions { noCompress += \"tflite\" } " +
                        "está configurado en wear/build.gradle.kts.",
                    cause = e
                )
            }
        }
}

/**
 * Se lanza cuando el modelo TFLite no puede ser cargado desde assets/.
 * El caller (SeizureMonitorService en Fase 1.1) debe capturar esta excepción
 * y mostrar una notificación de error al usuario.
 */
class ModelLoadException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
