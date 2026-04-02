package com.seizureguard.wear.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 0.4 — Test instrumented: verifica que el modelo carga desde el APK real.
 *
 * Por qué este test existe (TODO-001):
 *
 *   Los tests Robolectric (TFLiteModelLoaderTest) cargan el fixture desde
 *   src/test/assets/ en disco — nunca tocan el APK empaquetado.
 *   Si aaptOptions { noCompress += "tflite" } está mal configurado, el modelo
 *   queda comprimido en el APK y FileChannel.map() falla en runtime... pero
 *   los tests Robolectric siguen en verde.
 *
 *   Este test corre EN EL WATCH REAL, cargando el modelo desde el APK instalado.
 *   Es el único test que puede detectar el bug de compresión.
 *
 * Diferencia vs TFLiteModelLoaderTest (Robolectric):
 *
 *   TFLiteModelLoaderTest     → carga de src/test/assets/ (disco)    → sin watch
 *   TFLiteModelLoaderInstrumentedTest → carga del APK instalado → REQUIERE WATCH
 *
 * Cómo correrlo:
 *
 *   1. Conectar el watch: ./scripts/connect_watch.sh <IP>
 *   2. ./gradlew :wear:connectedAndroidTest
 *   O desde Android Studio: click derecho → Run 'TFLiteModelLoaderInstrumentedTest'
 *
 * Qué verifica:
 *
 *   1. El archivo existe en el APK empaquetado
 *   2. No está comprimido (memory-mapping funciona)
 *   3. El buffer tiene el tamaño correcto (~200 KB)
 */
@RunWith(AndroidJUnit4::class)
class TFLiteModelLoaderInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun modelLoads_fromInstalledApk_returnsBuffer() = runTest {
        val buffer = TFLiteModelLoader.load(context, "cnn_v024.tflite")

        assertNotNull("El modelo debe cargarse desde el APK instalado", buffer)
    }

    @Test
    fun modelLoads_fromInstalledApk_bufferSizeMatchesRealModel() = runTest {
        val buffer = TFLiteModelLoader.load(context, "cnn_v024.tflite")

        // El modelo real mide 209,456 bytes (204.5 KB)
        // Un buffer mucho más pequeño indicaría que el APK comprimió el asset
        // y el memory-mapping funcionó parcialmente o con datos corruptos.
        val minExpectedBytes = 100_000  // 100KB mínimo — si falla, el modelo está comprimido
        assertTrue(
            "El buffer debe tener al menos ${minExpectedBytes / 1024}KB. " +
                "Si es menor, el modelo puede estar comprimido en el APK. " +
                "Verificar: aaptOptions { noCompress += \"tflite\" } en wear/build.gradle.kts",
            buffer.limit() >= minExpectedBytes
        )
    }

    @Test
    fun modelLoads_fromInstalledApk_bufferMatchesExpectedSize() = runTest {
        val buffer = TFLiteModelLoader.load(context, "cnn_v024.tflite")

        val expectedBytes = 209_456  // tamaño exacto del cnn_v024.tflite
        assertTrue(
            "El buffer debería tener $expectedBytes bytes (${expectedBytes / 1024}KB). " +
                "Actual: ${buffer.limit()} bytes.",
            buffer.limit() == expectedBytes
        )
    }

    @Test(expected = ModelLoadException::class)
    fun modelLoad_withNonExistentFile_throwsModelLoadException() = runTest {
        TFLiteModelLoader.load(context, "modelo_inexistente.tflite")
    }
}
