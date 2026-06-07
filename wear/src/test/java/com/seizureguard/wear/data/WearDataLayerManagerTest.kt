package com.seizureguard.wear.data

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase A (2026-06-05) — Tests del WearDataLayerManager con el formato ADAPTADO a OSD.
 *
 * El reloj ahora habla el mismo idioma que SdDataSourceAw.java de OpenSeizureDetector V5.0:
 *   - accel_data  → JSON {"samples":[...]} (magnitudes en milli-g)
 *   - alarm_state → JSON {"alarm_state":<int>, "alarm_phrase":"..."}
 *
 * Antes el reloj mandaba float32 LE crudo y esperaba 1 byte — eso NO matcheaba el parser de
 * OSD. Estos tests son la red de seguridad del contrato (ver engram architecture/seizureguard-aw-contract).
 *
 * Protocolo de validación de Graham Jones:
 *   Paso 1: enviar [1.0..750.0] → verificar que OSD los recibe en orden.
 *   Paso 2: reloj quieto → ~1000 milli-g en logcat de OSD.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WearDataLayerManagerTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val manager = WearDataLayerManager(context)

    // ─── accel_data: encoding JSON {"samples":[...]} ─────────────────────────

    @Test
    fun `samplesToJson produces samples array with correct count`() {
        val samples = FloatArray(125) { it.toFloat() }

        val json = JSONObject(String(manager.samplesToJsonBytes(samples), Charsets.UTF_8))

        assertEquals(
            "El JSON debe tener un array 'samples' con 125 elementos (clave que OSD parsea primero)",
            125,
            json.getJSONArray("samples").length()
        )
    }

    @Test
    fun `samplesToJson preserves values in order`() {
        val original = FloatArray(750) { i -> (i + 1).toFloat() }

        val arr = JSONObject(String(manager.samplesToJsonBytes(original), Charsets.UTF_8))
            .getJSONArray("samples")

        assertEquals("primer valor", 1.0, arr.getDouble(0), 0.0001)
        assertEquals("valor del medio", 376.0, arr.getDouble(375), 0.0001)
        assertEquals("último valor", 750.0, arr.getDouble(749), 0.0001)
    }

    @Test
    fun `samplesToJson sequential validation pattern matches Graham protocol`() {
        // Paso 1 de Graham: [1.0, 2.0, ..., 750.0] en orden.
        val sequential = FloatArray(750) { i -> (i + 1).toFloat() }

        val arr = JSONObject(String(manager.samplesToJsonBytes(sequential), Charsets.UTF_8))
            .getJSONArray("samples")

        // El array completo debe estar en orden estricto 1..750.
        for (i in 0 until 750) {
            assertEquals("posición $i", (i + 1).toDouble(), arr.getDouble(i), 0.0001)
        }
    }

    @Test
    fun `samplesToJson empty array produces empty samples`() {
        val json = JSONObject(String(manager.samplesToJsonBytes(FloatArray(0)), Charsets.UTF_8))

        assertEquals(0, json.getJSONArray("samples").length())
    }

    // ─── alarm_state: parseo del JSON de OSD (RUTA CRÍTICA) ──────────────────

    @Test
    fun `parseAlarmState reads alarm_state from OSD json`() {
        val okBytes = """{"alarm_state":0,"alarm_phrase":"OK"}""".toByteArray(Charsets.UTF_8)
        val warnBytes = """{"alarm_state":1,"alarm_phrase":"WARNING"}""".toByteArray(Charsets.UTF_8)
        val alarmBytes = """{"alarm_state":2,"alarm_phrase":"ALARM"}""".toByteArray(Charsets.UTF_8)

        assertEquals(0, manager.parseAlarmState(okBytes))
        assertEquals(1, manager.parseAlarmState(warnBytes))
        assertEquals(2, manager.parseAlarmState(alarmBytes))
    }

    @Test
    fun `parseAlarmState tolerates extra fields and whitespace`() {
        val bytes = """{ "alarm_phrase": "possible seizure", "alarm_state": 2 }"""
            .toByteArray(Charsets.UTF_8)

        assertEquals(
            "Debe extraer alarm_state aunque el orden/espacios cambien y haya campos extra",
            2,
            manager.parseAlarmState(bytes)
        )
    }

    @Test
    fun `parseAlarmState returns null on malformed payload without crashing`() {
        // Un payload ilegible (ej: el viejo formato de 1 byte, o basura) NO debe crashear:
        // retorna null y el listener lo loguea sin tumbarse.
        assertNull(manager.parseAlarmState(byteArrayOf(0x02)))
        assertNull(manager.parseAlarmState("no soy json".toByteArray(Charsets.UTF_8)))
        assertNull(manager.parseAlarmState("""{"otra_cosa":1}""".toByteArray(Charsets.UTF_8)))
    }

    // ─── settings handshake (battery + sample_freq) ──────────────────────────

    @Test
    fun `settingsToJson produces the format OSD handleSettings expects`() {
        val json = JSONObject(String(manager.settingsToJsonBytes(87, 25), Charsets.UTF_8))

        assertEquals("battery debe ser el int que parsea OSD", 87, json.getInt("battery"))
        assertEquals("sample_freq debe ser el int que parsea OSD", 25, json.getInt("sample_freq"))
    }

    @Test
    fun `sendSettingsPath matches OSD protocol`() {
        assertEquals("/osd/settings", WearDataLayerManager.PATH_SETTINGS)
        assertEquals("/osd/send_settings", WearDataLayerManager.PATH_SEND_SETTINGS)
    }

    // ─── contratos de path ───────────────────────────────────────────────────

    @Test
    fun `alarmStatePath matches OSD protocol`() {
        assertEquals(
            "El path del alarmState debe coincidir con SdDataSourceAw.java",
            "/osd/alarm_state",
            WearDataLayerManager.PATH_ALARM_STATE
        )
    }

    @Test
    fun `accelDataPath matches OSD protocol`() {
        assertEquals(
            "El path de accel_data debe coincidir con SdDataSourceAw.java",
            "/osd/accel_data",
            WearDataLayerManager.PATH_ACCEL_DATA
        )
    }
}
