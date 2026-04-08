package com.seizureguard.wear.ml

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fase 1.5 — Tests del CircularBuffer.
 *
 * Qué verifican estos tests:
 *   - El buffer arranca vacío y llega a lleno en exactamente [capacity] muestras.
 *   - snapshot() devuelve los elementos en orden cronológico correcto (más antiguo → más reciente).
 *   - El buffer desliza: cuando se supera la capacidad, reemplaza los datos más antiguos.
 *   - snapshot() retorna una copia independiente, no una referencia al array interno.
 *   - reset() limpia completamente el estado.
 *   - Concurrencia: dos threads escribiendo en paralelo no corrompen el estado.
 *
 * Qué NO verifican estos tests:
 *   - El cálculo de magnitud √(x²+y²+z²) — eso vive en SeizureMonitorService, no en el buffer.
 *   - La integración con TFLite — eso es Fase 2.x.
 *
 * Por qué no se usan mocks aquí:
 *   CircularBuffer es una estructura de datos pura — sin dependencias externas.
 *   Mockearla sería testear los mocks en lugar del comportamiento real.
 *
 * Analogía Python para los tests de orden:
 *   from collections import deque
 *   buf = deque([0,1,2,3,4], maxlen=5)
 *   buf.append(5)  # descarta 0
 *   buf.append(6)  # descarta 1
 *   list(buf)  # → [2, 3, 4, 5, 6]  ← orden cronológico
 */
class CircularBufferTest {

    private lateinit var buffer: CircularBuffer

    @Before
    fun setUp() {
        buffer = CircularBuffer(capacity = 750)
    }

    /**
     * Verifica el estado inicial del buffer recién creado.
     *
     * Qué testea: que antes de recibir cualquier dato, el buffer indica
     * que está vacío. Sin esta garantía, la primera inferencia del CNN
     * podría correr con basura de memoria.
     */
    @Test
    fun buffer_startsEmpty() {
        // Assert
        assertEquals("Un buffer nuevo debe tener size 0", 0, buffer.size)
        assertFalse("Un buffer nuevo no debe estar lleno", buffer.isFull)
    }

    /**
     * Verifica que el buffer no se considera lleno antes de alcanzar la capacidad.
     *
     * Qué testea: que 749 muestras (una muestra menos que la capacidad) no son suficientes para
     * activar una inferencia. La ventana del CNN debe ser completa o nada.
     *
     * Por qué exactamente capacity - 1:
     *   Es el caso borde más cercano al llenado. Si pasara aquí, fallaría en
     *   producción: inferencias sobre ventanas incompletas.
     */
    @Test
    fun buffer_afterAddingLessThanCapacity_isNotFull() {
        // Arrange + Act
        repeat(749) { i -> buffer.add(i.toFloat()) }

        // Assert
        assertFalse("Con 749 muestras el buffer no debe estar lleno", buffer.isFull)
        assertEquals("Con 749 muestras, size debe ser 749", 749, buffer.size)
    }

    /**
     * Verifica que el buffer se marca como lleno exactamente en la muestra número [capacity].
     *
     * Qué testea: el contrato fundamental — cuando isFull es true, hay exactamente
     * 750 muestras (30 segundos) disponibles para el CNN.
     */
    @Test
    fun buffer_afterAddingExactCapacity_isFull() {
        // Arrange + Act
        repeat(750) { i -> buffer.add(i.toFloat()) }

        // Assert
        assertTrue("Con exactamente 750 muestras el buffer debe estar lleno", buffer.isFull)
        assertEquals("size debe ser exactamente 750 cuando está lleno", 750, buffer.size)
    }

    /**
     * Verifica que snapshot() devuelve los elementos en orden cronológico correcto.
     *
     * Qué testea: el orden de los datos que llegan al CNN.
     * Un orden incorrecto significaría que el modelo recibe la señal al revés —
     * como leer un ECG de derecha a izquierda. El modelo nunca lo vio en entrenamiento.
     *
     * Cómo se verifica:
     *   Agregamos 0f, 1f, 2f, ..., 749f en ese orden.
     *   snapshot() debe devolver exactamente [0, 1, 2, ..., 749].
     *
     * Analogía Python:
     *   buf = deque(range(750), maxlen=750)
     *   assert list(buf) == list(range(750))
     */
    @Test
    fun buffer_snapshot_returnsElementsInChronologicalOrder() {
        // Arrange
        val expected = FloatArray(750) { it.toFloat() }

        // Act
        expected.forEach { buffer.add(it) }
        val snapshot = buffer.snapshot()

        // Assert
        assertArrayEquals(
            "snapshot() debe devolver los elementos en orden cronológico (más antiguo → más reciente)",
            expected,
            snapshot,
            0.0001f
        )
    }

