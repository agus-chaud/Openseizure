# SeizureGuard — Explicaciones de cada fase
> Escrito para data scientists que nunca tocaron Android/Kotlin.
> Si entendés Python y modelos de ML, vas a entender esto.

---

> ## ⚠️ IMPORTANTE — Arquitectura actualizada (2026-06-05)
>
> Gran parte de este documento describe una arquitectura **vieja** donde **el reloj corría la
> inferencia con TensorFlow Lite**. **Eso ya no es así.** Lo vigente:
>
> - El reloj **NO infiere**. Es solo un sensor inteligente: captura el acelerómetro a 25Hz, lo
>   convierte a milli-g, y se lo manda a la app **OpenSeizureDetector V5.0** por Bluetooth
>   (Wear Data Layer). Recibe de vuelta el estado de alarma y vibra.
> - La inferencia corre **en el teléfono, dentro de la app OSD**, con **PyTorch ExecuTorch** y el
>   modelo **`deepEpiCnn_2026_01_24_Run24.pte`** — NO con TFLite ni `cnn_v024.tflite`.
> - El tensor real del modelo es **`(1, 1, 750)`** (este doc dice `(1, 750, 1)` en varios lados:
>   está desactualizado).
> - Este repo es **un solo módulo (`:wear`)**; no hay app de teléfono propia.
>
> Leé las secciones de abajo entendiendo que **todo lo que diga "el reloj infiere" o "TFLite"
> es historia, no el estado actual.** La parte de captura de sensores (TYPE_ACCELEROMETER,
> 25Hz, ring buffer, milli-g) **sí sigue vigente** — eso es lo que el reloj realmente hace.

---

## El problema que estamos resolviendo

Las convulsiones tónico-clónicas nocturnas son las más peligrosas de todas. La persona está dormida, no puede pedir ayuda, y el cuidador tampoco está despierto. Los dispositivos comerciales que detectan esto cuestan entre USD 500 y USD 2000. SeizureGuard es la alternativa open-source: toma datos del acelerómetro de un Samsung Galaxy Watch 8, los pasa por un modelo CNN, y si detecta una convulsión, despierta al cuidador en el teléfono.

Bien. Ahora la parte que le complica la cabeza a cualquiera que viene del mundo de datos: ¿por qué no es simplemente un script de Python que corre el modelo? Porque el modelo tiene que correr en una CPU de reloj inteligente con 300 mAh de batería, sin internet, sin GPU, sin servidor, a las 3 de la mañana, durante 8 horas continuas, y si se equivoca para el lado del falso negativo, alguien puede morir. Cada decisión de arquitectura de este proyecto existe por esa restricción. No es capricho de ingeniero — es necesidad real.

---

## Antes de arrancar — el stack

Si venís de Python, cada tecnología de este proyecto tiene un equivalente que ya conocés. La diferencia es que acá todo está tipado en compilación, todo corre en hardware real, y "el proceso se cayó" puede significar que alguien no recibió una alarma médica.

| Tecnología Android/Kotlin | Para qué sirve en SeizureGuard | Equivalente Python que ya conocés |
|--------------------------|-------------------------------|----------------------------------|
| **Kotlin 2.0.21** | Lenguaje principal — tipado fuerte, null-safe por diseño | Python con type hints *estrictos* y enforcement en compilación, no en runtime |
| **Gradle + Version Catalog** | Gestión de dependencias y build | pip + pyproject.toml |
| **Coroutines (suspend fun)** | Concurrencia sin bloquear el hilo de UI | asyncio — `async/await` de Python |
| **TFLite 2.14.0** | Runtime de inferencia del CNN en el reloj | `tflite-runtime` o `onnxruntime` en Python |
| **SensorManager** | API del OS para leer el acelerómetro a 25Hz | `sensor.subscribe(callback, interval_hz=25)` en cualquier librería de IoT |
| **Room** | Base de datos SQLite con ORM (para el módulo phone) | SQLAlchemy |
| **Robolectric** | Tests de Android que corren en JVM sin dispositivo | pytest con mocks del sistema operativo |
| **KSP** | Generador de código en compilación (para Room) | Como Cython o codegen — traduce annotations a código |
| **Wear Data Layer** | Canal Bluetooth watch → phone | gRPC o WebSocket entre dos procesos sin internet |

¿Se entiende la tabla? Bien. Ahora arrancamos.

---

## Fase 0: Setup — "Antes de escribir una línea de lógica"

### 0.1 — Estructura multi-módulo

La primera decisión del proyecto fue: ¿una app o dos?

El watch y el teléfono son dispositivos completamente distintos. El watch corre Wear OS y tiene SensorManager, acelerómetro, y TFLite. El teléfono corre Android normal y tiene SMS, pantalla grande, y Room DB para el historial. Si los mezclás en un solo módulo, empiezan a compartir dependencias que no tienen nada que ver entre sí — las librerías del teléfono engordan el APK del reloj, y empezás a tener problemas de compatibilidad en cascada.

La solución: dos módulos en el mismo repositorio.

```
OpenSeizure/
├── wear/    ← app del reloj: inferencia, sensores, alerta
└── phone/   ← app del teléfono: alarma, SMS, historial
```

Si esto te suena familiar, es porque es exactamente lo mismo que hacer `training/` y `serving/` en el mismo repo. Mismo dominio, mismo git, diferente stack. El módulo `wear` hace la inferencia; el módulo `phone` presenta los resultados. Se comunican por Bluetooth a través de la Wear Data Layer API (Fase 3.1).

