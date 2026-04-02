package com.seizureguard.wear.logging

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Fase 1.6 — Tests del CsvLogger (Robolectric, sin dispositivo).
 *
 * Qué verifican estos tests:
 *   - open() crea el archivo en disco con el header correcto.
 *   - write() agrega filas de datos al archivo.
 *   - close() hace flush y cierra el archivo, haciendo los datos accesibles.
 *   - Comportamientos defensivos: write() antes de open(), close() dos veces.
 *   - isLogging refleja el estado real del logger.
 *   - El nombre del archivo sigue el patrón raw_accel_YYYYMMDD_HHmmss.csv.
 *
 * Qué NO verifican estos tests:
 *   - Que el archivo se puede descargar con adb pull — eso requiere un dispositivo real.
 *   - Que getExternalFilesDir() es accesible sin root — eso es una garantía del SDK de Android.
 *   - La integración con SeizureMonitorService — eso está cubierto en SeizureMonitorServiceTest.
 *
 * Por qué Robolectric y no tests en JVM puro:
 *   CsvLogger usa Context para llamar getExternalFilesDir(). Sin Robolectric,
 *   no hay implementación de Context disponible en la JVM. Robolectric provee
 *   un Context simulado que implementa getExternalFilesDir() como un directorio
 *   temporal en el sistema de archivos del sistema operativo anfitrión (PC).
 *
 * Analogía Python:
 *   Es como usar `tmp_path` de pytest para testear código que escribe archivos
 *   a disco sin preocuparse por paths absolutos o limpieza manual.
 *
 * Nota sobre BuildConfig.DEBUG en Robolectric:
 *   En el contexto de tests Robolectric, BuildConfig.DEBUG = true siempre (build de debug).
 *   Los tests de CsvLogger no dependen de BuildConfig — testeamos el logger directamente,
 *   no el flag que lo activa. El flag se testea a nivel de integración en el Service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CsvLoggerTest {

    private lateinit var logger: CsvLogger
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        logger = CsvLogger(context)
    }

    @After
    fun tearDown() {
        // Asegurar que el logger siempre queda cerrado al terminar cada test.
        // Evita que un test que falla antes de close() deje el archivo abierto
        // y afecte a tests subsecuentes.
        logger.close()
    }

    /**
     * Verifica que open() crea el archivo en disco.
     *
     * Qué testea (comportamiento): que al llamar open(), existe un archivo
     * en el almacenamiento del dispositivo que puede ser descargado con adb pull.
     * Sin esto, el logging sería silenciosamente inefectivo.
     */
    @Test
    fun csvLogger_open_createsFile() {
        // Arrange — logger recién creado (tearDown lo cierra)

        // Act
        val path = logger.open()

        // Assert
        assertNotNull("open() debe retornar el path del archivo creado", path)
        assertTrue(
            "El archivo CSV debe existir en disco después de open()",
            File(path!!).exists()
        )
    }

    /**
     * Verifica que el archivo recién creado contiene el header correcto.
     *
     * Qué testea (contrato del formato CSV): el header es el contrato con
     * el análisis en Python. Si cambia el header, el código Python que lee
     * `df['timestamp_ms']` falla con KeyError. Este test documenta ese contrato.
     *
     * Por qué leer el archivo después de close() y no de open():
     *   Los datos pueden estar en el buffer de BufferedWriter, no en disco todavía.
     *   close() hace flush garantizando que el header llegó al sistema de archivos.
     */
    @Test
    fun csvLogger_open_writesHeader() {
        // Arrange
        val path = logger.open()
        logger.close()

        // Act
        val lines = File(path!!).readLines()

        // Assert
        assertTrue(
            "El archivo CSV debe tener al menos la línea de header",
            lines.isNotEmpty()
        )
        assertEquals(
            "El header debe ser exactamente 'timestamp_ms,x,y,z,magnitude' — " +
                    "este es el contrato con el análisis en Python (df['timestamp_ms'], etc.)",
            "timestamp_ms,x,y,z,magnitude",
            lines[0]
        )
    }

    /**
     * Verifica que write() agrega una fila con los datos correctos al CSV.
     *
     * Qué testea (comportamiento de escritura): que los valores que pasan por
     * el pipeline del sensor (x, y, z, magnitude) llegan al archivo con el
     * formato correcto para ser leídos por pandas.read_csv().
     *
     * Formato esperado: "timestamp_ms,x,y,z,magnitude" → "1000,1.0,2.0,3.0,3.74"
     * Nota: Kotlin convierte Float a String con punto decimal, eg. 1.0 no 1
     */
    @Test
    fun csvLogger_write_appendsRow() {
        // Arrange
        val path = logger.open()

        // Act
        logger.write(1000L, 1f, 2f, 3f, 3.74f)
        logger.close()

        // Assert
        val lines = File(path!!).readLines()
        assertEquals(
            "El archivo debe tener 2 líneas: header + 1 fila de datos",
            2,
            lines.size
        )
        assertTrue(
            "La fila de datos debe contener los valores escritos. " +
                    "Línea actual: '${lines[1]}'",
            lines[1].contains("1000") &&
                    lines[1].contains("1.0") &&
                    lines[1].contains("2.0") &&
                    lines[1].contains("3.0") &&
                    lines[1].contains("3.74")
        )
    }

    /**
     * Verifica que write() antes de open() no lanza excepción.
     *
     * Qué testea (comportamiento defensivo): que el caller puede llamar write()
     * de forma segura independientemente del estado del logger. Esto es importante
     * porque onAccelerometerSample() llama write() continuamente, y si el logger
     * no está abierto (ej: isLoggingEnabled = false, o falló el open()), no debe
     * romper el pipeline de sensores.
     */
    @Test
    fun csvLogger_write_beforeOpen_doesNotCrash() {
        // Arrange — logger no abierto (se verifica implícitamente: si crashea, el test falla)

        // Act + Assert — no debe lanzar excepción
        logger.write(System.currentTimeMillis(), 1f, 2f, 3f, 3.74f)
        // Si llegamos acá sin excepción, el test pasa
    }

    /**
     * Verifica que close() hace flush de todos los datos escritos antes del cierre.
     *
     * Qué testea (comportamiento de flush): que los datos en el buffer interno de
     * BufferedWriter llegan al disco cuando se cierra el archivo. Sin flush(),
     * las últimas muestras de la noche podrían perderse si el Service se cierra
     * justo antes de que el buffer se vuelque automáticamente.
     *
     * Por qué 3 muestras: verifica que el buffer acumula correctamente y que
     * todas llegan al disco, no solo la última.
     */
    @Test
    fun csvLogger_close_flushesData() {
        // Arrange
        val path = logger.open()

        // Act — escribir 3 muestras y cerrar
        logger.write(1000L, 0.1f, 0.2f, 0.3f, 0.37f)
        logger.write(1040L, 0.4f, 0.5f, 0.6f, 0.87f)
        logger.write(1080L, 0.7f, 0.8f, 0.9f, 1.39f)
        logger.close()

        // Assert
        val lines = File(path!!).readLines()
        assertEquals(
            "Después de close(), el archivo debe tener 4 líneas: 1 header + 3 datos. " +
                    "Si el flush falló, algunas líneas podrían faltar.",
            4,
            lines.size
        )
    }

    /**
     * Verifica que close() es idempotente — llamarlo dos veces no lanza excepción.
     *
     * Qué testea (comportamiento defensivo): que el cleanup de onDestroy() y
     * ACTION_STOP puede llamar close() sin riesgo de crash. En el lifecycle del
     * Service, onDestroy() siempre llama close() como red de seguridad, incluso
     * si ACTION_STOP ya lo llamó antes.
     */
    @Test
    fun csvLogger_close_isIdempotent() {
        // Arrange
        logger.open()

        // Act + Assert — dos cierres consecutivos no deben lanzar excepción
        logger.close()
        logger.close()
        // Si llegamos acá sin excepción, el test pasa
    }

    /**
     * Verifica que isLogging es true después de open().
     *
     * Qué testea (estado observable): que el caller puede preguntar si el logger
     * está activo antes de llamar write(). Esto es útil para diagnóstico y
     * para decisiones condicionales en el Service.
     */
    @Test
    fun csvLogger_isLogging_afterOpen_isTrue() {
        // Arrange + Act
        logger.open()

        // Assert
        assertTrue(
            "isLogging debe ser true después de open() — el logger está activo",
            logger.isLogging
        )
    }

    /**
     * Verifica que isLogging es false después de close().
     *
     * Qué testea (estado observable): que el logger reporta correctamente
     * que ya no está activo después del cierre. Sin esto, el caller podría
     * intentar escribir en un archivo ya cerrado.
     */
    @Test
    fun csvLogger_isLogging_afterClose_isFalse() {
        // Arrange
        logger.open()

        // Act
        logger.close()

        // Assert
        assertFalse(
            "isLogging debe ser false después de close() — el logger está inactivo",
            logger.isLogging
        )
    }

    /**
     * Verifica que el nombre del archivo sigue el patrón esperado.
     *
     * Qué testea (contrato de naming): que el archivo se puede identificar
     * fácilmente en el directorio de logs. El patrón "raw_accel_YYYYMMDD_HHmmss.csv"
     * garantiza que: (1) los archivos están ordenados cronológicamente por nombre,
     * (2) no se sobrescriben sesiones anteriores, (3) el data scientist puede
     * saber de cuándo son los datos sin abrir el archivo.
     *
     * Por qué regex y no assertEquals:
     *   El timestamp cambia con cada ejecución del test. Verificar el patrón
     *   es más robusto que verificar el timestamp exacto.
     */
    @Test
    fun csvLogger_filename_containsTimestamp() {
        // Arrange + Act
        val path = logger.open()

        // Assert
        assertNotNull("open() debe retornar el path del archivo", path)
        assertTrue(
            "El nombre del archivo debe seguir el patrón 'raw_accel_YYYYMMDD_HHmmss.csv'. " +
                    "Path actual: '$path'",
            path!!.matches(Regex(".*raw_accel_\\d{8}_\\d{6}\\.csv"))
        )
    }

    /**
     * Verifica que una segunda llamada a open() retorna null si ya está abierto.
     *
     * Qué testea (comportamiento de exclusividad): que el logger no abre dos archivos
     * simultáneos. Si open() se llamara dos veces (ej: bug en onMonitoringStart()),
     * el primer archivo quedaría sin cerrar y perdería datos.
     *
     * Retornar null (en lugar de lanzar excepción) permite al caller manejar el
     * caso sin try/catch — simplemente ignora el null si ya tiene el path original.
     */
    @Test
    fun csvLogger_open_secondCall_returnsNull() {
        // Arrange — primer open exitoso
        val firstPath = logger.open()
        assertNotNull("Precondición: el primer open() debe retornar un path", firstPath)

        // Act — segundo open mientras ya está abierto
        val secondPath = logger.open()

        // Assert
        assertNull(
            "El segundo open() debe retornar null — el logger ya está activo. " +
                    "Abrir un segundo archivo sin cerrar el primero causaría pérdida de datos.",
            secondPath
        )
    }
}
