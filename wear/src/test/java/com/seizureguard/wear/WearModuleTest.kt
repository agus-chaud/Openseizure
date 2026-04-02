package com.seizureguard.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 0.1 — Smoke tests del módulo Wear.
 *
 * Verifica que:
 * 1. El módulo compila correctamente
 * 2. El test runner está configurado y funciona
 * 3. La lógica básica de la app está lista para ser construida
 *
 * Estos tests evolucionarán en fases posteriores:
 * - Fase 1.5: CircularBufferTest (ring buffer de 125 muestras)
 * - Fase 2.4: SeizureStateMachineTest (OK → WARNING → ALARM)
 * - Fase 0.3: TFLiteModelLoadTest (carga del modelo CNN v0.24)
 */
class WearModuleTest {

    @Test
    fun smokeTest_moduleCompilesAndTestRunnerWorks() {
        // Arrange
        val moduleName = "SeizureGuard Wear"

        // Act
        val isReady = moduleName.isNotEmpty()

        // Assert
        assertTrue("Módulo Wear configurado correctamente", isReady)
    }

    @Test
    fun modelConstants_inputShapeIsCorrect() {
        // Arrange — constantes del modelo CNN v0.24 (documentadas en DevPlan)
        val expectedSamples = 125       // 5 segundos a 25Hz
        val expectedChannels = 1        // magnitud vectorial (scalar)
        val expectedBatchSize = 1       // inferencia single-sample

        // Act — en Fase 2.1 estas constantes vendrán del TFLiteInferenceEngine
        val tensorInput = Triple(expectedBatchSize, expectedSamples, expectedChannels)

        // Assert
        assertEquals("Batch size debe ser 1", 1, tensorInput.first)
        assertEquals("Input debe tener 125 muestras (5s @ 25Hz)", 125, tensorInput.second)
        assertEquals("Canal único: magnitud vectorial", 1, tensorInput.third)
    }

    @Test
    fun sensorSampling_frequencyAndWindowSizeAreConsistent() {
        // Arrange
        val samplingRateHz = 25
        val windowSeconds = 5

        // Act
        val samplesPerWindow = samplingRateHz * windowSeconds

        // Assert
        assertEquals(
            "25Hz × 5s debe dar exactamente 125 muestras para el tensor CNN",
            125,
            samplesPerWindow
        )
    }
}