Cada módulo tiene su propio `build.gradle.kts` — su `requirements.txt` personal. Cada uno se compila independientemente. Y cuando deployás, instalás dos APKs separados: uno en el watch, uno en el teléfono.

### 0.2 — Stack de dependencias

Dos decisiones de setup que vale la pena entender ahora porque van a aparecer en todos los archivos de configuración.

**Version Catalog — un solo lugar para todas las versiones**

Con dos módulos, la misma dependencia (Coroutines, por ejemplo) aparece en dos `build.gradle.kts`. Sin centralizar, si tenés que actualizar Coroutines de 1.8.1 a 1.9.0, editás dos archivos. Con el Version Catalog (`gradle/libs.versions.toml`), editás uno solo. Es el `pyproject.toml` de Android — un lugar único para versiones, y Android Studio tiene autocompletado completo para las referencias `libs.xxx`.

**KSP y nunca kapt — la diferencia que importa en Kotlin 2.0**

Room necesita generar código SQL a partir de tus data classes. En Python, Pydantic genera validadores a partir de type hints. Room hace algo parecido, pero en compilación. La herramienta que hace esa generación se llama procesador de anotaciones.

Hay dos opciones: `kapt` (el viejo) y `KSP` (el nuevo). `kapt` está deprecated en Kotlin 2.0 — convierte todo el código Kotlin a Java stubs antes de procesarlo, lo que en Kotlin 2.0 rompe en casos edge y es 2x a 10x más lento. KSP entiende el AST de Kotlin directamente. La regla es simple: nunca agregar `id("kotlin-kapt")` a este proyecto. Siempre `alias(libs.plugins.ksp)`.

**`aaptOptions { noCompress += "tflite" }` — la línea que evita un crash a las 3am**

Cuando Android empaqueta la app en un APK (básicamente un ZIP), comprime la mayoría de los assets para reducir el tamaño de descarga. Eso incluye el modelo CNN de 204KB. El problema: `TFLiteModelLoader` usa memory-mapping (`mmap`) para cargar el modelo — le dice al OS que trate el archivo como si fuera memoria RAM directamente, sin leerlo byte a byte. Memory-mapping solo funciona sobre archivos sin comprimir. Si el `.tflite` está comprimido en el ZIP, `channel.map()` falla en runtime con un `IOException` críptico.

Esta única línea en el `build.gradle.kts` le dice al packaging tool que deje el `.tflite` sin comprimir:

```kotlin
aaptOptions {
    noCompress += "tflite"
}
```

El detalle perverso: los tests Robolectric pasarían igual aunque falte esta línea, porque Robolectric carga assets del disco directamente, no del APK. El fallo aparecería SOLO en el dispositivo real. Por eso en Fase 0.4 se agregó un test instrumented específicamente para detectar este escenario.

### 0.3 — TFLiteModelLoader

Este es el primer componente de ML del proyecto. Su única responsabilidad: cargar el archivo `.tflite` del APK a memoria de forma segura. Nada más.

**Por qué memory-mapping y no leer los bytes directamente**

Memory-mapping (`mmap`) le dice al OS que trate el archivo como si fuera un rango de memoria virtual. La CPU accede solo a las páginas que necesita, el OS gestiona el caché. Para un modelo de 204KB es casi instantáneo y no duplica el uso de memoria — el buffer no es una copia del archivo, ES el archivo visto como memoria.

```
APK (almacenamiento del reloj)
  └── assets/cnn_v024.tflite   ← sin comprimir (aaptOptions)
           │
           │  AssetManager.openFd()
           ▼
  AssetFileDescriptor
           │
           │  FileInputStream.channel.map()  ← mmap
           ▼
  MappedByteBuffer   ← el modelo en memoria, listo para TFLite
```

**Por qué `suspend fun` y no una función regular**

En Android hay un único "main thread" que maneja la UI y los sensores. Si bloqueás ese thread por más de ~5 segundos leyendo un archivo, el OS muestra "La aplicación no responde" (ANR) y puede matarla. Las Kotlin Coroutines son la solución idiomática. `suspend fun` con `withContext(Dispatchers.IO)` garantiza que la carga corre en un thread de I/O sin bloquear el main thread.

Si venís de Python, esto es exactamente `asyncio`:

```python
# Python asyncio
async def load_model(path: str) -> bytes:
    async with aiofiles.open(path, 'rb') as f:
        return await f.read()

# Kotlin equivalente
suspend fun load(context: Context, modelFileName: String): MappedByteBuffer =
    withContext(Dispatchers.IO) { /* corre en thread de I/O */ }
```

**Por qué `throw ModelLoadException` y no retornar null**

Esta es una app médica. Un fallo silencioso no es una opción — si el modelo no cargó, el Service no puede monitorear, y el usuario no lo sabe. Retornar `null` permite que el caller se olvide de verificarlo y la app arranca sin modelo, infiriendo basura o crasheando más tarde con un mensaje confuso. Una excepción FUERZA al caller a tomar una decisión explícita. Eso es exactamente lo que queremos.

**El fixture de 144 bytes — testear el mecanismo, no el contenido**

Los tests del loader usan un modelo TFLite mínimo generado con Python + flatbuffers. Son 144 bytes con un FlatBuffer v3 válido pero vacío — sin operadores, sin tensores. No es el CNN de producción. Sirve para verificar que el pipeline `AssetManager → FileChannel → MappedByteBuffer` funciona correctamente. Si el test falla, sabés que el problema es el loader, no el contenido del modelo. Separación de responsabilidades desde el principio.

### 0.4 — ADB Wireless

