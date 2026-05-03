package com.seizureguard.wear.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 2.1 — Tests del WearDataLayerManager.
 *
 * Estrategia (alineada a smart-testing / pirámide): comportamiento observable de la
 * serialización (entradas/salidas, tamaños en bytes, endianness), sin acoplarse a GMS
 * ni a detalles internos del cliente de mensajes.
 *
 * WearDataLayerManager usa Wearable.getMessageClient() que requiere Google Play Services.
 * Esa parte no se testea aquí sin mocks profundos. Estos tests cubren la lógica pura:
 * FloatArray ↔ ByteArray y constantes de path del protocolo OSD (DEC-039).
 *
 * Por qué importa testear la serialización:
 *   SdDataSourceAw.java (teléfono) deserializa los bytes con ByteOrder.LITTLE_ENDIAN.
 *   Si el reloj serializa en big-endian, los floats llegan con los bytes invertidos
 *   y el modelo infiere sobre basura. Este test es la red de seguridad de ese contrato.
 *
 * Contrato N floats por mensaje (DEC-039): el payload `/osd/accel_data` es variable;
 * aquí se fijan los tamaños previstos 125 (~5 s de transporte) y 750 (ventana modelo).
 *
 * Protocolo de validación de Graham Jones (documentado en test 5):
 *   Paso 1: enviar [1.0..750.0] → verificar que el teléfono los recibe en orden.
 *   Paso 2: reloj quieto → verificar ~1000 milli-g en logcat del teléfono.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WearDataLayerManagerTest {

    // floatsToBytes/bytesToFloats no usan APIs de Android, pero WearDataLayerManager
    // requiere un Context en el constructor (para inicializar el MessageClient vía lazy).
    // ApplicationProvider.getApplicationContext() es el patrón estándar en Robolectric
    // para obtener un Context sin instanciar una Activity real.
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val manager = WearDataLayerManager(context)

    // ─── Test 1: round-trip ───────────────────────────────────────────────────

    @Test
    fun `floatsToBytes roundTrip preserves values`() {
        // Arrange: array con valores conocidos (incluyendo negativos y decimales)
        val original = FloatArray(750) { i -> (i - 375).toFloat() * 1.5f }

        // Act
        val bytes = manager.floatsToBytes(original)
        val recovered = manager.bytesToFloats(bytes)

        // Assert: los valores originales deben ser idénticos a los recuperados
        // (Float.SIZE_BYTES = 4 → no hay pérdida en la conversión float → bytes → float)
        assertArrayEquals(
            "El round-trip floats → bytes → floats debe preservar todos los valores",
            original,
            recovered,
            0.0001f
        )
    }

    // ─── Test 2: tamaño del ByteArray ─────────────────────────────────────────

    @Test
    fun `floatsToBytes 750 samples produces 3000 bytes`() {
        // Arrange
        val samples = FloatArray(750) { it.toFloat() }

        // Act
        val bytes = manager.floatsToBytes(samples)

        // Assert: 750 floats × 4 bytes/float = 3000 bytes exactos
        assertEquals(
            "750 floats × Float.SIZE_BYTES(4) debe producir exactamente 3000 bytes",
            3000,
            bytes.size
        )
    }

    /** Contrato DEC-039: chunk de transporte típico 125 floats → 500 bytes, round-trip estable. */
    @Test
    fun `transportPayload 125 floats roundTrips and occupies 500 bytes`() {
        val original = FloatArray(125) { i -> (i - 62).toFloat() * 0.25f }

        val bytes = manager.floatsToBytes(original)

        assertEquals(
            "125 floats × 4 bytes = 500 bytes (tamaño de mensaje accel_data para ese N)",
            500,
            bytes.size
        )
        val recovered = manager.bytesToFloats(bytes)
        assertArrayEquals(
            "Round-trip con N=125 debe preservar todos los valores",
            original,
            recovered,
            0.0001f
        )
    }

    /** Regla observable: tamaño en bytes = N×4 para los N previstos en el plan de transporte. */
    @Test
    fun `transportPayload byteLength is four times float count for 125 and 750`() {
        val cases = listOf(125 to 500, 750 to 3000)
        for ((n, expectedBytes) in cases) {
            val samples = FloatArray(n) { it.toFloat() }
            val bytes = manager.floatsToBytes(samples)
            assertEquals(
                "N=$n → ${n * 4} bytes",
                expectedBytes,
                bytes.size
            )
        }
    }

    // ─── Test 3: verificar little-endian ─────────────────────────────────────

    @Test
    fun `floatsToBytes is little endian`() {
        // Arrange: 1.0f en IEEE 754 = 0x3F800000
        // En little-endian los bytes van del menos significativo al más significativo:
        //   [0x00, 0x00, 0x80, 0x3F]
        // En big-endian sería: [0x3F, 0x80, 0x00, 0x00]
        val samples = FloatArray(1) { 1.0f }

        // Act
        val bytes = manager.floatsToBytes(samples)

        // Assert: verificar los 4 bytes del primer (y único) float
        assertEquals("Byte 0 de 1.0f en little-endian debe ser 0x00", 0x00.toByte(), bytes[0])
        assertEquals("Byte 1 de 1.0f en little-endian debe ser 0x00", 0x00.toByte(), bytes[1])
        assertEquals("Byte 2 de 1.0f en little-endian debe ser 0x80", 0x80.toByte(), bytes[2])
        assertEquals("Byte 3 de 1.0f en little-endian debe ser 0x3F", 0x3F.toByte(), bytes[3])
    }

    // ─── Test 4: array vacío ──────────────────────────────────────────────────

    @Test
    fun `bytesToFloats empty array returns empty float array`() {
        // Arrange
        val emptyBytes = ByteArray(0)

        // Act
        val result = manager.bytesToFloats(emptyBytes)

        // Assert
        assertEquals(
            "bytesToFloats(ByteArray(0)) debe retornar un FloatArray vacío",
            0,
            result.size
        )
    }

    // ─── Test 5: protocolo de validación de Graham Jones ─────────────────────

    @Test
    fun `sequential test data has correct values`() {
        // Arrange + Act: el patrón de debug es números secuenciales 1.0..750.0
        // Este es el "paso 1" del protocolo de validación: el teléfono verifica
        // que recibe exactamente [1.0, 2.0, ..., 750.0] en ese orden.
        // Si algún float llega con bytes invertidos o reordenados, el test en el
        // teléfono falla y sabemos que hay un problema de serialización.
        val sequential = FloatArray(750) { i -> (i + 1).toFloat() }

        // Assert: verificar inicio, medio y fin del array secuencial
        assertEquals(
            "El primer elemento del array secuencial debe ser 1.0f",
            1.0f,
            sequential[0],
            0.001f
        )
        assertEquals(
            "El elemento 375 del array secuencial debe ser 376.0f",
            376.0f,
            sequential[375],
            0.001f
        )
        assertEquals(
            "El último elemento del array secuencial debe ser 750.0f",
            750.0f,
            sequential[749],
            0.001f
        )
    }

    // ─── Test 6: contrato del path /osd/alarm_state ───────────────────────────

    @Test
    fun `alarmStatePath matches OSD protocol`() {
        // Este test es un contrato: si alguien cambia el path, el test falla
        // y documenta por qué importa — SdDataSourceAw.java en el teléfono
        // escucha exactamente en "/osd/alarm_state". Cambiar el path rompe
        // la comunicación silenciosamente (sin error en compilación).
        assertEquals(
            "El path del alarmState debe coincidir con el protocolo OSD de SdDataSourceAw.java",
            "/osd/alarm_state",
            WearDataLayerManager.PATH_ALARM_STATE
        )
    }

    // ─── Test 7: contrato del path /osd/accel_data ────────────────────────────

    @Test
    fun `accelDataPath matches OSD protocol`() {
        // Mismo razonamiento que el test anterior: el path es un contrato
        // entre el reloj y SdDataSourceAw.java. Cambiarlo rompe el canal
        // de datos principal sin error visible en compilación.
        assertEquals(
            "El path de accel_data debe coincidir con el protocolo OSD de SdDataSourceAw.java",
            "/osd/accel_data",
            WearDataLayerManager.PATH_ACCEL_DATA
        )
    }
}