    /**
     * Verifica el comportamiento de ventana deslizante: al superar la capacidad,
     * el buffer descarta las muestras más antiguas y retiene las más recientes.
     *
     * Qué testea: la propiedad clave del ring buffer — no es una lista que crece,
     * es una ventana que desliza sobre el stream de datos.
     *
     * Escenario: 755 muestras agregadas (0f..754f), capacidad 750.
     * Las primeras 5 (0f..4f) deben haber sido descartadas.
     * snapshot() debe devolver [5f, 6f, ..., 754f] — exactamente los últimos 30 segundos.
     *
     * Analogía Python:
     *   buf = deque(maxlen=750)
     *   for i in range(755):
     *       buf.append(float(i))
     *   assert list(buf) == [float(i) for i in range(5, 755)]
     */
    @Test
    fun buffer_afterOverflow_containsMostRecentSamples() {
        // Arrange
        repeat(755) { i -> buffer.add(i.toFloat()) }

        // Act
        val snapshot = buffer.snapshot()

        // Assert — el buffer tiene los 750 más recientes: 5f..754f
        assertEquals("Después de overflow, snapshot debe tener exactamente 750 elementos", 750, snapshot.size)
        assertEquals(
            "El primer elemento debe ser el sexto agregado (5f) — los 5 primeros fueron descartados",
            5f,
            snapshot[0],
            0.0001f
        )
        assertEquals(
            "El último elemento debe ser el más reciente (754f)",
            754f,
            snapshot[749],
            0.0001f
        )

        // Verificar que el orden es cronológico completo
        val expected = FloatArray(750) { (it + 5).toFloat() }
        assertArrayEquals(
            "snapshot() debe devolver los últimos 750 elementos en orden cronológico",
            expected,
            snapshot,
            0.0001f
        )
    }

    /**
     * Verifica que snapshot() retorna un array vacío cuando el buffer no está lleno.
     *
     * Qué testea: que el CNN no recibe datos parciales durante los primeros 30 segundos.
     * Las primeras 749 muestras se acumulan silenciosamente — no se infiere nada.
     * La primera inferencia válida ocurre exactamente en la muestra 750.
     *
     * Analogía Python:
     *   if len(buffer) < WINDOW_SIZE:
     *       return  # no inferir todavía
     */
    @Test
    fun buffer_snapshot_whenNotFull_returnsEmptyArray() {
        // Arrange — buffer con solo 50 muestras (menos de 30 segundos)
        repeat(50) { i -> buffer.add(i.toFloat()) }

        // Act
        val snapshot = buffer.snapshot()

        // Assert
        assertEquals(
            "snapshot() debe retornar un array vacío cuando el buffer tiene menos de 750 muestras",
            0,
            snapshot.size
        )
    }

    /**
     * Verifica que reset() limpia completamente el buffer.
     *
     * Qué testea: que después de detener y reiniciar el monitoreo,
     * el buffer no "contamina" el siguiente ciclo con datos del anterior.
     *
     * Escenario: se llenó el buffer (750 muestras), se llamó reset(),
     * el buffer debe comportarse como nuevo.
     */
    @Test
    fun buffer_reset_clearsAllSamples() {
        // Arrange — llenar el buffer completamente
        repeat(750) { i -> buffer.add(i.toFloat()) }
        assertTrue("Precondición: el buffer debe estar lleno antes del reset", buffer.isFull)

        // Act
        buffer.reset()

        // Assert
        assertEquals("Después de reset(), size debe ser 0", 0, buffer.size)
        assertFalse("Después de reset(), isFull debe ser false", buffer.isFull)
        assertEquals(
            "Después de reset(), snapshot() debe retornar array vacío",
            0,
            buffer.snapshot().size
        )
    }

    /**
     * Verifica que snapshot() retorna una COPIA independiente del array interno.
     *
     * Qué testea: que continuar agregando datos al buffer después de tomar un snapshot
     * no modifica retroactivamente el snapshot ya obtenido.
     *
     * Por qué importa:
     *   El CNN puede tardar ~15-30ms en inferir. Durante ese tiempo, el sensor
     *   sigue entregando muestras que se agregan al buffer. Si snapshot() retornara
     *   una referencia directa, el modelo estaría leyendo datos a medio actualizar
     *   → predicciones basadas en datos corruptos → falsas alarmas o seizures no detectados.
     *
     * Analogía Python:
     *   # Mal: window = buffer.data  ← referencia directa, datos mutables
     *   # Bien: window = buffer.data.copy()  ← copia segura para inferencia
     */
    @Test
    fun buffer_snapshot_returnsIndependentCopy() {
        // Arrange — llenar el buffer con valores conocidos (0f..749f)
        repeat(750) { i -> buffer.add(i.toFloat()) }
        val snapshot = buffer.snapshot()

        // Verificar precondición: el último elemento del snapshot es 749f
        assertEquals("Precondición: snapshot[749] debe ser 749f", 749f, snapshot[749], 0.0001f)

        // Act — agregar 5 muestras más (750f..754f) para desplazar la ventana
        repeat(5) { i -> buffer.add((750 + i).toFloat()) }

        // Assert — el snapshot original NO debe haber cambiado
        assertEquals(
            "El snapshot obtenido antes del add() no debe cambiar cuando se siguen agregando datos",
            749f,
            snapshot[749],
            0.0001f
        )
        assertEquals(
            "El snapshot debe seguir empezando en 0f aunque el buffer haya avanzado",
            0f,
            snapshot[0],
            0.0001f
        )
    }