El Samsung Galaxy Watch 8 no tiene puerto USB expuesto. El único conector físico es el cargador magnético, que no es un puerto de datos. Para conectar el reloj al PC para desarrollo se usa ADB sobre WiFi — ambos dispositivos en la misma red local.

```
                    Red WiFi local
   PC ─────────────────────────────── Samsung Watch 8
   adb connect 192.168.x.x:5555       Depuración inalámbrica ON
```

Hay dos tipos de tests en este proyecto y es importante entender la diferencia:

```
Tests Robolectric (src/test/)          Tests instrumented (src/androidTest/)
────────────────────────────────       ────────────────────────────────────────
Sin dispositivo, corre en la PC        Requiere Samsung Watch 8 conectado
./gradlew :wear:test                   ./gradlew :wear:connectedAndroidTest
Carga assets del DISCO                 Carga assets del APK INSTALADO en el reloj
Testa el mecanismo de carga            Testa que el .tflite no quedó comprimido
Fixture de 144 bytes                   cnn_v024.tflite real (204KB)
```

El test instrumented `bufferMatchesExpectedSize()` verifica que `buffer.limit() == 209_456`. Si en algún futuro refactor alguien elimina la línea `noCompress += "tflite"`, todos los tests Robolectric van a seguir pasando, pero este test va a fallar con el watch conectado. Es el safety net del packaging. Es el test que detecta el bug antes de que sea un problema a las 3am.

Fase 0 está completa. La base está lista. Ahora el vigilante empieza a tomar forma.

---

## Fase 1.1 — ForegroundService — "El vigilante nocturno"

Un ForegroundService en Android es la solución al problema de "cómo hago que mi app siga corriendo cuando nadie la está mirando". Sin un ForegroundService, Android puede matar cualquier app que está en background para liberar memoria — es el comportamiento normal y deseable para apps regulares. Para SeizureGuard, que necesita monitorear 8 horas continuas mientras el usuario duerme, eso sería fatal.

Un ForegroundService es como un daemon de Linux — un proceso que corre en segundo plano indefinidamente, que el OS trata diferente al resto, y que no puede ser matado silenciosamente. La única diferencia con un daemon: Android exige que el Service tenga una notificación visible en la barra de estado mientras está activo. Es la forma de decirle al usuario "esta app está usando recursos en background, vos lo aprobaste".

**Por qué `START_STICKY`**

`onStartCommand()` devuelve una constante que le dice al OS qué hacer si mata el Service por falta de memoria:

- `START_NOT_STICKY`: no lo reinicia — el monitoreo queda muerto para siempre.
- `START_STICKY`: lo reinicia automáticamente con `intent = null`.
- `START_REDELIVER_INTENT`: lo reinicia con el último Intent reenviado.

`START_STICKY` es la elección correcta. Si el OS mata el Service en condiciones extremas (muy raro con WakeLock activo, pero posible), Android lo reinicia solo. El monitoreo nocturno no puede quedar detenido sin que el usuario lo sepa.

**Por qué `setOngoing(true)` — la notificación que no se puede descartar**

Sin `setOngoing(true)`, el usuario puede deslizar la notificación para cerrarla. El problema: descartar la notificación de un ForegroundService en Android DETIENE el Service. Un usuario que descarta la notificación a las 3am mientras cambia de posición para dormir detiene el monitoreo sin saberlo. `setOngoing(true)` hace que la notificación no sea descartable. Protección silenciosa.

**Por qué `IMPORTANCE_LOW` y no `IMPORTANCE_HIGH`**

`IMPORTANCE_HIGH` haría sonido y mostraría un banner de alerta — interrumpiría el sueño del cuidador cada vez que inicia el monitoreo. `IMPORTANCE_LOW` es visible en el notification shade del reloj pero no hace ruido ni vibración. Para una notificación persistente de servicio que solo dice "monitoreando", es el nivel correcto. Las alertas reales usarán vibración separada en Fase 2.5.

**Por qué factory methods en `companion object`**

```kotlin
companion object {
    fun startIntent(context: Context) = Intent(context, SeizureMonitorService::class.java)
        .apply { action = ACTION_START }
    fun stopIntent(context: Context)  = Intent(context, SeizureMonitorService::class.java)
        .apply { action = ACTION_STOP }
}
```

Si cada parte de la app que quiere arrancar el Service construye el Intent manualmente, y en algún refactor renombrás la clase `SeizureMonitorService`, todos esos callers fallan en runtime — el Intent apunta a una clase que ya no existe. Con los factory methods, hay un único lugar donde se referencia `SeizureMonitorService::class.java`. Si la clase se renombra y olvidás actualizar algo, el error aparece en compilación, no a las 3am.

**Por qué `SupervisorJob`**

El Service usa un `CoroutineScope` para lanzar coroutines (la lógica de sensores e inferencia correrá acá). `SupervisorJob` hace que si una coroutine falla (por ejemplo, la inferencia TFLite lanza una excepción inesperada), las demás coroutines del scope NO se cancelan. El Service sigue monitoreando. Sin `SupervisorJob`, una excepción en una coroutine hija cancela todas las demás — el Service queda vivo pero sin ninguna coroutine activa, sin monitoreo, sin error visible.

```python
# Equivalente Python asyncio:
# SupervisorJob ≈ asyncio.TaskGroup con return_exceptions=True
# Si una tarea falla, las otras siguen corriendo
```

El UI toggle de la Fase 1.1 usa estado local con `mutableStateOf` — lo suficiente para que la pantalla refleje si el monitoreo está activo. En Fase 2.x esto se va a mover a un ViewModel con arquitectura limpia. Por ahora, cumple su función.

