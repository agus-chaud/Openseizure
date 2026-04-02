package com.seizureguard.wear.ml

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 0.3 — Tests del mecanismo de carga del modelo TFLite.
 *
 * Qué se testea aquí:
 *   - El AssetManager puede abrir el archivo desde src/test/assets/
 *   - FileChannel.map() produce un MappedByteBuffer no vacío
 *   - La excepción correcta se lanza si el archivo no existe
 *
 * Qué NO se testea aquí (Fase 2.1):
 *   - Validación del schema TFLite (Interpreter creation)
 *   - Shapes del tensor: input (1,125,1) / output (1,2)
 *   - Inferencia real
 *
 * Fixture: model_fixture.tflite — FlatBuffer TFLite v3 mínimo válido (144 bytes),
 * generado via Python flatbuffers. No es el modelo de producción (cnn_v024.tflite).
 *
 * @Config(sdk = [30]) — Wear OS API mínima del proyecto (wear/build.gradle.kts minSdk=30)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TFLiteModelLoaderTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun modelLoads_withValidFixture_returnsBuffer() = runTest {
        val buffer = TFLiteModelLoader.load(context, "model_fixture.tflite")

        assertNotNull("El loader debe retornar un buffer no nulo", buffer)
    }

    @Test
    fun modelLoad_returnsNonEmptyBuffer() = runTest {
        val buffer = TFLiteModelLoader.load(context, "model_fixture.tflite")

        assertTrue(
            "El buffer debe tener contenido (limit > 0)",
            buffer.limit() > 0
        )
    }

    @Test(expected = ModelLoadException::class)
    fun modelLoad_withMissingFile_throwsModelLoadException() = runTest {
        TFLiteModelLoader.load(context, "archivo_que_no_existe.tflite")
    }
}
