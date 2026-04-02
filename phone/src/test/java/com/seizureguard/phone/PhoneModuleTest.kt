package com.seizureguard.phone

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fase 0.1 — Smoke tests del módulo Phone.
 *
 * Verifica que el módulo Companion compila y el test runner está configurado.
 * Tests más significativos se agregan en:
 * - Fase 3.1: DataLayerParserTest (parsear mensajes del watch)
 * - Fase 3.5: SeizureEventDaoTest (Room DB queries)
 */
class PhoneModuleTest {

    @Test
    fun smokeTest_moduleCompilesAndTestRunnerWorks() {
        // Arrange
        val moduleName = "SeizureGuard Phone"

        // Act
        val isReady = moduleName.isNotEmpty()

        // Assert
        assertTrue("Módulo Phone configurado correctamente", isReady)
    }

    @Test
    fun smsAlert_messageFormatIsCorrect() {
        // Arrange — formato del SMS de emergencia (Fase 3.3)
        val timestamp = "03:47"
        val appName = "SeizureGuard"

        // Act
        val message = "ALERTA $appName — posible convulsión detectada a las $timestamp"

        // Assert
        assertTrue("El SMS debe mencionar ALERTA", message.contains("ALERTA"))
        assertTrue("El SMS debe incluir el timestamp", message.contains(timestamp))
        assertEquals(
            "ALERTA SeizureGuard — posible convulsión detectada a las 03:47",
            message
        )
    }
}