Con el ForegroundService en pie, el vigilante nocturno existe. Pero todavía está sentado en la silla sin hacer nada. Fase 1.2 le da la primer herramienta: mantener el CPU despierto.

---

## Fase 1.2 — WakeLock — "Mantener el CPU despierto"

Sin WakeLock, el CPU del reloj entra en modo de suspensión profunda a los pocos minutos de inactividad. La pantalla se apaga, la CPU baja a frecuencia mínima, y el SensorManager deja de entregar muestras. El ForegroundService existe, pero está dormido. Sin detección.

**Por qué `PARTIAL_WAKE_LOCK` y no `FULL_WAKE_LOCK`**

Android tiene varios tipos de WakeLock:

| Tipo | CPU | Pantalla |
|------|-----|----------|
| `PARTIAL_WAKE_LOCK` | Activa | Puede dormir |
| `FULL_WAKE_LOCK` (deprecated) | Activa | Encendida plena |

Para monitoreo nocturno, la pantalla del reloj DEBE apagarse. El usuario está durmiendo. Una pantalla encendida toda la noche destruiría la batería de 300 mAh del Galaxy Watch 8 en 1-2 horas. Solo necesitamos la CPU activa para que el SensorManager capture acelerómetro y TFLite infiera. `PARTIAL_WAKE_LOCK` es el único tipo correcto para este caso de uso.

```python
# Analogía Python:
# PARTIAL_WAKE_LOCK ≈ mantener un proceso corriendo en background
#                     mientras la pantalla del sistema está apagada
# FULL_WAKE_LOCK    ≈ nunca poner el monitor en modo ahorro de energía
```

**Por qué timeout de 10 horas y no `acquire()` indefinido**

`acquire()` sin argumentos crea un WakeLock que NUNCA expira solo. Si el OS mata el Service sin llamar `onDestroy()` (raro pero posible), el WakeLock queda activo para siempre. El reloj nunca entra en suspensión profunda. La batería muere. El usuario despierta sin monitoreo Y sin batería.

`acquire(10 * 60 * 60 * 1000L)` configura un timeout de 10 horas. En el camino normal, `onDestroy()` libera el lock mucho antes. En el camino anómalo, el OS lo libera automáticamente después de 10h. Las 10h no son arbitrarias: 8h de monitoreo nocturno + 2h de margen. El timeout nunca dispara en uso normal.

**Por qué verificar `isHeld` antes de `release()`**

```kotlin
private fun releaseWakeLock() {
    wakeLock?.let {
        if (it.isHeld) it.release()  // sin esto: RuntimeException
    }
    wakeLock = null
}
```

Llamar `release()` sobre un WakeLock que ya fue liberado lanza `RuntimeException: WakeLock under-locked`. Esto podría ocurrir si el timeout de 10h expira antes de que `onDestroy()` se llame. La verificación `isHeld` hace que `releaseWakeLock()` sea idempotente — se puede llamar múltiples veces sin explotar.

```python
# Python equivalente:
import threading
lock = threading.Lock()
if lock.locked():    # equivale a isHeld
    lock.release()   # safe
```

**Por qué liberar el WakeLock en `onDestroy()` y no en `ACTION_STOP`**

`ACTION_STOP` llama `stopSelf()`, que PROGRAMA la destrucción del Service pero no la ejecuta inmediatamente. `onDestroy()` es el callback garantizado que se ejecuta cuando el Service efectivamente termina. Si liberas el WakeLock en `ACTION_STOP`, hay una ventana donde el Service sigue corriendo pero sin WakeLock — la CPU puede dormir mientras el Service aún no terminó.

Con el WakeLock activo, el vigilante está despierto. Ahora Fase 1.3: empezar a escuchar.

---

## Fase 1.3 — SensorManager — "Empezar a escuchar"

Arrancamos con `onMonitoringStart()` vacío. Un Service que arranca, pone la notificación, agarra el WakeLock... y no hace NADA. Fantástico. Tenemos un vigilante nocturno que se sienta en la silla y se queda dormido. Eso era Fase 1.1 y 1.2.

Fase 1.3 es donde el vigilante empieza a escuchar.

### El SensorManager — la capa de hardware

`SensorManager` es la API de Android que habla con el hardware del reloj. Vos le decís: *"registrame un listener para el acelerómetro, a 25Hz"* — y cada vez que el sensor tiene un dato nuevo, Android te llama `onSensorChanged()`.

```
Hardware (chip de movimiento del Watch 8)
         │
         │  cada 40ms
         ▼
SensorManager del OS
         │
         │  llama a tu listener
         ▼
onSensorChanged(event)   ← acá llegás vos
         │
         ▼
onAccelerometerSample(x, y, z)   ← stub por ahora, Fase 1.5 lo llena
```

Analogía Python que te va a quedar grabada:

```python
# Lo que hicimos en Fase 1.3 es EXACTAMENTE esto:
sensor.on_change(callback=procesar_muestra, frequency_hz=25)

# El onDestroy() es esto:
sensor.unsubscribe()
```

Es una suscripción a un stream de datos. Nada más. La diferencia es que en Android el "stream" es el hardware del reloj, corre 24/7, y tiene consecuencias en batería y estabilidad si lo manejás mal.

### Por qué `TYPE_ACCELEROMETER` y no `TYPE_LINEAR_ACCELERATION`

Este es el punto más importante de toda la fase, y si lo hacés mal vas a tener un modelo que da basura sin entender por qué.