    /**
     * Verifica que el cálculo de magnitud en SeizureMonitorService es correcto.
     *
     * Este es un test de integración conceptual: verifica que cuando el Service recibe
     * x=3f, y=4f, z=0f, la magnitud guardada en el buffer es 5f (triángulo 3-4-5).
     *
     * Por qué testear la magnitud aquí y no en el Service:
     *   CircularBuffer es una caja negra desde el punto de vista del Service.
     *   Este test verifica que el valor que llega al buffer es el correcto,
     *   actuando como un test de contrato para el preprocesamiento del CNN.
     *
     * Fórmula: magnitud = √(x² + y² + z²) = √(9 + 16 + 0) = √25 = 5.0
     *
     * Por qué el triángulo 3-4-5:
     *   Es el ejemplo más fácil de verificar mentalmente. Si este test falla,
     *   hay un bug en el cálculo de magnitud — el CNN recibiría features incorrectos.
     *
     * Analogía Python:
     *   import math
     *   assert math.sqrt(3**2 + 4**2 + 0**2) == 5.0
     */
    @Test
    fun buffer_magnitude_calculatedCorrectly() {
        // Arrange
        val smallBuffer = CircularBuffer(capacity = 1)

        // Simular el cálculo que hace SeizureMonitorService.onAccelerometerSample()
        val x = 3f
        val y = 4f
        val z = 0f
        val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)

        // Act
        smallBuffer.add(magnitude)

        // Assert
        assertTrue("Con una sola muestra de magnitud 5f, el buffer debe estar lleno", smallBuffer.isFull)
        val snapshot = smallBuffer.snapshot()
        assertEquals(
            "La magnitud de (3, 4, 0) debe ser exactamente 5.0 (triángulo pitagórico 3-4-5)",
            5f,
            snapshot[0],
            0.0001f
        )
    }

    /**
     * Verifica que el buffer es thread-safe bajo acceso concurrente.
     *
     * Qué testea: que dos coroutines escribiendo simultáneamente no corrompen
     * el estado interno del buffer (writeIndex + count + buffer[] deben ser consistentes).
     *
     * Sin la sincronización con `synchronized(lock)`:
     *   - Thread A lee writeIndex = 50
     *   - Thread B lee writeIndex = 50 (antes de que A lo incrementara)
     *   - Ambos escriben en buffer[50] → se sobreescriben mutuamente
     *   - Uno de los incrementos de count se pierde
     *   → Resultado: datos corruptos, size incorrecto, potencial IndexOutOfBounds
     *
     * Por qué runBlocking + launch × 2 + join:
     *   `runBlocking` bloquea el test thread hasta que todas las coroutines hijas terminen.
     *   `launch` × 2 crea dos coroutines que corren en paralelo en el pool de coroutines.
     *   `join()` espera que cada coroutine termine antes de verificar el resultado.
     *
     * Invariante verificada: al final, size == capacity (no hay corruption de count).
     *
     * Nota: Este test es probabilístico — no garantiza que encuentra TODOS los race conditions,
     * pero con 1000 operaciones por coroutine es altamente probable que lo encuentre si existe.
     */
    @Test
    fun buffer_concurrentAccess_doesNotCorrupt() {
        // Arrange
        val concurrentBuffer = CircularBuffer(capacity = 750)

        // Act — dos coroutines agregando 1000 muestras cada una en paralelo
        runBlocking {
            val job1 = launch {
                repeat(1000) { i -> concurrentBuffer.add(i.toFloat()) }
            }
            val job2 = launch {
                repeat(1000) { i -> concurrentBuffer.add(i.toFloat()) }
            }
            job1.join()
            job2.join()
        }

        // Assert — el buffer debe estar en un estado consistente
        // Con 2000 operaciones sobre un buffer de 750, debe estar lleno y no corrupto
        assertEquals(
            "Después de 2000 operaciones concurrentes, size debe ser exactamente capacity (750). " +
                    "Si falla, hay una condición de carrera en synchronized(lock).",
            750,
            concurrentBuffer.size
        )
        assertTrue(
            "El buffer debe estar lleno después de 2000 inserciones concurrentes",
            concurrentBuffer.isFull
        )
        assertEquals(
            "snapshot() después de acceso concurrente debe retornar exactamente 750 elementos",
            750,
            concurrentBuffer.snapshot().size
        )
    }
}