`TYPE_ACCELEROMETER` te da la aceleración **incluyendo la gravedad**. Cuando el reloj está quieto sobre la mesa, te da `z ≈ 9.8 m/s²` = ~1000 milli-g. Siempre. En reposo. Eso es la Tierra tirando para abajo.

`TYPE_LINEAR_ACCELERATION` le resta la gravedad via el giroscopio interno. Cuando el reloj está quieto, te da `x=0, y=0, z=0` = ~0 milli-g. Solo el movimiento real.

El modelo DeepEpiCnn Run24 fue entrenado con datos de Garmin y PineTime que incluyen la gravedad (confirmado por Graham Jones, creador de OpenSeizureDetector). El modelo ESPERA ver ~1000 milli-g en reposo. Si le das `TYPE_LINEAR_ACCELERATION`, la magnitud en reposo es ≈0 — el modelo recibe una señal fuera de su distribución de entrenamiento. El resultado: falsos negativos, falsas alarmas, predicciones inválidas. Y lo peor — todo parecería funcionar porque el modelo compila, carga, infiere. El error es silencioso.

```
TYPE_ACCELEROMETER en reposo:    [0.1, 0.2, 9.8] m/s²  → magnitud ≈ 1000 milli-g ← lo que el modelo espera
TYPE_LINEAR_ACCELERATION:        [0.0, 0.0, 0.0] m/s²  → magnitud ≈ 0 milli-g    ← distribution shift
```

Loco, es como normalizarle mal los features a tu modelo. El modelo entrenó con datos con gravedad incluida. Si en producción le mandás datos sin gravedad, el modelo no tira error. Va a predecir. Mal. Y vos no vas a saber por qué.

### El período de muestreo: 40,000 microsegundos

Android no trabaja en Hz. Trabaja en microsegundos. 25Hz = 1/25 segundos = 0.04 segundos = 40 milisegundos = **40,000 microsegundos**.

Tiene constantes como `SENSOR_DELAY_NORMAL` (~200ms = 5Hz) o `SENSOR_DELAY_GAME` (~20ms = 50Hz), pero ninguna da 25Hz exacto. Entonces hardcodeamos el número.

Ojo — es un *hint* al OS, no una garantía. Android puede variarlo levemente. La frecuencia real la vamos a medir en Fase 1.6 cuando hagamos el CSV logging. Si hay drift, lo detectamos ahí.

### El orden en `onDestroy()` — el detalle que separa al junior del senior

```kotlin
stopSensorCollection()   // 1
releaseWakeLock()        // 2
serviceScope.cancel()    // 3
super.onDestroy()        // 4
```

Por qué este orden y no otro: `onSensorChanged()` puede llegar en cualquier thread del OS, en cualquier momento. Si vos cancelás el `serviceScope` primero, y después llega un callback del sensor que intenta lanzar una coroutine en ese scope... `IllegalStateException`. El Service revienta en silencio a las 3am.

Primero parás la fuente de datos. Después liberás recursos. Después cancelás el scope. ¿Se entiende?

---

## Fase 1.4 — Samsung Health SDK (opcional) — "La herramienta premium que no necesitás todavía"

Existe un SDK llamado **Samsung Privileged Health SDK** que da acceso a sensores del Galaxy Watch con mayor privilegio del que ofrece el `SensorManager` estándar de Android. Con él podés leer frecuencia cardíaca con mayor frecuencia de actualización, SpO₂ en modo continuo, y —teóricamente— acelerómetro con acceso más directo al hardware sin pasar por el filtro de sensor fusion del OS.

Suena tentador. Pero en el MVP, lo salteamos. Y vale la pena entender por qué.

**Lo que `TYPE_ACCELEROMETER` ya hace bien**

Las convulsiones tónico-clónicas son movimientos de alta amplitud y frecuencia relativamente baja (1-3 Hz). Son señales fuertes en el acelerómetro — no son sutiles. El modelo DeepEpiCnn Run24 fue entrenado con datos capturados con acelerómetro estándar con gravedad incluida (el mismo que usamos nosotros: `TYPE_ACCELEROMETER` a 25Hz, salida en milli-g).

En términos de Python: el modelo aprendió sobre una distribución particular de features. Esa distribución fue generada por `TYPE_ACCELEROMETER` con magnitudes en milli-g. Cambiar el sensor —aunque sea "mejor"— es un distribution shift. El modelo no entrenó con esos datos. El resultado es impredecible hasta que se revalide.

Para el problema que estamos resolviendo en la Fase 1 del MVP, el SensorManager estándar es suficiente. La señal que necesitamos es fuerte. 25Hz alcanza. El modelo funciona sobre la señal cruda con gravedad.

**El costo real del Samsung Health SDK**

No es solo agregar una dependencia en `build.gradle.kts`. El proceso completo incluye:

- Solicitud formal de acceso a Samsung (proceso de aprobación que toma semanas o meses)
- Firma especial del APK con un certificado autorizado por Samsung
- Complejidad adicional en el build: la APK tiene que coincidir con el certificado autorizado para que el SDK funcione en el device
- Mantenimiento de dos paths de código: uno para relojes Samsung con el SDK aprobado, otro para cualquier otra cosa

En Python sería como reemplazar `pandas.read_csv()` por una API propietaria con rate limits, autenticación OAuth, y proceso de aprobación manual antes de poder correrlo localmente. Para el problema que tenés, `pandas` alcanza.

**Cuándo SÍ tiene sentido implementarlo**

En la **Fase 5**, cuando agreguemos frecuencia cardíaca (HR) y saturación de oxígeno en sangre (SpO₂) como features adicionales del modelo CNN, el Samsung Health SDK se vuelve necesario. El `SensorManager` estándar de Android entrega HR con una frecuencia de actualización muy baja para ser útil como feature temporal en el modelo. El SDK de Samsung da acceso a HR con resolución mucho mayor.

Pero ese es un problema de Fase 5. No de ahora.

**La regla de arquitectura que esto ilustra**

No agregues complejidad antes de que el problema la requiera. El MVP funciona sin el Samsung Health SDK. Cuando el modelo necesite features que solo ese SDK puede dar, entonces —y solo entonces— vale la pena el costo de implementarlo. Agregar esa complejidad hoy sería pagar una deuda técnica sin obtener ningún beneficio concreto en el modelo.

---

## Fase 1.5 — Ring Buffer — "El buffer de memoria del vigilante"

Llegamos al momento donde el pipeline empieza a tener sentido completo. El CNN v0.24 no analiza cada muestra del acelerómetro en aislamiento. No puede. No tiene forma de saber si un valor de magnitud de 2.3 m/s² es parte de una convulsión o de un movimiento brusco para acomodar la almohada.

Lo que el modelo DeepEpiCnn Run24 necesita es **una ventana de tiempo**: los últimos 30 segundos de movimiento, representados como 750 muestras continuas en milli-g. Solo con esa secuencia completa puede reconocer el patrón rítmico y de alta amplitud que caracteriza una convulsión tónico-clónica.

El componente que acumula esa ventana y la mantiene actualizada es el **ring buffer circular**.

**Por qué una ventana de 750 muestras**

El modelo fue entrenado con ventanas de 30 segundos capturadas a 25Hz:

```
30 segundos × 25 muestras/segundo = 750 muestras
```

El tensor de input del CNN tiene shape `(1, 750, 1)`:
- `1` → tamaño del batch (inferimos de a una ventana)
- `750` → timesteps (30 segundos de historia)
- `1` → features por timestep (la magnitud vectorial √(x²+y²+z²) en milli-g)

Si le pasás 749 muestras, el shape no coincide. Si le pasás 751, tampoco. El contrato es exacto.

**Qué es un ring buffer y cómo funciona**

Un ring buffer es un array de tamaño fijo que sobreescribe los datos más antiguos cuando está lleno. Tiene un puntero de escritura (`writeIndex`) que avanza circularmente.

```
Buffer con capacidad 5, antes de llenarse:
[1.2, 0.8, 2.1, _, _]  writeIndex=3, count=3

Agregamos 0.5 y 3.1:
[1.2, 0.8, 2.1, 0.5, 3.1]  writeIndex=0 (vuelve al inicio), count=5 → isFull

Agregamos 1.7 (overflow → sobreescribe el más antiguo):
[1.7, 0.8, 2.1, 0.5, 3.1]  writeIndex=1
```

El buffer nunca crece. Siempre ocupa exactamente `capacidad × 4 bytes` en memoria. Para 750 floats: **3,000 bytes**, siempre, durante toda la noche.

En Python, el equivalente exacto es:

```python
from collections import deque
buffer = deque(maxlen=750)
buffer.append(nueva_muestra)  # O(1): descarta el más antiguo si está lleno
```

**La diferencia entre `add()` y `snapshot()`**

El buffer tiene dos operaciones fundamentales:

- `add(value: Float)`: escribe una nueva muestra en la posición `writeIndex` y avanza el índice. Es O(1). Lo llama el `onSensorChanged()` cada 40ms.

- `snapshot(): FloatArray`: cuando el buffer está lleno (750 muestras acumuladas), genera una copia del contenido **en orden cronológico** — del más antiguo al más reciente. Es esta ventana ordenada la que se convierte en tensor para el CNN.

La palabra clave es **COPIA**. `snapshot()` no devuelve el array interno del buffer. Crea un `FloatArray` nuevo con 750 elementos. ¿Por qué?

Porque el sensor sigue escribiendo datos en un thread del OS mientras el CNN intenta inferir sobre la ventana en otro thread. Si `snapshot()` devolviera el array interno, este escenario sería posible:

```
Thread del sensor:     buffer[52] = nuevaMuestra   ← escribe mientras...
Thread de inferencia:  tensor[52] = buffer[52]      ← ...el CNN lee al mismo tiempo
                                                    → valor a medio escribir → NaN o basura
```

Con la copia: el tensor de inferencia es completamente independiente del buffer interno. El sensor puede seguir escribiendo sin afectar la inferencia en curso.

**Thread safety: el `synchronized` que evita la alarma falsa a las 3am**

El sensor entrega callbacks en un thread interno del OS. La inferencia va a correr en el `serviceScope` del ForegroundService (otro thread). Dos threads accediendo al mismo array sin coordinación es una **condición de carrera**.

Sin `synchronized`: un `add()` y un `snapshot()` ejecutándose en paralelo pueden leer valores a medio escribir. El CNN recibe un tensor corrupto. Puede devolver una probabilidad de convulsión basada en basura. Falsa alarma a las 3am, o peor: no detectar una convulsión real.

Con `synchronized(lock)`: solo un thread puede ejecutar `add()` o `snapshot()` a la vez. El bloqueo dura microsegundos (escribir o copiar 750 floats). No hay contención observable en la práctica.

**El gotcha del off-by-one en `snapshot()`**

El índice de inicio para leer el buffer en orden cronológico es `writeIndex` — la posición donde se va a escribir la próxima muestra, que también es la posición que contiene el dato más antiguo (ya fue sobreescrito por el ciclo anterior).

Un error de ±1 en este índice no tira ninguna excepción. El código compila. Los tests simples pasan. Pero el tensor tiene las muestras en orden incorrecto. El CNN infiere sobre una secuencia temporal desordenada. Los resultados son impredecibles.

Es exactamente el tipo de bug que solo aparece cuando conectás el modelo en Fase 2.1 y las probabilidades de convulsión parecen plausibles pero incorrectas — y no sabés si el problema es el preprocessing, el buffer, o el modelo.

**Por qué la magnitud se calcula en el Service y no en el buffer**

El `CircularBuffer` almacena `Float`. No sabe de dónde vienen esos floats. No sabe que representan magnitudes de acelerómetro.

La magnitud vectorial `√(x²+y²+z²)` se calcula en el `SeizureMonitorService`, dentro de `onAccelerometerSample(x, y, z)`, antes de llamar `accelerometerBuffer.add(magnitude)`.

Esto es separación de responsabilidades. El buffer es una estructura de datos genérica — almacena una secuencia de floats con semántica FIFO. Si el buffer supiera calcular magnitudes, estaría acoplado a la lógica del dominio (acelerómetro, ML). El día que el modelo cambie y necesite otra representación, tendrías que modificar el buffer — lo cual no tiene ningún sentido.

En Python: es la diferencia entre pasar `magnitude = np.sqrt(x**2 + y**2 + z**2)` al buffer vs que el buffer haga ese cálculo internamente.

**El puente hacia Fase 2**

Cuando el buffer se llena (750 muestras acumuladas), el Service llama a `onWindowReady(snapshot)`. Por ahora ese método está vacío — es un stub. El puente está tendido. En Fase 2.1, ese stub se llenará con el código que crea el tensor de TFLite, instancia el `Interpreter`, y hace la primera inferencia real.

El vigilante ya tiene memoria de los últimos 30 segundos. Ahora necesita la inteligencia para interpretarlos.

---

## Fase 1.6 — CSV Logging — "Ver los datos antes de confiar en ellos"

Tenemos el ForegroundService activo, el WakeLock que mantiene el CPU despierto, el SensorManager capturando a 25Hz, y el ring buffer acumulando ventanas de 30 segundos. Todo listo para conectar TFLite en Fase 2.1.

Pero espera. ¿Realmente estamos capturando a 25Hz? ¿O el OS entrega muestras a 23Hz, a 27Hz, con jitter variable? ¿Y `TYPE_ACCELEROMETER` realmente entrega ~1000 milli-g en reposo? ¿Cómo lo verificás?

Sin esta fase, estás **adivinando**. En una app médica, adivinar no alcanza.

**El problema concreto**

El período de 40,000µs que le pasamos al `SensorManager` es un *hint* al OS, no una garantía. Android puede variarlo según la carga del sistema, el estado del hardware, y la versión del reloj. Si la frecuencia real es 20Hz en lugar de 25Hz, el buffer se llena en 37.5 segundos en lugar de 30. Las ventanas no representan lo que el CNN espera.

Además, `TYPE_ACCELEROMETER` debería dar magnitud ≈1000 milli-g en reposo. Pero eso es teoría. ¿Qué pasa en el hardware real del Galaxy Watch 8 a las 3am cuando el CPU está bajo carga del WakeLock?

La única forma de responder estas preguntas es **mirar los datos reales**.

**Qué escribe el CSV**

Para cada muestra del sensor, el logger escribe una fila con cinco campos:

```
timestamp_ms, x, y, z, magnitude
1743644400123, 0.012, -0.008, 0.003, 0.015
1743644400163, 0.031, -0.015, 0.007, 0.035
...
```

El `timestamp_ms` es la marca temporal en milisegundos del momento en que `onSensorChanged()` recibió el evento. Con esos timestamps podés calcular la frecuencia real de muestreo.

**Dónde se guardan los archivos**

Los CSV se guardan en `getExternalFilesDir(null)/logs/` con nombre `raw_accel_YYYYMMDD_HHmmss.csv`. Por ejemplo: `raw_accel_20260402_230000.csv` para una sesión iniciada el 2 de abril de 2026 a las 23:00.

Este directorio es accesible directamente con `adb pull` sin necesitar root:

```bash
adb pull /sdcard/Android/data/com.seizureguard.wear/files/logs/ ./logs/
```

**El análisis en Python que confirma que todo funciona**

Una vez descargado el CSV, este script te da las métricas que importan:

```python
import pandas as pd

df = pd.read_csv("raw_accel_20260402_230000.csv")

# Frecuencia real de muestreo
df['delta_ms'] = df['timestamp_ms'].diff()
print("Frecuencia real:")
print(df['delta_ms'].describe())
# mean ≈ 40ms → correcto (25Hz)
# std < 5ms   → sin jitter problemático

# Magnitud en reposo
print(f"\nMagnitud media: {df['magnitude'].mean():.3f} milli-g")
# ≈ 1000 milli-g → TYPE_ACCELEROMETER funcionando (1g de gravedad en reposo)
```

Si `mean ≈ 40ms` y `std < 5ms`: el SensorManager entrega a 25Hz con jitter aceptable. Si `magnitude ≈ 0.0` con el reloj quieto: la resta de gravedad funciona correctamente en el hardware real. Recién ahí podés conectar TFLite encima con confianza.

**Por qué solo en `BuildConfig.DEBUG`**

En producción, el monitoreo corre 8 horas por noche. 25 muestras/segundo × 8h × 3600s = 720,000 filas de CSV. Eso es ~36 MB por noche, 1 GB por mes. En un reloj con 2-4 GB de storage, inaceptable. Además, 25 escrituras por segundo al sistema de archivos tienen impacto real en la batería.

`BuildConfig.DEBUG` es `true` en el build de desarrollo y `false` en el build de release. El compilador elimina el bloque `if (BuildConfig.DEBUG) { ... }` completamente del bytecode de producción. Cero overhead, cero riesgo de activación accidental.

**Por qué `BufferedWriter` y no `FileWriter` directo**

`FileWriter` sin buffer haría una llamada al sistema operativo (`syscall`) por cada `write()`. A 25Hz, eso es 25 syscalls por segundo. En 8 horas: **720,000 llamadas al sistema de archivos**. Cada syscall tiene overhead: cambio de contexto, escritura al flash del reloj, retorno al proceso.

`BufferedWriter` acumula los datos en un buffer en memoria (8KB por defecto, ≈160 filas de CSV). Solo hace la syscall cuando el buffer se llena — una vez cada ~6 segundos en lugar de 25 veces por segundo. **Reducción de ~150x en el I/O al flash**. Menos consumo de batería, menos desgaste del hardware de almacenamiento.

**El orden de cierre: por qué `csvLogger.close()` va ANTES de `releaseWakeLock()`**

`BufferedWriter` tiene datos en memoria que todavía no se volcaron a disco. El `flush()` que hace `close()` es una operación de I/O que puede tomar algunos milisegundos.

```
Estado al hacer onDestroy(): buffer tiene 50 filas sin volcar

Si csvLogger.close() va ANTES de releaseWakeLock():
  CPU activa → flush completa → los 50 filas llegan a disco → CSV completo ✓

Si releaseWakeLock() va ANTES de csvLogger.close():
  CPU puede entrar en suspensión profunda durante el flush
  → algunas filas nunca llegan a disco → CSV truncado o corrupto ✗
```

Primero terminás el trabajo (flush del CSV). Después liberás el CPU (WakeLock). El orden importa.

Ahora sí tenés datos reales verificados. Ahora sí podés conectar TFLite encima.

---

## El estado del pipeline hasta acá

La Fase 1 está completa (salvo la 1.4 opcional que se retoma en Fase 5). El vigilante tiene todo lo que necesita para capturar datos reales en milli-g, acumularlos en ventanas de 30 segundos verificadas, y pasarlos al CNN. Lo que sigue es la inteligencia: la inferencia TFLite, el preprocesamiento del tensor, y la máquina de estados que decide cuándo despertar al cuidador.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         SAMSUNG GALAXY WATCH 8                      │
│                                                                     │
│  ✅ Acelerómetro 3D (25Hz, TYPE_ACCELEROMETER, salida en milli-g)   │
│     X, Y, Z muestras cada 40ms                                      │
│            │                                                        │
│            ▼                                                        │
│  ✅ Magnitud vectorial: √(x² + y² + z²)   ← Fase 1.5               │
│            │                                                        │
│            ▼                                                        │
│  ✅ Ring Buffer circular (750 muestras = 30 segundos)  ← Fase 1.5   │
│     thread-safe, snapshot() devuelve copia independiente            │
│            │                                                        │
│            ▼                                                        │
│  ✅ CSV logging (timestamps + magnitud, solo DEBUG)   ← Fase 1.6    │
│     verificación de 25Hz y magnitud ≈ 0 en reposo                  │
│            │                                                        │
│            ▼                                                        │
│  [ ] Tensor input: shape (1, 750, 1)   ← Fase 2.2                  │
│            │                                                        │
│            ▼                                                        │
│  ✅ CNN v0.24 cargado en memoria (TFLiteModelLoader)                │
│  [ ] Interpreter instanciado y corriendo   ← Fase 2.1              │
│            │                                                        │
│            ▼                                                        │
│  [ ] Output: [prob_normal, prob_seizure]   ← Fase 2.3              │
│            │                                                        │
│            ▼                                                        │
│  [ ] Máquina de estados: OK → WARNING → ALARM   ← Fase 2.4         │
│            │                                                        │
│            ▼                                                        │
│  ✅ ForegroundService nocturno (8h, START_STICKY)                   │
│  ✅ WakeLock (PARTIAL, 10h timeout)                                 │
│  [ ] Vibración háptica en ALARM   ← Fase 2.5                       │
└─────────────────────────────────────────────────────────────────────┘
                               │
                    Wear Data Layer API
                 [ ] Fase 3 completa
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          ANDROID PHONE                              │
│  [ ] AlarmActivity (pantalla completa) + sirena   ← Fase 3.2       │
│  [ ] SMS automático al cuidador   ← Fase 3.3                       │
│  [ ] Historial de eventos en Room DB   ← Fase 3.5                  │
└─────────────────────────────────────────────────────────────────────┘
```

**Tests actuales: 41 pasando, todos verdes**

- 3 smoke tests del módulo (`WearModuleTest`)
- 3 tests del loader TFLite (`TFLiteModelLoaderTest`, Robolectric)
- 10 tests del ring buffer circular (`CircularBufferTest`, Robolectric) — incluyendo concurrencia
- 10 tests del CSV logger (`CsvLoggerTest`, Robolectric)
- 15 tests del ForegroundService + WakeLock + SensorManager (`SeizureMonitorServiceTest`, Robolectric)
- 4 tests instrumented de packaging del APK (`TFLiteModelLoaderInstrumentedTest`, requiere watch)

La Fase 1 completa del lado de la captura de datos. El próximo paso es la Fase 2: instanciar el `Interpreter` TFLite, construir el tensor de input a partir de la ventana del ring buffer, correr la primera inferencia real, e implementar la máquina de estados `OK → WARNING → ALARM` que decide cuándo activar la alarma.
