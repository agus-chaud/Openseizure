# SeizureGuard — Registro de Decisiones de Arquitectura

Este archivo documenta **por qué** tomamos cada decisión técnica relevante.
La pregunta "por qué" es más valiosa que el "qué" — el código ya explica el qué.

> Para un data scientist trainee: esto es el log de experimentos, pero para decisiones de ingeniería. Cada entrada es una elección con alternativas consideradas y razones claras.

---

## ⚠️ CAMBIO DE ARQUITECTURA (2026-06-05) — leer antes que nada

Varias decisiones tempranas de este registro quedaron **SUPERADAS** por un giro de arquitectura.
Como en un buen registro de decisiones, **no las borramos** (el "por qué" histórico sigue siendo
valioso), pero quedan marcadas como superadas:

**Lo vigente hoy:**
- El reloj es solo un **data source Android Wear** compatible con `SdDataSourceAw` de la app
  **OpenSeizureDetector V5.0**. El reloj **NO infiere** — captura accel a 25Hz, lo manda en
  milli-g por `/osd/accel_data` y recibe `/osd/alarm_state`.
- La inferencia corre **en el teléfono, dentro de la app OSD**, con **PyTorch ExecuTorch** y el
  modelo **`deepEpiCnn_2026_01_24_Run24.pte`** (NO TFLite, NO `cnn_v024.tflite`). Tensor real:
  **`(1, 1, 750)`** (no `(1,750,1)`). Dependencia: `org.pytorch:executorch-android:1.0.1`.
- Este repo es **módulo único `:wear`**. El módulo `:phone` propio fue **retirado** (lo reemplaza
  la app OSD). **Actualización (DEC-051):** se reintroduce parcialmente un módulo `:phone` con otro
  propósito — puente de transporte reloj→OSD, no inferencia.

**Decisiones SUPERADAS por este cambio** (válidas como historia, no como estado actual):
- **DEC-002** (multi-módulo `:wear` + `:phone`) → hoy módulo único `:wear`.
- **DEC-006, 007, 010, 011, 017** (todo lo de `TFLiteModelLoader` / `Interpreter` TFLite) → la
  inferencia es de OSD con ExecuTorch; el loader y el modelo se borraron del repo.
- **DEC-040** (`PhoneCircularBuffer`) → el módulo `:phone` se retiró.
- Toda mención a tensor `(1, 750, 1)` → el real es `(1, 1, 750)`.

Fuente de verdad de lo nuevo: engram `architecture/seizureguard-executorch-api` y
`architecture/seizureguard-aw-contract`. Lo que sigue debajo es el historial original.

---

## DEC-001: Kotlin nativo sobre Progressive Web App (PWA)

**Fase:** 0.1 | **Fecha:** Marzo 2026

**Decisión:** Construir el módulo watch como app nativa Kotlin/Wear OS, no como PWA.

**Alternativa descartada:** Una PWA (web app en el navegador del reloj) con JavaScript + TF.js.

**Por qué la descartamos:**

| Criterio | PWA / TF.js | Kotlin nativo |
|----------|------------|---------------|
| Acceso al acelerómetro | No disponible en Wear OS browser | SensorManager completo |
| Foreground service (8h nocturnas) | Imposible desde el browser | ForegroundService nativo |
| Inferencia TFLite | TF.js: 200-500ms | TFLite C++: 15-30ms |
| Batería (8h continuo) | ~3-4h antes de morir | 8h+ (objetivo alcanzable) |
| Acceso a vibración y alarmas | Muy limitado | Control total |

**Conclusión:** Una app médica que monitorea toda la noche y toma decisiones de seguridad no puede vivir en un browser. El costo en rendimiento y acceso a hardware es inaceptable.

---

## DEC-002: Arquitectura multi-módulo `:wear` + `:phone`

**Fase:** 0.1 | **Fecha:** Marzo 2026

**Decisión:** Un solo repositorio con dos módulos Android separados.

**Alternativa descartada:** Un monolito (todo en un módulo) o dos repositorios separados.

**Razones:**

- El watch y el phone tienen SDKs distintos: Wear OS no tiene SMS, el phone no tiene SensorManager de reloj.
- Compilarlos por separado evita que dependencias del phone contaminen el módulo wear (menos APK, más performance).
- Un solo repositorio facilita la coordinación del protocolo Wear Data Layer (el contrato de mensajes está en un solo lugar).
- Analogía: como tener `model_training/` y `model_serving/` en el mismo repo — diferente stack, mismo dominio.

---

## DEC-003: Kotlin 2.0.21 + AGP 8.5.2 (en vez de versiones estables anteriores)

**Fase:** 0.1 | **Fecha:** Marzo 2026

**Decisión:** Usar Kotlin 2.0.21 y Android Gradle Plugin 8.5.2.

**Por qué importa la versión de Kotlin:**

Kotlin 2.0 introduce el nuevo Compose Compiler plugin como un plugin de Kotlin (`org.jetbrains.kotlin.plugin.compose`). En Kotlin 1.9.x este plugin no existe — en esa versión, el compiler de Compose es una dependencia del AGP, no de Kotlin. Si usamos Kotlin 2.0+ tenemos que usar el plugin nuevo. Si usamos Kotlin 1.9, lo configuramos diferente.

**El bug que evitamos:** Intentamos con Kotlin 1.9.24 + el plugin `org.jetbrains.kotlin.plugin.compose` — error en el Gradle sync porque ese plugin simplemente no existe en esa versión. Solución: upgradear a 2.0.21 donde el plugin es nativo.

**Regla práctica:** La combinación siempre tiene que ser consistente:
- Kotlin 2.0.x → `org.jetbrains.kotlin.plugin.compose` como plugin de Kotlin
- KSP: versión `{kotlin}-{patch}` = `2.0.21-1.0.28`
- AGP: 8.5.x es el mínimo para Kotlin 2.0

---

## DEC-004: KSP en lugar de kapt para generación de código

**Fase:** 0.2 | **Fecha:** Marzo 2026

**Decisión:** Usar KSP (Kotlin Symbol Processing) en vez de kapt para Room y cualquier otra librería que requiera generación de código.

**Contexto para data scientists:** kapt y KSP son herramientas que generan código Kotlin/Java en tiempo de compilación. Room los usa para generar el código SQL a partir de tus data classes. Es parecido a cómo Pydantic genera validadores a partir de type hints, pero en tiempo de compilación.

**Por qué KSP:**

- kapt está deprecated en Kotlin 2.0.
- KSP es entre 2x y 10x más rápido que kapt porque entiende el AST de Kotlin directamente.
- kapt convierte todo a Java stubs primero — con Kotlin 2.0 esto rompe en casos edge.
- Si Android Studio sugiere kapt en algún warning, ignorarlo: KSP es el camino correcto.

**Regla:** Nunca agregar `id("kotlin-kapt")` al proyecto. Siempre `alias(libs.plugins.ksp)`.

---

## DEC-005: Version Catalog (`libs.versions.toml`) para todas las dependencias

**Fase:** 0.2 | **Fecha:** Marzo 2026

**Decisión:** Centralizar todas las versiones en `gradle/libs.versions.toml`.

**Alternativa descartada:** Declarar versiones inline en cada `build.gradle.kts`.

**Por qué:**

- Con dos módulos, la misma dependencia (por ejemplo, Coroutines) aparece en ambos `build.gradle.kts`. Sin el catalog, si hay que actualizar Coroutines de 1.8.1 a 1.9.0, hay que editar dos archivos. Con el catalog, se edita solo `libs.versions.toml`.
- Es el estándar actual de Android (Gradle 8+). Android Studio tiene autocompletado para las referencias `libs.xxx`.
- Analogía: es como el `pyproject.toml` de Python — un lugar único para versiones.

```toml
# libs.versions.toml
[versions]
coroutines = "1.8.1"

[libraries]
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx",
                                name = "kotlinx-coroutines-android",
                                version.ref = "coroutines" }
```

```kotlin
// En cualquier build.gradle.kts
implementation(libs.kotlinx.coroutines.android)
```

---

## DEC-006: `aaptOptions { noCompress += "tflite" }`

**Fase:** 0.2 | **Fecha:** Marzo 2026

**Decisión:** Agregar esta configuración en `wear/build.gradle.kts`.

**Contexto:** Cuando Android empaqueta la app en un APK, comprime la mayoría de los assets (imágenes, archivos) usando ZIP para reducir el tamaño de descarga. Esto es un problema para archivos TFLite.

**El problema técnico:** `TFLiteModelLoader` usa `FileChannel.map()` para hacer memory-mapping del modelo — le dice al OS que trate el archivo como memoria directamente, sin leerlo byte a byte. Pero memory-mapping solo funciona sobre archivos sin comprimir. Si el `.tflite` está comprimido en el APK, `channel.map()` falla con `IOException` en runtime.

**La solución:** decirle a Android que no comprima archivos `.tflite`:
```kotlin
aaptOptions {
    noCompress += "tflite"
}
```

**Cómo se detectaría el problema sin esta config:** Los tests Robolectric pasarían igual (cargan el archivo directamente del disco, no del APK comprimido). El fallo aparecería solo en el dispositivo real. Por eso se agregó `TODO-001`: un test instrumented que verifica la carga desde el APK real.

---

## DEC-007: `suspend fun` en `TFLiteModelLoader.load()`

**Fase:** 0.3 | **Fecha:** Marzo 2026

**Decisión:** El método de carga del modelo es una `suspend fun` con `withContext(Dispatchers.IO)`.

**Alternativa descartada:** Función síncrona regular.

**Contexto para data scientists:** En Android, hay un único "main thread" que maneja la UI, los eventos táctiles, y la actualización de la pantalla. Si bloqueás este thread por más de ~5 segundos (por ejemplo, leyendo un archivo de 204KB), el OS muestra el diálogo "La aplicación no responde" (ANR) y la puede matar.

Las Kotlin Coroutines son la solución idiomática para código asíncrono. `suspend fun` significa "esta función puede pausarse sin bloquear el thread". `withContext(Dispatchers.IO)` significa "mové este trabajo a un pool de threads dedicado a I/O".

```
Python asyncio equivalent:

async def load_model(path: str) -> bytes:
    loop = asyncio.get_event_loop()
    with ThreadPoolExecutor() as pool:
        return await loop.run_in_executor(pool, lambda: open(path, 'rb').read())

Kotlin:

suspend fun load(context: Context, modelFileName: String): MappedByteBuffer =
    withContext(Dispatchers.IO) {
        // este bloque corre en un thread de I/O, no en el main thread
        ...
    }
```

**Por qué decidirlo en Fase 0.3 y no en Fase 2.1:** Si el loader fuera síncrono, en Fase 2.1 habría que refactorizarlo para usarlo desde el ForegroundService (que corre en main thread). Cuesta lo mismo hacerlo bien ahora.

---

## DEC-008: `throw ModelLoadException` en lugar de retornar null o Result

**Fase:** 0.3 | **Fecha:** Marzo 2026

**Decisión:** Cuando el modelo no puede cargarse, el loader lanza `ModelLoadException`.

**Alternativas descartadas:**

| Alternativa | Problema |
|------------|---------|
| Retornar `null` | El caller puede olvidarse de verificar null → NullPointerException en Fase 2.1 cuando se intenta crear el Interpreter |
| Retornar `Result<MappedByteBuffer>` | Más expresivo pero agrega complejidad innecesaria en Fase 0 |
| Ignorar el error y logear | Silencioso — el app arranca sin modelo y se comporta de forma impredecible |

**Por qué Exception es lo correcto acá:** La carga del modelo es una precondición para que la app funcione. Si falla, el SeizureMonitorService no puede iniciar. Una excepción fuerza al caller a tomar una decisión explícita (mostrar error, entrar en modo degradado). Esto es especialmente importante para una app médica donde un fallo silencioso puede tener consecuencias reales.

---

## DEC-009: Robolectric para tests del loader (en vez de tests instrumented)

**Fase:** 0.3 | **Fecha:** Marzo 2026

**Decisión:** Los tests de `TFLiteModelLoader` usan Robolectric (`src/test/`) en vez de tests instrumented (`src/androidTest/`).

**Contexto:** Los tests de Android que usan APIs del sistema (como `AssetManager`) normalmente necesitan un dispositivo físico o emulador conectado. Robolectric simula el sistema Android en la JVM, permitiendo que estos tests corran en cualquier PC con `./gradlew test`.

**Alternativa descartada:** Tests instrumented en `src/androidTest/`.

**Tradeoffs:**

| | Robolectric | Instrumented |
|--|-------------|-------------|
| Requiere dispositivo | No | Sí |
| Velocidad | ~segundos | ~minutos |
| Fidelidad | ~90% | 100% |
| Útil para CI/CD | Sí | Requiere emulador en CI |

**Limitación conocida (TODO-001):** Robolectric carga assets del directorio `src/test/assets/` en disco, no del APK empaquetado. El bug de `aaptOptions` (DEC-006) no es detectable con Robolectric. Se requiere un test instrumented en Fase 0.4 para eso.

---

## DEC-010: `model_fixture.tflite` como fixture de tests vs modelo real

**Fase:** 0.3 | **Fecha:** Marzo 2026

**Decisión:** Usar un modelo TFLite mínimo (144 bytes, FlatBuffer v3) generado con Python como fixture de tests.

**Contexto:** Los tests del loader verifican que el mecanismo de carga (AssetManager → FileChannel → MappedByteBuffer) funciona. No verifican que el modelo sea un CNN válido para inferencia — eso es responsabilidad de Fase 2.1.

**Cómo se generó el fixture:**

```python
import flatbuffers

b = flatbuffers.Builder(512)
# ... construye un FlatBuffer TFLite v3 mínimo (version=3, 1 subgraph vacío, 2 buffers)
b.Finish(model)
buf = bytes(b.Output())
# → 144 bytes con identificador "TFL3" válido
```

**Por qué no usar el modelo real como fixture:**
1. El modelo real (204KB) no debería vivir en `src/test/assets/` — pertenece a `src/main/assets/`.
2. Un fixture pequeño hace los tests más rápidos y explícitos sobre qué están testeando.
3. Si el test falla, sabemos que el problema es el loader, no el contenido del modelo.

**Advertencia:** El fixture NO pasaría la validación del `tflite.Interpreter` porque es un modelo vacío (sin operadores, sin tensores de la forma correcta). El `Interpreter` valida el schema TFLite al construirse. Eso se testa en Fase 2.1 con el modelo real.

---

## DEC-011: `object TFLiteModelLoader` (Kotlin singleton)

**Fase:** 0.3 | **Fecha:** Marzo 2026

**Decisión:** `TFLiteModelLoader` es un `object` de Kotlin (singleton) en vez de una clase instanciable.

**Por qué:** El loader no tiene estado propio — es una función pura que toma un contexto y un nombre de archivo y devuelve un buffer. No tiene sentido crear múltiples instancias. En Kotlin, `object` es la forma idiomática de declarar un singleton sin boilerplate.

```kotlin
// object: singleton, se usa como TFLiteModelLoader.load(...)
object TFLiteModelLoader {
    suspend fun load(context: Context, modelFileName: String): MappedByteBuffer { ... }
}

// class: habría que instanciar — innecesario aquí
val loader = TFLiteModelLoader()
loader.load(context, "model.tflite")
```

**Nota para Fase 2.1:** El `Interpreter` (que sí tiene estado) NO será un singleton porque necesita cerrarse cuando el Service se destruye. Ver TODO-002.

---

## DEC-012: `FileChannel.use {}` para cerrar el canal después de `map()`

**Fase:** 0.3 | **Fecha:** Marzo 2026

**Decisión:** Cerrar el `FileChannel` y el `AssetFileDescriptor` después de llamar a `channel.map()`.

**Contexto técnico:** `FileChannel.map()` crea un `MappedByteBuffer` que es independiente del canal — el buffer vive en la memoria virtual del proceso y el OS gestiona las páginas. El `FileChannel` puede (y debe) cerrarse después del mapping. Si no se cierra, el file descriptor queda abierto.

**Por qué importa en Wear OS:** Un `ForegroundService` que corre 8 horas continuas puede reiniciarse varias veces (batería baja, actualizaciones, reinicios del OS). Si cada reinicio abre un nuevo FD sin cerrar el anterior, eventualmente el proceso llega al límite del OS (~1024 FDs) y se cuelga.

**Implementación:**

```kotlin
assetFileDescriptor.use { afd ->           // cierra el AssetFileDescriptor al salir
    FileInputStream(afd.fileDescriptor).channel.use { channel ->   // cierra el FileChannel
        channel.map(READ_ONLY, afd.startOffset, afd.declaredLength)  // el buffer sobrevive
    }
}
// Acá: FD y canal cerrados. MappedByteBuffer sigue siendo válido.
```

**Analogía Python:**
```python
with open(model_path, 'rb') as f:
    data = f.read()
# f está cerrado, data sigue disponible
```

---

## DEC-013: ADB Wireless en vez de USB para el Samsung Watch 8

**Fase:** 0.4 | **Fecha:** Marzo 2026

**Decisión:** La conexión de desarrollo con el watch es exclusivamente via ADB Wireless (WiFi).

**Contexto:** No existe USB. El Samsung Galaxy Watch 8 no tiene puerto USB expuesto al exterior — solo el contacto de carga magnético. La única forma de conectar ADB es por red.

**Alternativa considerada:** ADB via Bluetooth (Android Debug Bridge over Bluetooth). Samsung lo soporta en algunos modelos, pero es inestable, más lento, y requiere configuración extra en la companion app. WiFi es el método estándar y recomendado por Wear OS.

**Implicaciones para el desarrollo:**

```
                   Red WiFi local (mismo router)
   PC  ───────────────────────────────────  Samsung Watch 8
   adb connect 192.168.x.x:5555             ADB Debugging + Wireless ON
   adb install wear-debug.apk               acepta el diálogo de autorización
   adb logcat                               primero
```

**Restricciones:**
- PC y watch deben estar en la misma red WiFi.
- El firewall de Windows puede bloquear el puerto 5555 — ver `connect_watch.sh` para el comando de apertura.
- Si el watch entra en modo ahorro de batería, la conexión se puede perder.

---

## DEC-014: Tests instrumented para verificar packaging del APK (TODO-001 resuelto)

**Fase:** 0.4 | **Fecha:** Marzo 2026

**Decisión:** Agregar `TFLiteModelLoaderInstrumentedTest` en `src/androidTest/` que carga el modelo desde el APK real instalado en el watch.

**Por qué esto no podía resolverse con Robolectric:**

```
Robolectric (src/test/):
  context.assets.open("model_fixture.tflite")
  → Lee de src/test/assets/ en DISCO
  → Nunca toca el APK
  → No detecta problemas de packaging

Instrumented (src/androidTest/):
  context.assets.open("cnn_v024.tflite")
  → Lee del APK INSTALADO en el watch
  → Pasa por el sistema de assets de Android
  → Detecta si el modelo está comprimido
```

**El bug que este test detecta:** Si `aaptOptions { noCompress += "tflite" }` se elimina o configura mal en un refactor futuro, el modelo queda comprimido. `FileChannel.map()` falla silenciosamente (o con IOException críptico). Todos los tests Robolectric pasan. Solo falla en el dispositivo real a las 3am. Este test instrumented captura ese escenario durante el desarrollo.

**Qué verifica `bufferMatchesExpectedSize()`:**

El tamaño exacto del `MappedByteBuffer` debe coincidir con `AssetFileDescriptor.declaredLength`. Si el APK comprimió el archivo, `declaredLength` devuelve el tamaño comprimido (menor), y `channel.map()` falla o devuelve un buffer más chico. Verificar que `buffer.limit() == 209_456` garantiza que el modelo llegó completo y sin compresión.

---

## DEC-015: Scripts bash en `scripts/` para automatizar ADB

**Fase:** 0.4 | **Fecha:** Marzo 2026

**Decisión:** Agregar `scripts/connect_watch.sh` y `scripts/deploy_wear.sh` en vez de documentar solo comandos manuales.

**Por qué scripts y no solo instrucciones en el README:**

- Los comandos ADB tienen muchos flags y casos borde (dispositivo no autorizado, firewall, múltiples dispositivos conectados). Documentarlos en el README genera README largo; un script da feedback claro con colores y mensajes de error accionables.
- `deploy_wear.sh` verifica automáticamente que el `.tflite` no está comprimido en el APK (`unzip -v` y busca "Stored" vs "Deflated") — esto es el safety check de DEC-006 en el flujo de deploy.
- Reduce la fricción para volver al proyecto después de semanas sin tocarlo.

**Alternativa considerada:** Makefile. Descartado — requiere Make instalado en Windows, y los scripts bash corren directamente en Git Bash o WSL sin dependencias adicionales.

---

## DEC-016: Decisiones de diseño del ForegroundService (Fase 1.1)

**Fase:** 1.1 | **Fecha:** Marzo 2026

### START_STICKY como valor de retorno de `onStartCommand()`

`onStartCommand()` puede devolver tres constantes:

| Constante | Comportamiento si el OS mata el Service |
|-----------|----------------------------------------|
| `START_NOT_STICKY` | No se reinicia. El monitoreo queda detenido para siempre. |
| `START_STICKY` | Se reinicia automáticamente con `intent = null`. |
| `START_REDELIVER_INTENT` | Se reinicia con el último Intent reenviado. |

**Elección: `START_STICKY`.** Si el OS mata el Service en condiciones de memoria extrema (muy raro con WakeLock activo, pero posible), Android lo reinicia. El Intent llega `null`.

> ⚠️ **Actualizado por DEC-044 (T4):** la versión original de Fase 1.1 *ignoraba* el caso `null` en el `when`. Eso resultó ser un bug Critical (servicio zombie: notificación visible sin monitoreo real). Hoy el restart con `intent=null` se maneja explícitamente — reanuda o se apaga, nunca queda zombie. Ver DEC-044.

### IMPORTANCE_LOW para el canal de notificación

`IMPORTANCE_HIGH` tocaría y mostraría un banner de alerta → interrumpe el sueño del cuidador.
`IMPORTANCE_LOW` es visible en el notification shade del reloj pero no hace ruido ni vibración.

Para una notificación persistente de servicio que solo dice "monitoreando", `IMPORTANCE_LOW` es el nivel correcto. Las alertas reales usan vibración separada (Fase 2.5).

### `setOngoing(true)` — notificación no descartable

```kotlin
.setOngoing(true)
```

Sin esto, el usuario puede deslizar la notificación para cerrarla. Pero descartar la notificación de un ForegroundService en Android detiene el Service. Un usuario que descarta por error la notificación a las 3am detiene el monitoreo sin saberlo. `setOngoing(true)` previene esto.

### Companion object con factory methods `startIntent()` / `stopIntent()`

```kotlin
companion object {
    fun startIntent(context: Context) = Intent(context, SeizureMonitorService::class.java)
        .apply { action = ACTION_START }
    fun stopIntent(context: Context)  = Intent(context, SeizureMonitorService::class.java)
        .apply { action = ACTION_STOP }
}
```

**Alternativa descartada:** que cada caller construya el Intent manualmente.

**Por qué:** Si el class name del Service cambia en un refactor, todos los callers que construían el Intent manualmente fallan en runtime (el Intent apunta a una clase inexistente). Con los factory methods, hay un único lugar donde se referencia `SeizureMonitorService::class.java`. Rompe en compilación, no en runtime.

**Bonus:** se pueden testear directamente (los tests `serviceCompanion_*Intent*` de Robolectric).

### `serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())`

El Service vive en el main thread. Toda la lógica de sensores e inferencia (Fases 1.3, 2.1) va en coroutines dentro de `serviceScope`:

- `Dispatchers.Default`: pool de threads CPU — correcto para cómputo (inferencia TFLite, buffer ops).
- `SupervisorJob`: si una coroutine falla (ej: la inferencia lanza excepción), las demás no se cancelan. El Service sigue monitoreando con las otras coroutines.
- Cancelado en `onDestroy()`: garantiza que no quedan coroutines "huérfanas" corriendo después de que el Service muere.

**Analogía Python:**
```python
# asyncio equivalente
executor = ThreadPoolExecutor(max_workers=4)
# SupervisorJob ≈ asyncio.TaskGroup con return_exceptions=True
```

---

---

## DEC-022: WakeLock — PARTIAL vs FULL, timeout de 10h, y liberación en onDestroy()

**Fase:** 1.2 | **Fecha:** Marzo 2026

### Por qué PARTIAL_WAKE_LOCK y no FULL_WAKE_LOCK

Android define tres niveles de WakeLock relevantes para aplicaciones:

| Tipo | CPU | Pantalla | Teclado |
|------|-----|----------|---------|
| `PARTIAL_WAKE_LOCK` | Activa | Puede dormir | Puede dormir |
| `SCREEN_DIM_WAKE_LOCK` (deprecated) | Activa | Encendida tenue | Puede dormir |
| `FULL_WAKE_LOCK` (deprecated) | Activa | Encendida plena | Encendido |

Para monitoreo nocturno, **la pantalla del reloj DEBE apagarse**. El usuario está durmiendo. Una pantalla encendida toda la noche destruiría la batería de 300 mAh del Galaxy Watch 8 en 1-2 horas (la pantalla es el componente que más consume). Solo necesitamos la CPU activa para que el SensorManager siga capturando acelerómetro y el TFLite siga infiriendo.

`PARTIAL_WAKE_LOCK` es el único tipo correcto para este caso de uso.

**Analogía Python:**
```python
# PARTIAL_WAKE_LOCK ≈ mantener un proceso Python corriendo en background
#                     mientras la pantalla del sistema está apagada
os.nice(0)  # CPU activa, proceso corriendo, sin necesitar display

# FULL_WAKE_LOCK ≈ mantener la pantalla encendida + el proceso
# → equivale a nunca poner el monitor en modo ahorro de energía
```

### Por qué timeout de 10 horas y no `acquire()` indefinido

`PowerManager.WakeLock.acquire()` sin argumentos crea un WakeLock que **nunca expira por sí solo**. Si el OS mata el Service sin llamar `onDestroy()` (raro pero posible en condiciones de memoria extrema), el WakeLock queda activo permanentemente. El reloj nunca entra en modo de suspensión profunda. La batería muere en horas. El usuario despierta sin monitoreo Y sin batería.

`acquire(timeoutMs)` configura un timeout de seguridad. Si `onDestroy()` se llama (el camino normal), `releaseWakeLock()` libera el lock antes de que expire el timeout. Si `onDestroy()` nunca se llama (el camino anómalo), el OS libera el WakeLock automáticamente después de `timeoutMs`.

**Por qué 10 horas específicamente:**
- 8 horas es la duración máxima del monitoreo nocturno (el caso de uso principal).
- 10h = 8h de uso + 2h de margen → el timeout nunca expira en uso normal.
- 12h sería demasiado margen (el reloj podría seguir sin dormir 4h después del monitoreo).

```kotlin
// En producción: onDestroy() siempre se llama antes del timeout
acquireWakeLock()     // t=0h: acquire con timeout 10h
releaseWakeLock()     // t~8h: onDestroy() → released. Timeout nunca dispara.

// En el caso anómalo: el timeout actúa como red de seguridad
acquireWakeLock()     // t=0h: acquire con timeout 10h
// onDestroy() nunca se llama (OS mató el proceso sin cleanup)
// t=10h: el OS libera el WakeLock automáticamente
```

### Por qué se libera en `onDestroy()` y no en `ACTION_STOP`

`ACTION_STOP` llama `stopSelf()`, que **programa** la destrucción del Service pero no la ejecuta inmediatamente. `onDestroy()` es el callback garantizado por el lifecycle de Android que se ejecuta cuando el Service efectivamente termina.

Liberar el WakeLock en `ACTION_STOP` (antes de `stopSelf()`) crearía una ventana de tiempo donde el Service está corriendo sin WakeLock — la CPU podría dormirse mientras el Service aún no terminó. Liberar en `onDestroy()` garantiza que el WakeLock protege la CPU durante todo el tiempo de vida del Service, sin excepciones.

**Nota:** En Fase 1.2 simplificamos asumiendo que parar el monitoreo = destruir el Service. En fases futuras (si se implementa pause/resume), la lógica de release podría moverse a un método explícito de pausa.

### Verificación del isHeld antes de release()

```kotlin
private fun releaseWakeLock() {
    wakeLock?.let {
        if (it.isHeld) it.release()  // ← la verificación es OBLIGATORIA
    }
    wakeLock = null
}
```

Llamar `release()` sobre un WakeLock que ya fue liberado lanza `RuntimeException: WakeLock under-locked`. Esto podría ocurrir si el timeout de 10h expira antes de que `onDestroy()` se llame. La verificación `isHeld` hace que `releaseWakeLock()` sea idempotente — se puede llamar múltiples veces sin error.

**Analogía Python:**
```python
import threading
lock = threading.Lock()
lock.acquire()
# ... tiempo después ...
if lock.locked():      # equivale a isHeld
    lock.release()     # safe — no lanza si ya está released
```

---

---

## DEC-023: TYPE_ACCELEROMETER (raw, con gravedad) en lugar de TYPE_LINEAR_ACCELERATION

**Fase:** 1.3 | **Fecha:** Abril 2026 | **Corregido:** Abril 2026

**Decisión:** El SensorManager se suscribe a `Sensor.TYPE_ACCELEROMETER` (aceleración cruda con gravedad incluida) para capturar los datos del acelerómetro.

**Decisión original (incorrecta):** Habíamos elegido `Sensor.TYPE_LINEAR_ACCELERATION` asumiendo que el modelo fue entrenado sin gravedad. Esta suposición era INCORRECTA.

**Corrección (confirmada por Graham Jones, creador de OpenSeizureDetector):**

El modelo DeepEpiCnn Run24 fue entrenado con datos de Garmin y PineTime que reportan **aceleración cruda con gravedad incluida**. Los datos de entrenamiento NO tienen la gravedad sustraída.

**Evidencia clave:**
Con el reloj en reposo sobre la mesa, un eje debe mostrar **~1000 milli-g** (= 1g de gravedad). Eso solo es posible con `TYPE_ACCELEROMETER`. Si se usara `TYPE_LINEAR_ACCELERATION`, la magnitud en reposo sería ≈0 milli-g.

**Por qué importa:**
El CNN aprendió a detectar convulsiones **sobre el baseline de ~1000 milli-g**. Una convulsión tónico-clónica genera movimientos de alta amplitud por encima de ese baseline. Si le pasamos datos sin gravedad (magnitud en reposo ≈ 0), el modelo está recibiendo inputs fuera de la distribución de entrenamiento — equivalente a pasar features sin la escala correcta a un modelo entrenado con datos en otra escala.

**Analogía Python:**
```python
# TYPE_ACCELEROMETER: el vector en reposo tiene módulo 1g
accel_raw = sensor.read()    # ej: [0.1, 9.8, 0.2] m/s²  → magnitud ≈ 9.81 m/s² ≈ 1000 milli-g

# TYPE_LINEAR_ACCELERATION: el OS sustrae la gravedad
accel_linear = sensor.read() # ej: [0.1, 0.0, 0.2] m/s²  → magnitud ≈ 0.2 m/s² ≈ 22 milli-g

# El modelo Run24 espera accel_raw. Darle accel_linear = distribución incorrecta.
```

**Implicación en las unidades:**
Las magnitudes se convierten de m/s² a **milli-g** antes de entrar al buffer:
```
milli_g = sqrt(x² + y² + z²) × (1000 / 9.81)
```
En reposo: ~1000 milli-g. Durante convulsión TC: picos de 2000-5000+ milli-g.

**Lección aprendida:**
No asumir el preprocessing del modelo — verificar con el creador o con los datos de entrenamiento originales. Un supuesto incorrecto sobre la distribución de features invalida completamente el pipeline de inferencia.

**Fallback documentado:**
`TYPE_ACCELEROMETER` está disponible en todo hardware Android. Es el sensor más básico del stack. No hay fallback necesario — todos los relojes lo soportan.

---

## DEC-024: Período de muestreo explícito (40,000µs) en lugar de SENSOR_DELAY_*

**Fase:** 1.3 | **Fecha:** Abril 2026

**Decisión:** Usar `SENSOR_SAMPLING_PERIOD_US = 40_000` (40 milisegundos) como argumento al `SensorManager.registerListener()`, en lugar de las constantes predefinidas de Android.

**Alternativas descartadas:**

| Constante de Android | Período aproximado | Frecuencia aproximada | Problema |
|---------------------|-------------------|----------------------|---------|
| `SENSOR_DELAY_NORMAL` | ~200ms | ~5Hz | Muy lento — perderíamos resolución de convulsiones |
| `SENSOR_DELAY_UI` | ~67ms | ~15Hz | Más cerca, pero no es 25Hz |
| `SENSOR_DELAY_GAME` | ~20ms | ~50Hz | El doble de lo necesario — desperdicia batería y CPU |
| `SENSOR_DELAY_FASTEST` | ~0ms (máximo del hardware) | Varía por dispositivo | Indeterminado — puede ser 100Hz+ |

**Por qué 25Hz exactamente:**

- El modelo DeepEpiCnn Run24 fue entrenado con ventanas de **750 muestras** que representan **30 segundos** de datos.
- 750 muestras / 30 segundos = 25 muestras por segundo = 25Hz.
- Cambiar la frecuencia sin reentrenar el modelo rompe el contrato de la ventana temporal: a 50Hz tendríamos 1500 muestras en 30 segundos pero el tensor input es `(1, 750, 1)`.
- El criterio de Nyquist también es relevante: las convulsiones tónico-clónicas tienen movimientos rítmicos de 1-3Hz. Para capturarlos correctamente se necesita muestrear a más del doble: 25Hz >> 6Hz.

**Conversión Hz → microsegundos:**
```
period_us = 1_000_000 / frequency_hz
period_us = 1_000_000 / 25 = 40_000 µs = 40ms
```

**IMPORTANTE — "hint" vs garantía:**

Android documenta que el período pasado a `registerListener()` es un "hint" al OS, no una garantía. El OS puede entregar muestras a una frecuencia levemente distinta según la carga del sistema. En la Fase 1.6 (logging CSV) se medirá la frecuencia real con timestamps para verificar que efectivamente estamos cerca de 25Hz antes de conectar el CNN al pipeline.

**Analogía Python:**
```python
# Android SensorManager equivalente en Python:
sensor.subscribe(callback=on_sample, interval_us=40_000)
# El OS puede llamar on_sample a 24Hz o 26Hz en la práctica — verificar con timestamps
```

---

## DEC-025: Orden de cleanup en onDestroy() — sensor → WakeLock → coroutines

**Fase:** 1.3 | **Fecha:** Abril 2026

**Decisión:** El orden de limpieza en `onDestroy()` es exactamente:
1. `stopSensorCollection()` — desregistra el SensorEventListener
2. `releaseWakeLock()` — libera el PARTIAL_WAKE_LOCK
3. `serviceScope.cancel()` — cancela todas las coroutines hijas

**Por qué este orden específico y no otro:**

**1. Sensor primero:**
El callback `onSensorChanged()` del SensorManager corre en un thread interno del OS, **fuera del serviceScope**. Si cancelamos el serviceScope antes de desregistrar el sensor, puede llegar un callback tardío que intenta lanzar una coroutine en un scope ya cancelado → `IllegalStateException: CoroutineScope is cancelled`. Desregistrar el sensor primero corta el flujo de datos desde la fuente, garantizando que no lleguen más callbacks.

```
Timeline incorrecto (sensor último):
  serviceScope.cancel()  ← scope cancelado
  Thread del OS: onSensorChanged() llega  ← intenta usar scope cancelado → CRASH

Timeline correcto (sensor primero):
  stopSensorCollection()  ← no más callbacks del sensor
  serviceScope.cancel()   ← seguro, nadie más va a usarlo
```

**2. WakeLock segundo:**
Una vez que el sensor no envía datos, ya no hay trabajo que proteger con el WakeLock. Liberarlo antes de cancelar el scope es el orden lógico: "terminamos de trabajar, soltamos el CPU, luego limpiamos los threads".

Si se hiciera al revés (cancelar scope, luego liberar WakeLock), habría una ventana donde las coroutines están canceladas pero el WakeLock sigue activo — el CPU permanece despierto sin ningún trabajo que hacer.

**3. Coroutines último:**
`serviceScope.cancel()` es la limpieza de los threads internos de la app. Hacerlo último garantiza que, si alguna coroutine estaba ejecutando trabajo crítico (logging, escritura a Room DB en fases futuras), termina de forma ordenada antes de que el scope se cierre.

**Nota sobre `super.onDestroy()`:**
La convención de Android es llamar `super.onDestroy()` al final del override (a diferencia de `super.onCreate()` que siempre va primero). El `super.onDestroy()` de la clase `Service` hace limpieza del framework que no necesita que el WakeLock o el scope estén activos.

---

## DEC-026: Ring buffer en lugar de lista creciente para acumular muestras

**Fase:** 1.5 | **Fecha:** Abril 2026 | **Actualizado:** Abril 2026 (buffer 125 → 750 muestras)

**Decisión:** Usar `CircularBuffer` (array de tamaño fijo + puntero de escritura circular) en lugar de una lista que crece indefinidamente.

**Alternativa descartada:** `MutableList<Float>` con `add()` y `removeAt(0)` al superar la capacidad.

**Por qué la descartamos:**

| Criterio | Lista creciente | Ring buffer |
|----------|----------------|-------------|
| Memoria en 8h de monitoreo | ~7 MB (1.8M muestras × 4 bytes) | 3,000 bytes (750 × 4 bytes), siempre |
| Operación de inserción | O(n) si se hace `removeAt(0)` | O(1) siempre |
| Complejidad de implementación | Simple de entender | Requiere entender el índice circular |
| Riesgo en producción | OOM en la noche si hay un bug | Imposible crecer más allá de 3,000 bytes |

**El número concreto:**

8 horas × 3600 segundos × 25 muestras/segundo = **720,000 muestras × 4 bytes = 2.88 MB**.
Con magnitud calculada desde 3 floats, el raw sería **3 × 2.88 MB = ~8.6 MB** acumulado overnight.
En un reloj con 1-2 GB RAM esto parece manejable, pero el GC tendría que limpiar constantemente, presionando la batería y la CPU. El ring buffer usa **exactamente 3,000 bytes siempre** (750 muestras × 4 bytes), sin GC pressure.

**Capacidad: 750 muestras (30 segundos a 25Hz):**
El modelo DeepEpiCnn Run24 requiere ventanas de 30 segundos como input. Ver DEC-023 para el contexto del modelo.

**Analogía Python:**
```python
# Mal: lista que crece
samples = []
samples.append(value)
if len(samples) > 750:
    samples.pop(0)  # O(n): copia toda la lista

# Bien: deque con maxlen
from collections import deque
buffer = deque(maxlen=750)
buffer.append(value)  # O(1): descarta el más antiguo automáticamente
```

---

## DEC-027: `snapshot()` retorna copia y no referencia al array interno

**Fase:** 1.5 | **Fecha:** Abril 2026

**Decisión:** `snapshot()` crea y retorna un `FloatArray` nuevo en cada llamada, copiando el contenido del buffer interno.

**Alternativa descartada:** Retornar una referencia directa al array interno del buffer.

**Por qué la descartamos:**

El buffer sigue siendo escrito por el thread del `SensorManager` (thread del OS) mientras el CNN v0.24 infiere sobre la ventana. Si `snapshot()` retornara el array interno:

```
Thread del sensor:  buffer[52] = nuevaMuestra  ← escribe
Thread de inferencia:  result = buffer[52]     ← lee simultáneamente
                                               → data race → NaN o valor corrupto
```

La consecuencia: el modelo recibe un tensor con al menos una muestra a medio escribir. El CNN podría devolver una probabilidad de convulsión basada en basura → alarma falsa a las 3am o, peor, seizure no detectado.

**Costo de la copia:** 125 × 4 bytes = **500 bytes por inferencia**. Con inferencias cada ~5 segundos (cuando el buffer se llena), el costo es negligible: 100 bytes/segundo de overhead de memoria. El GC limpia el FloatArray anterior en microsegundos.

---

## DEC-028: `synchronized(lock)` en lugar de `AtomicXxx` o Channel de Kotlin

**Fase:** 1.5 | **Fecha:** Abril 2026

**Decisión:** Usar `synchronized(lock)` con un objeto `Any()` como monitor para proteger el acceso concurrente al buffer.

**Alternativas descartadas:**

**Opción A: `AtomicInteger` + `AtomicReferenceArray`**
- `AtomicXxx` funciona para variables individuales, no para el conjunto `(buffer, writeIndex, count)` que deben actualizarse **atómicamente como una unidad**.
- Para proteger las tres variables a la vez con `AtomicXxx` se necesitaría un CAS loop complejo que es más difícil de leer y verificar que un simple `synchronized`.

**Opción B: `Channel<Float>` de Kotlin Coroutines**
- Más idiomático en Kotlin moderno: el sensor produce en un Channel, la inferencia consume.
- La complejidad de introducir Channels, Flows y backpressure en Fase 1.5 no está justificada.
- En Fase 2.x, cuando se integre la inferencia asincrónica con `serviceScope`, puede tener sentido migrar. Por ahora, `synchronized` es correcto y simple.

**Opción C: `ReentrantLock` de Java**
- Más flexible que `synchronized` (permite `tryLock()`, interrumpible).
- La flexibilidad extra no se necesita aquí — el lock se adquiere en dos métodos cortísimos (`add()` y `snapshot()`). `synchronized` es suficiente.

**Por qué `synchronized` es la elección correcta aquí:**
- Es la solución más simple que garantiza la invariante.
- Un data scientist que lee el código entiende inmediatamente qué protege y por qué.
- El bloque bloqueado dura microsegundos (copiar/escribir 125 floats). No hay contención observable.

---

## DEC-029: `getExternalFilesDir()` en lugar de `filesDir` para los CSV

**Fase:** 1.6 | **Fecha:** Abril 2026

**Decisión:** Almacenar los archivos CSV de logging en `getExternalFilesDir(null)/logs/` y no en `filesDir`.

**Contexto:** El data scientist necesita poder descargar los CSV del reloj a la PC para análisis en Python. Hay dos ubicaciones disponibles para datos de la app:

| Ubicación | Acceso vía ADB | Permisos necesarios |
|-----------|---------------|---------------------|
| `filesDir` (almacenamiento interno) | Solo con `adb shell run-as com.seizureguard.wear` o root | Ninguno extra |
| `getExternalFilesDir()` (almacenamiento externo de la app) | `adb pull /sdcard/Android/data/...` directamente | Ninguno extra en API 29+ |

**Por qué `getExternalFilesDir()`:**
- `adb pull /sdcard/Android/data/com.seizureguard.wear/files/logs/` funciona sin root ni permisos especiales.
- No requiere el permiso `WRITE_EXTERNAL_STORAGE` en API 29+ (Android 10+). Desde API 29, cada app tiene acceso irrestricto a su propio directorio en almacenamiento externo.
- El directorio se crea automáticamente si no existe (`.apply { mkdirs() }`).

**Desventaja aceptada:** Si el usuario desinstala la app, los archivos CSV se borran. Para datos de debugging en desarrollo, esto es completamente aceptable.

**Alternativa descartada:** `filesDir` → requiere `adb shell run-as` que no siempre está disponible en relojes con builds de producción.

---

## DEC-030: Logging a CSV solo en `BuildConfig.DEBUG`

**Fase:** 1.6 | **Fecha:** Abril 2026

**Decisión:** Controlar el logging CSV con `BuildConfig.DEBUG` en lugar de un flag de runtime configurable.

**Contexto:** En el monitoreo nocturno de 8 horas a 25Hz, el CSV acumularía:
- 25 muestras/segundo × 8 horas × 3600 segundos = **720,000 filas**
- Cada fila ≈ 50 bytes → **~36 MB por noche**
- 25 escrituras/segundo al sistema de archivos del reloj → impacto en batería e I/O

**Por qué `BuildConfig.DEBUG`:**
- Es `true` en builds de desarrollo (Android Studio, Gradle `debug` variant) y `false` en builds de release.
- El compilador elimina el bloque `if (BuildConfig.DEBUG) { ... }` en el bytecode de release → cero overhead en producción.
- No requiere UI adicional ni configuración por el usuario.

**Alternativa descartada A: Flag en SharedPreferences (runtime)**
- El usuario podría activarlo accidentalmente en producción → consumo inesperado de batería y storage.
- Agrega UI/UX que no aporta valor para el caso de uso principal.

**Alternativa descartada B: Siempre activo**
- 36 MB/noche × 30 noches = 1 GB en el reloj en un mes → inaceptable para un dispositivo con 2-4 GB de storage total.

---

## DEC-031: `BufferedWriter` y no `FileWriter` directo para escritura CSV

**Fase:** 1.6 | **Fecha:** Abril 2026

**Decisión:** Usar `BufferedWriter(FileWriter(file))` en lugar de escribir directamente con `FileWriter`.

**El problema con `FileWriter` directo:**
```kotlin
// MAL: cada write() es una syscall al sistema de archivos
writer.write("$ts,$x,$y,$z,$mag\n")  // ← syscall al OS
// 25 veces/segundo × 8 horas = 720,000 syscalls al sistema de archivos
```

**Por qué `BufferedWriter` resuelve esto:**
- Acumula los datos en un buffer en memoria (por defecto 8KB ≈ ~160 filas de CSV).
- Solo hace la syscall cuando el buffer se llena → típicamente cada ~6 segundos en lugar de 25 veces/segundo.
- Reduce el I/O al almacenamiento flash del reloj en ~150x → menos consumo de batería, menos desgaste del flash.

**El flush() explícito en `close()`:**
- `BufferedWriter.close()` llama `flush()` internamente en condiciones normales.
- El `flush()` explícito antes del `close()` garantiza que si ocurre una excepción durante el cierre, los datos aún llegan al OS antes de que se cierre el file descriptor.
- En el bloque `finally` de `close()`, el estado se limpia pase lo que pase.

---

## DEC-032: Orden de cleanup en `onDestroy()` — sensor → csvLogger → WakeLock → coroutines

**Fase:** 1.6 | **Fecha:** Abril 2026

**Decisión:** Agregar `csvLogger.close()` DESPUÉS de `stopSensorCollection()` y ANTES de `releaseWakeLock()` en `onDestroy()`.

**El orden completo:**
```
1. stopSensorCollection()   ← corta el flujo de datos del sensor
2. csvLogger.close()        ← flush + cierre del archivo CSV
3. releaseWakeLock()        ← libera el CPU lock
4. serviceScope.cancel()    ← cancela coroutines
5. super.onDestroy()
```

**Por qué `csvLogger.close()` ANTES de `releaseWakeLock()`:**

`BufferedWriter` tiene un buffer interno con datos que pueden no haberse volcado a disco todavía. El flush es una operación I/O que puede tomar algunos milisegundos:

```
Estado: buffer tiene 50 filas sin volcar a disco
  │
  ├─ Si csvLogger.close() va ANTES de releaseWakeLock():
  │    CPU activa → flush completa → datos en disco → OK
  │
  └─ Si releaseWakeLock() va ANTES de csvLogger.close():
       CPU puede suspenderse durante el flush → archivo CSV incompleto o corrupto
```

**Por qué `csvLogger.close()` DESPUÉS de `stopSensorCollection()`:**
El sensor puede enviar callbacks en threads del OS. Si cerramos el CSV mientras el sensor sigue activo, `write()` podría llamarse sobre un `BufferedWriter` cerrado → `IOException`. Parar el sensor primero garantiza que no lleguen más escrituras.

**Por qué el mismo orden aplica en `ACTION_STOP`:**
```
stopSensorCollection() → csvLogger.close() → accelerometerBuffer.reset() → stopSelf()
```
`stopSelf()` triggers `onDestroy()` eventualmente, pero los recursos deben liberarse en el orden correcto en el sitio de llamada, no depender de `onDestroy()` como única red de seguridad.

---

---

## DEC-033: `MessageClient` y no `DataClient` para enviar accel_data

**Fase:** 2.1 | **Fecha:** Abril 2026

**Decisión:** Usar `MessageClient` para enviar las 750 muestras del acelerómetro al teléfono.

**Alternativa descartada:** `DataClient` (el otro mecanismo principal del Wear Data Layer).

**Por qué descartamos `DataClient`:**

| Criterio | `DataClient` | `MessageClient` |
|----------|-------------|----------------|
| Semántica | Key-value store sincronizado entre dispositivos | Mensaje unidireccional fire-and-forget |
| Overhead | Sincronización bilateral → mayor latencia | Sin sincronización → baja latencia |
| Persistencia | Los datos persisten hasta que se actualizan | El mensaje no persiste — se entrega o se pierde |
| Caso de uso ideal | Configuración, preferencias que deben estar disponibles offline | Streaming de datos que se procesa inmediatamente |
| Para accel_data | Inapropiado: sincroniza p. ej. 3,000 bytes (750×4) por ventana innecesariamente en el store | Correcto: envía y olvida — el teléfono infiere y descarta (tamaño por mensaje: ver DEC-039) |

**El argumento clave:** El teléfono no necesita almacenar los datos del acelerómetro — los procesa y descarta. `DataClient` mantendría los últimos 3,000 bytes sincronizados y disponibles aunque el teléfono no esté conectado. Ese overhead no aporta nada y añade latencia. `MessageClient` envía y listo.

**Referencia:** SdDataSourceAw.java de OpenSeizureDetector V5 también usa `MessageClient` para recibir los datos — mantener la misma API simplifica la compatibilidad.

---

## DEC-034: Serialización little-endian para el ByteBuffer de accel_data

> ⚠️ **SUPERADA por DEC-046 (Fase A, junio 2026).** El transporte ya NO es binario
> little-endian: hoy es **JSON `{"samples":[...]}`** en UTF-8, para matchear lo que
> `SdDataSourceAw` de OSD parsea primero. Se mantiene como historia. Ver DEC-046.

**Fase:** 2.1 | **Fecha:** Abril 2026

**Decisión:** Serializar los floats de `accel_data` en `ByteOrder.LITTLE_ENDIAN` antes de enviarlos via `MessageClient` (cantidad de floats variable; ver DEC-039).

**Por qué:**

1. **Compatibilidad con SdDataSourceAw.java:** El código del teléfono deserializa con `ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)`. Si el reloj serializa en big-endian, los 4 bytes de cada float llegan invertidos → el modelo infiere sobre basura sin ningún error visible.

2. **Orden nativo de ARM:** El Samsung Galaxy Watch 8 usa un procesador ARM (Exynos W930). ARM es natively little-endian. No hay conversión de bytes → cero overhead.

**Analogía Python:**
```python
import struct
# little-endian: '<' + f'{len(samples)}f'  (p. ej. 750f o 125f — ver DEC-039)
payload = struct.pack(f'<{len(samples)}f', *samples)
# big-endian hubiera sido '>' — el teléfono fallaría silenciosamente
```

**Verificación:** El test `floatsToBytes_isLittleEndian` verifica que el primer float `1.0f` produce `[0x00, 0x00, 0x80, 0x3F]` — el encoding little-endian de IEEE 754 para 1.0.

---

## DEC-035: Modo secuencial como primer test de transporte (protocolo Graham Jones)

> ⚠️ **Actualizado por DEC-046:** el modo secuencial sigue existiendo, pero hoy envía
> `N` números secuenciales **como JSON `{"samples":[...]}`** (no binario), donde `N` es el
> tamaño del chunk de transporte (`TRANSPORT_CHUNK_SIZE = 125`), con numeración **continua
> entre chunks** para validar el orden global. La idea (verificar transporte antes que datos
> reales) sigue vigente; el formato y el `N` cambiaron. **Pendiente de confirmar** si el
> protocolo de validación de Graham sigue activo (ver T3 en el plan de auditoría).

**Fase:** 2.1 | **Fecha:** Abril 2026

**Decisión:** Agregar `isSequentialMode` al companion object de `SeizureMonitorService`. Cuando es `true` (por defecto en DEBUG), `onWindowReady()` envía `[1.0, 2.0, ..., 750.0]` en lugar de datos reales.

**Por qué verificar el transporte antes de conectar datos reales:**

El pipeline de datos tiene tres capas con potenciales puntos de falla:
```
Sensor → CircularBuffer → WearDataLayerManager → MessageClient → Bluetooth → SdDataSourceAw.java
```

Si conectamos datos reales directamente y el modelo da resultados extraños, no sabemos en qué capa está el problema. ¿El sensor mide mal? ¿La serialización invierte bytes? ¿El Bluetooth fragmenta paquetes?

Los números secuenciales `[1.0..750.0]` son fácilmente verificables en el logcat del teléfono sin necesidad de entender los datos del acelerómetro:
- Si el teléfono recibe `[1.0, 2.0, 3.0, ...]` → el transporte funciona.
- Si recibe `[4.0, 3.0, 2.0, 1.0, ...]` → hay inversión de orden.
- Si recibe `[0.0, 1.401e-45, ...]` → la serialización está en big-endian.

**Dos pasos del protocolo:**
1. `isSequentialMode = true` → verificar orden de llegada end-to-end.
2. `isSequentialMode = false` + reloj quieto → verificar ~1000 milli-g en logcat del teléfono.

Solo cuando ambos pasos pasen se considera el transporte validado.

---

---

## DEC-036: StateFlow en companion object vs LiveData vs BroadcastReceiver

**Fase:** 2.2 | **Fecha:** Abril 2026

**Decisión:** Usar `StateFlow` en el companion object de `SeizureMonitorService` para comunicar el `alarmState` a la `MainActivity`.

**Alternativas descartadas:**

| Alternativa | Por qué se descartó |
|-------------|---------------------|
| `LocalBroadcastManager` | Deprecated desde AndroidX 1.1.0 |
| `LiveData` | Requiere un `LifecycleOwner` — correcto para Activity pero innecesario aquí; introduce acoplamiento con el lifecycle de Android sin beneficio real en este contexto |
| `SharedPreferences` + polling | Lento, no reactivo, requiere un loop o un `FileObserver` |
| `ViewModel` compartido | Overhead innecesario en esta fase — el Service y la Activity viven en el mismo proceso |

**Por qué StateFlow:**
- Es la API moderna de Kotlin para estado observable
- `collectAsState()` en Compose lo conecta directamente a la UI sin boilerplate
- El companion object es suficiente en esta fase — en Fase 3 se puede migrar a un ViewModel compartido si el estado se complejiza

**Archivos afectados:** `SeizureMonitorService.kt` (companion object), `MainActivity.kt` (collectAsState)

---

## DEC-037: VibrationEffect.createWaveform() para ALARM en vez de loop de coroutines

**Fase:** 2.2 | **Fecha:** Abril 2026

**Decisión:** Usar `VibrationEffect.createWaveform(timings, amplitudes, repeat=-1)` para el patrón de ALARM, en lugar de un loop de coroutines que llame `vibrate()` repetidamente.

**Alternativa descartada:** Un loop en `serviceScope` que dispara `vibrate()` cada 700ms mientras `alarmState >= 2`.

**Por qué se descartó el loop:**
- Requiere cancelación explícita cuando el alarmState vuelve a OK
- Condición de carrera: si el loop tarda en cancelarse y llega un nuevo alarmState antes, el reloj puede seguir vibrando cuando no debería
- Más código, más puntos de falla en una app médica

**Por qué createWaveform:**
- El patrón de vibración está controlado por el sistema operativo, no por una coroutine
- Para detener la vibración: `vibrator.cancel()` — una sola línea, inmediato
- `repeat = -1` significa "ejecutar el waveform una sola vez" — el ciclo de detección controla si se vuelve a llamar

**Archivos afectados:** `AlarmStateManager.kt`

---

## DEC-038: Amplitudes 80 para WARNING y 255 para ALARM

**Fase:** 2.2 | **Fecha:** Abril 2026

**Decisión:** WARNING usa amplitud 80/255 (~31%) y ALARM usa amplitud 255 (máxima).

**Razonamiento:**

| Estado | Amplitud | Justificación |
|--------|----------|---------------|
| WARNING | 80/255 (~31%) | Perceptible en la muñeca sin despertar al cuidador. Es una señal al sistema para prepararse, no una alarma final. |
| ALARM | 255 (máxima) | Debe despertar al usuario y al cuidador. Cuando hay riesgo real de convulsión, no hay razón para suavizar la respuesta háptica. |

**Por qué no una escala lineal (0→50→255):**
El protocolo OSD define solo 3 estados clínicamente relevantes: normal, sospechoso, alarma. Una escala continua agregaría complejidad sin beneficio para el usuario final.

**Archivos afectados:** `AlarmStateManager.kt` (constantes `WARNING_AMPLITUDE`, `vibrateAlarm()`)

---

## DEC-039: Contrato OSD wear ↔ phone (transporte de bytes)

> ⚠️ **SUPERADA por DEC-046 (Fase A, junio 2026).** Esta entrada describe el contrato **binario**
> (floats little-endian, alarm_state de 1 byte) que ya **NO se usa**. El contrato vigente es **JSON**
> y suma el handshake de settings. La **única fuente de verdad actual es DEC-046.** Se mantiene
> debajo como historia del diseño binario original.

**Fase:** 2.1+ (documentación de contrato) | **Fecha:** Mayo 2026

**Decisión:** Esta entrada era la única fuente de verdad para el protocolo de mensajes entre reloj y teléfono (paths, payload binario, endianness, unidades y relación con el tensor del modelo). DEC-033 y DEC-034 describen el mecanismo (`MessageClient`) y la serialización; aquí se congelaba el contrato completo.

### Paths (`MessageClient`)

| Path | Dirección | Payload |
|------|-----------|---------|
| `/osd/accel_data` | watch → phone | `N` floats IEEE-754 **little-endian** (ver DEC-034), `N ≥ 1`, tamaño en bytes = `N × 4`. Valores: magnitud vectorial en **milli-g** (misma convención que DEC-024). |
| `/osd/alarm_state` | phone → watch | **1 byte** sin signo: `0` = OK, `1` = WARNING, `2+` = ALARM (resto reservado / compatibilidad OSD). |

### Tensor del modelo CNN v0.24 (TFLite) vs tamaño del mensaje

| Concepto | Valor canónico | Notas |
|----------|------------------|--------|
| **Input del modelo** `(batch, timesteps, features)` | **`(1, 750, 1)`** | 750 timesteps × 25 Hz = 30 s de magnitud en milli-g. Documentado en `wear/src/main/assets/MODELS.md`. |
| **Floats por mensaje `accel_data`** | **Variable `N`** | La API `WearDataLayerManager.sendAccelData(samples)` no fija `N` en tiempo de compilación: el teléfono debe interpretar `data.size / 4` como `N`. Hoy el flujo de producción envía ventanas completas (`N = 750` → 3000 bytes); el plan de producto prevé **chunks de transporte** (p. ej. `N = 125` → 500 bytes, ~5 s a 25 Hz) que el teléfono acumula hasta armar la ventana de 750 para inferencia. |

**Regla explícita:** `N` del mensaje **no tiene por qué** coincidir con 750: es el **tamaño de un envío** por Data Layer; **750** es el **timesteps del tensor** del modelo. No confundir chunk de transporte con shape del input TFLite.

### Frecuencia y cadencia (referencia)

- **Sensor:** 25 Hz (período objetivo 40 ms; ver DEC-024).
- **Mensajes `accel_data`:** acoplados a la lógica de ventana/chunk del servicio (p. ej. al llenar buffer o cada 125 muestras), no a cada muestra individual.
- **`alarm_state`:** evento puntual cuando el teléfono actualiza el estado hacia el reloj.

**Referencias de código:** `WearDataLayerManager.PATH_ACCEL_DATA`, `PATH_ALARM_STATE`, `floatsToBytes` / `bytesToFloats`; en phone: `DataLayerListenerService`, `AccelPayloadCodec`, `PhoneCircularBuffer`, `PhoneAccelChunkProcessor` (DEC-040).

---

## DEC-040: Teléfono — warm-up del buffer y luego inferir en cada chunk (ventana deslizante)

**Fase:** 3.1 (acumulador phone) | **Fecha:** Mayo 2026

**Decisión:** En el módulo `phone`, `PhoneCircularBuffer` acumula muestras hasta `inputSize` (750 por defecto). Mientras `count < inputSize`, **no** se dispara inferencia ni se envía `alarm_state` al watch. Una vez el buffer está **lleno** (`isFull`):

1. **Primera inferencia** ocurre en el primer instante en que hay 750 muestras acumuladas (p. ej. tras 6 chunks de 125).
2. **Inferencias siguientes:** tras cada chunk adicional que se ingiere con `addAll`, el buffer sigue lleno y representa una **ventana deslizante** con paso igual al tamaño del chunk (125): se infiere de nuevo en **cada** mensaje posterior.

**Por qué no inferir antes del warm-up:** El tensor del modelo exige 750 timesteps continuos en orden; inferir con menos datos sería padding arbitrario o basura.

**Por qué inferir en cada chunk post warm-up:** Mantiene latencia acotada respecto al último dato recibido y coincide con el paso de 125 muestras del plan de transporte (actualización cada ~5 s de señal nueva entrando en la ventana de 30 s).

**Archivos afectados:** `PhoneCircularBuffer.kt`, `PhoneAccelChunkProcessor.kt`, `DataLayerListenerService.kt`.

---

## DEC-041: Tests de contrato escritos en rojo ANTES del fix (red de seguridad)

**Fase:** Auditoría / Milestone 0 (T1) | **Fecha:** Junio 2026

**Decisión:** Antes de tocar el camino crítico del `SeizureMonitorService`, escribir tests que
describen el comportamiento **correcto deseado**, verlos **fallar (rojo)**, y recién después
implementar el fix. Mientras el fix no existe, los tests quedan marcados `@Ignore` con referencia
al hallazgo, para no romper el CI.

**Por qué primero el test y verlo en rojo:**
Un test que nunca viste fallar no es una red de seguridad, es una esperanza. Si lo escribís
después del fix, no sabés si testea lo correcto. Verlo en rojo primero garantiza que el test
**clava donde tiene que clavar**; cuando pasa a verde, sabés que el fix es real.

**Dos lecciones concretas de este proceso (quedaron como evidencia, no como teoría):**

1. **Falso verde detectado.** El primer test de H1 medía la cantidad de *listeners de sensor*
   tras un doble arranque. Pero Android (y `ShadowSensorManager`) **deduplican** el registro del
   mismo objeto listener → daba 1 aunque el bug existiera. Se reescribió para medir el **WakeLock**
   (cada arranque crea una instancia distinta = el leak real). Moraleja: un test que da verde con
   el bug presente es peor que no tener test.
2. **Gotcha de Robolectric.** `assertSame(msg, wakeLockA, wakeLockB)` lanza `NullPointerException`
   en `PowerManager$WakeLock.toString()` (`mToken is null`) al construir el mensaje de fallo,
   ocultando la causa. Solución: comparar con `===` y usar `assertTrue` sobre el booleano.

**Archivos afectados:** `wear/src/test/.../service/SeizureMonitorServiceTest.kt` (sección "Tests de contrato").

---

## DEC-042: CI con GitHub Actions (tests + lint en cada push y PR)

**Fase:** Auditoría / Milestone 0 (T2) | **Fecha:** Junio 2026

**Decisión:** Un workflow `.github/workflows/ci.yml` que corre `:wear:testDebugUnitTest` y
`:wear:lintDebug` en cada push a `main` y cada Pull Request (JDK 17 Temurin, cache de Gradle).

**Por qué:** El repo ya tenía >1.600 líneas de tests que nadie corría automáticamente — una red
guardada en el placard. Más aún yendo a la org pública OpenSeizureDetector: un contribuidor
externo debe saber al instante si rompió algo, sin depender de que alguien lo recuerde.

**Dos gotchas que el propio CI nos enseñó (fallando):**
- **`exit 126` (permiso denegado):** git tenía `gradlew` como `100644` (no ejecutable); en el
  runner Linux `./gradlew` no corría. Fix: `git update-index --chmod=+x gradlew` (queda `100755`),
  sirve para CI y para cualquier clon.
- **Node 20 deprecado:** las actions corrían sobre Node 20 (forzado a Node 24 el 16/06/2026). Se
  actualizaron a los majors vigentes: checkout v6, setup-java v5, setup-gradle v6, upload-artifact v7.
  Versiones verificadas con `gh api repos/<repo>/releases/latest`, no de memoria.

**Regla de trabajo:** siempre verificar `:wear:testDebugUnitTest` y `:wear:lintDebug` **localmente**
antes de pushear — el CI confirma, no descubre.

---

## DEC-043: Flujo rama + Pull Request obligatorio (no push directo a main)

**Fase:** Auditoría / Milestone 0 | **Fecha:** Junio 2026

**Decisión:** Todo cambio entra por una rama y un PR; `main` está protegido con un *ruleset* que
exige el check de CI verde. El repo se hizo **público** para que la protección de ramas sea gratis
(GitHub la cobra en repos privados).

**Por qué terminó siendo PR obligatorio aunque se eligió "sin require pull request":**
El "required status check" **bloquea el push directo** a `main`. Es un huevo-y-gallina: GitHub
exige que el check esté verde *antes* de aterrizar el commit, pero el check solo corre *después*.
La única forma de resolverlo es por PR, donde el CI corre sobre la rama antes del merge.

**Flujo consolidado:**
```
git checkout -b fix/xxx → commit → git push -u origin fix/xxx
gh pr create → gh pr checks <n> --watch (esperar verde)
gh pr merge <n> --merge --delete-branch → git checkout main → git pull
```

**Por qué está bien:** Sin querer, la ruleset impuso el flujo profesional correcto para un repo
público con aportes externos. Nada entra a `main` sin pasar la red de tests.

---

## DEC-044: Restart con `intent=null` — reanudar o apagarse, nunca quedar zombie (Critical C1)

**Fase:** Auditoría / Milestone 1 (T4) | **Fecha:** Junio 2026

**Decisión:** `onStartCommand()` maneja explícitamente el caso `intent=null` (restart por
`START_STICKY` tras un kill del OS) en `onRestartAfterKill()`: si el monitoreo estaba activo,
**reanuda**; si no, hace `stopSelf()`.

**El bug que arregla (era Critical):**
Cuando el OS mataba el Service por memoria y lo recreaba con `intent=null`, el `when` no matcheaba
ninguna rama: **no se readquiría el WakeLock ni el sensor**, pero `onCreate()` ya había mostrado la
notificación persistente. Resultado: el reloj decía "monitoreando" sin capturar **un solo dato**.
En software de seguridad de vida, ese es el peor estado posible: **confianza falsa**.

**Cómo:** se persiste si el monitoreo está activo en `SharedPreferences` (`KEY_WAS_MONITORING`).
Detalle clave del diseño: **solo `ACTION_STOP` (parada explícita del usuario) limpia el flag**; un
kill del OS NO lo borra. Así el restart sabe distinguir "me mataron mientras cuidaba" (→ reanudar)
de "el usuario ya había parado" (→ apagarse).

**Tests:** `service_restartWithNullIntent_whileWasMonitoring_resumesMonitoring` y
`service_restartWithNullIntent_whenNotMonitoring_stopsSelf` (escritos en rojo según DEC-041, hoy verdes).

**Archivos afectados:** `wear/src/main/.../service/SeizureMonitorService.kt`.

---

## DEC-045: `onMonitoringStart()` idempotente — un flag de instancia (High H1)

**Fase:** Auditoría / Milestone 1 (T5) | **Fecha:** Junio 2026

**Decisión:** `onMonitoringStart()` retorna sin hacer nada si ya está monitoreando, usando un flag
de instancia `isMonitoringActive` (se resetea en `onMonitoringStop()`).

**El bug que arregla:**
Un segundo `ACTION_START` (doble tap, o el OS reenviando el intent) re-ejecutaba el arranque:
`acquireWakeLock()` reasignaba el campo `wakeLock` a uno **nuevo** y lo adquiría, dejando el primero
held **para siempre** (la batería del reloj se drena de noche). También duplicaba los listeners de
`MessageClient`.

**Por qué un flag de instancia y no el `KEY_WAS_MONITORING` de DEC-044:**
Son dos cosas distintas. `isMonitoringActive` vive **con la instancia** del Service (idempotencia
en caliente). `KEY_WAS_MONITORING` es **persistente** y sobrevive a un kill del OS (para reanudar).
Mezclarlos confundiría dos responsabilidades.

**Test:** `service_doubleActionStart_doesNotAcquireSecondWakeLock` (rojo→verde según DEC-041).

**Archivos afectados:** `wear/src/main/.../service/SeizureMonitorService.kt`.

---

## DEC-046: Contrato OSD wear ↔ phone en JSON (Fase A) — FUENTE DE VERDAD ACTUAL

**Fase:** A | **Fecha:** Junio 2026

**Decisión:** El reloj habla con la app OSD V5.0 usando **mensajes JSON UTF-8** sobre `MessageClient`,
adaptándose al formato que `SdDataSourceAw.java` de OSD **ya espera** (no al revés). Esta entrada
**reemplaza** el contrato binario de DEC-034 y DEC-039.

> ⚠️ **Bloqueante de transporte conocido (DEC-050):** este contrato JSON es correcto (matchea
> `SdDataSourceAw`), pero hoy los mensajes **no llegan a OSD**: el Wear Data Layer los descarta por
> *AppKey mismatch* — SeizureGuard y OSD no comparten package name ni certificado de firma. El
> arreglo está en exploración SDD `watch-osd-message-delivery`. Ver **DEC-050**.

> **Por qué cambió:** OSD parsea `accel_data` como JSON primero (`json.has("samples")`), y su
> fallback binario lee `int16`, no `float32`. Mandar floats binarios little-endian (DEC-034) NO
> matcheaba ese parser → datos basura silenciosos. Adaptarse a JSON es lo que hace que OSD funcione.

### Paths (`MessageClient`)

| Path | Dirección | Payload (JSON UTF-8) |
|------|-----------|----------------------|
| `/osd/accel_data` | watch → phone | `{"samples":[m0, m1, ...]}` — `N` magnitudes en **milli-g**. Hoy `N = TRANSPORT_CHUNK_SIZE = 125` (~5 s a 25 Hz); OSD acumula hasta 750 para inferir. |
| `/osd/alarm_state` | phone → watch | `{"alarm_state": <int>, "alarm_phrase": "<texto>"}`. El reloj lee `alarm_state` (0=OK, 1=WARNING, 2+=ALARM). Payload ilegible → se loguea y se ignora, no crashea. |
| `/osd/settings` | watch → phone | `{"battery": <0-100>, "sample_freq": <Hz>}`. Respuesta al handshake; sin esto OSD reporta "Data source fault". |
| `/osd/send_settings` | phone → watch | Texto plano `"start"`: OSD pide los settings al arrancar la fuente Android Wear. |

### Handshake de settings (lo que evita "Data source fault")

OSD manda `/osd/send_settings` con `"start"` al iniciar la fuente. El reloj debe responder
`/osd/settings` con batería + frecuencia. El reloj además los manda **proactivamente** al arrancar
el monitoreo (por si OSD mandó `"start"` antes de que el listener estuviera registrado — carrera de
arranque). Ver `architecture/seizureguard-aw-contract` en engram.

### Tensor del modelo vs tamaño del mensaje

| Concepto | Valor | Notas |
|----------|-------|-------|
| **Input del modelo** | **`(1, 1, 750)`** | ExecuTorch (`.pte`), NO TFLite. 750 timesteps × 25 Hz = 30 s. Corre en el teléfono dentro de OSD. |
| **Floats por mensaje `accel_data`** | `N = 125` (chunk) | El teléfono interpreta `samples.length`; acumula chunks hasta 750. `N` (transporte) ≠ 750 (timesteps del tensor). |

### Unidades y frecuencia

- Magnitud `√(x²+y²+z²)` convertida a **milli-g** (DEC-023/024). En reposo ~1000 milli-g.
- Sensor a 25 Hz (período 40 ms, DEC-024). Un mensaje `accel_data` cada ~5 s (al llenar el chunk).

**Referencias de código:** `WearDataLayerManager` (`PATH_ACCEL_DATA`, `PATH_ALARM_STATE`,
`PATH_SETTINGS`, `PATH_SEND_SETTINGS`, `samplesToJsonBytes`, `parseAlarmState`, `settingsToJsonBytes`).

---

## DEC-047: Modo validación apagado por defecto y opt-in explícito (Critical C2)

**Fase:** Auditoría / T3 (fases 1 y 2) | **Fecha:** Junio 2026

**El bug que arreglamos (era Critical):**
`isSequentialMode` defaulteaba a `BuildConfig.DEBUG`. Cuando está activo, el reloj manda a OSD
números secuenciales sintéticos (`[1, 2, 3, ...]`) en vez del acelerómetro real — es el "modo
validación de Graham" para probar el transporte. El problema: como el default era `BuildConfig.DEBUG`,
**cualquier build de debug recién instalado mandaba datos sintéticos por defecto**. El detector
procesaba basura y nadie se enteraba. En software de seguridad de vida, inaceptable.

**Decisión (en dos fases):**

- **Fase 1 — default `false` siempre.** Un build fresco, debug o release, manda **datos reales**.
  Esto solo cierra el agujero. Test: `sequentialMode_isDisabledByDefault` (corre en variante debug,
  así que si alguien revierte el default a `BuildConfig.DEBUG`, falla).

- **Fase 2 — opt-in explícito.** El modo validación se activa **solo** si el Intent de start lo pide
  con `EXTRA_VALIDATION_MODE = true` **Y** el build es debug (doble condición en `onStartCommand`).
  `startIntent(context, validationMode = true)` agrega el extra; el Intent normal de la app
  (`MainActivity`) nunca lo pone. En release es imposible activarlo, ni a propósito.

```
# Activar el modo validación por ADB (solo debug):
adb shell am start-foreground-service \
  -n com.seizureguard.wear/.service.SeizureMonitorService \
  -a com.seizureguard.wear.START_MONITORING --ez validation_mode true
```

**Por qué la doble condición (`BuildConfig.DEBUG && extra`):**
Defensa en profundidad. Aunque alguien pase el extra en un APK de release (por un bug o un Intent
malicioso), el `&& BuildConfig.DEBUG` lo bloquea. El modo validación literalmente no existe en producción.

**Falta (Fase 3):** aviso visible ("MODO VALIDACIÓN — datos sintéticos") en notificación/UI mientras
esté activo, para que durante una sesión de validación nunca pase desapercibido. La seguridad ya está
garantizada por fases 1 y 2; la fase 3 es ergonomía.

**Tests:** `sequentialMode_isDisabledByDefault`, `actionStart_withValidationExtra_enablesSequentialMode`,
`actionStart_withoutValidationExtra_leavesSequentialModeOff`. (Los dos últimos restauran el `var`
global `isSequentialMode` en `finally` para no contaminar otros tests.)

**Archivos afectados:** `wear/src/main/.../service/SeizureMonitorService.kt`. Supera lo que DEC-035
describía sobre el default del modo secuencial.

---

## DEC-048: Watchdog de salud del pipeline (H2, M3, M4, M5)

**Fase:** Auditoría / T8 | **Fecha:** Junio 2026

**El problema (familia de fallas silenciosas):**
El reloj podía *creer* que monitoreaba cuando en realidad nada llegaba al teléfono. Lo vivimos en
campo (ver `FIELD_TEST_NOTES.md`: "silencio bidireccional"). El sensor que deja de emitir, el
Bluetooth que se cae, el teléfono que se desconecta — todas comparten el mismo veneno: el sistema
muestra "todo bien" mientras la red de seguridad está rota. En software de seguridad de vida,
**una falla que no avisa es peor que una alarma falsa.**

**Decisión:** Un watchdog (corutina en `serviceScope`) corre cada `WATCHDOG_INTERVAL_MS` (30s) y
evalúa dos marcas de tiempo: última muestra del sensor (`lastSampleAtMs`) y última entrega
**exitosa** al teléfono (`lastDeliveryOkAtMs`). Si alguna se pasa de su umbral, pasa a estado
**DEGRADADO**: notificación "⚠ MONITOREO DEGRADADO", vibración distintiva y `StateFlow pipelineHealth`.

**Un solo componente cierra cuatro hallazgos:**
- **H2** — `sendAccelData`/`sendToAllNodes` ahora devuelven `Boolean`; el fallo de envío ya no se
  traga en silencio. `lastDeliveryOkAtMs` solo se actualiza ante una entrega real.
- **M3** — el watchdog renueva el WakeLock cada tick (`acquire(timeout)` reinicia el contador), así
  el timeout de 10h nunca expira en un monitoreo largo (uso de hasta 10h nocturno).
- **M4** — detecta sensor muerto (sin muestras en >`SAMPLE_STALE_MS` = 10s).
- **M5** — `onAccuracyChanged` loguea precisión baja/no confiable (no degrada, solo registra).

**Decisiones de diseño:**
- **Función pura `evaluateHealth(now, lastSample, lastDelivery, started)`** — sin estado ni Android,
  para testearla determinísticamente. El loop (delay + histéresis) NO se testea (flakiness con timers).
- **Warm-up de 60s** tras arrancar: no juzgar mientras se establece la conexión (el primer chunk
  tarda ~5s, el handshake puede demorar).
- **Histéresis** (2 checks DEGRADED seguidos): no oscilar por un bache puntual.
- **Umbrales:** sensor 10s (a 25Hz, 10s sin muestra es claramente anómalo); entrega 60s (los chunks
  van cada ~5s, 60s es generoso pero detecta desconexión real).
- **El reloj vibra en DEGRADADO** aunque la háptica sea secundaria para alarmas clínicas: acá es
  apropiado, porque una causa típica de DEGRADADO es el **teléfono desconectado** — la alerta tiene
  que venir del reloj, no del teléfono que no está.

**Tests:** `health_duringWarmup_isHealthyEvenIfStale`, `health_freshSampleAndDelivery_isHealthy`,
`health_staleSensor_isDegraded`, `health_staleDelivery_isDegraded`.

**Archivos afectados:** `SeizureMonitorService.kt` (watchdog, evaluateHealth, PipelineHealth,
notificación), `WearDataLayerManager.kt` (envío con Boolean), `AlarmStateManager.kt` (vibrateDegraded),
`strings.xml`.

---

## DEC-049: El producto NO guarda historial clínico — el cruce sueño/convulsiones se hace fuera de la app

**Fase:** SDD `context-logging` (exploración) | **Fecha:** Agosto 2026

**El pedido:**
Además de avisarle al cuidador, poder registrar las variables que el reloj mide (horas y calidad de
sueño, estrés) asociadas a cada convulsión detectada, para con el tiempo buscar patrones y ayudar a
reducir la frecuencia.

**Lo que la exploración encontró (los cuatro hechos que decidieron todo):**

1. **El reloj no puede dar sueño ni estrés.** No es una preferencia arquitectónica: Health Connect
   —donde vive ese dato— **no existe en Wear OS**, solo en el teléfono. Health Services, la API que
   sí corre en el reloj, expone métricas de fitness en vivo (pulso, distancia, calorías), no fases
   de sueño ni score de estrés. Agregarle Room al reloj no resolvería nada porque el dato no está ahí.
2. **La detección es de OSD, un tercero.** Su base local de eventos no declara un `<provider>`
   exportado, así que ninguna otra app la puede leer sin un cambio upstream. Lo único alcanzable hoy
   sin tocar OSD es un broadcast implícito (`uk.org.openseizuredetector.dialler.ALARM`), frágil por
   las restricciones de background de Android 8+.
3. **La correlación es retrospectiva.** No hace falta capturar nada en tiempo real: con un timestamp
   del evento alcanza para cruzar después contra datos de sueño ya fechados. Esto baja el requisito
   de "no perder ningún evento en vivo" a "tener la fecha", y es lo que hace viable la opción barata.
4. **El dato personal ya es exportable sin construir nada.** La descarga personal de Samsung Health
   entrega CSVs con sueño, estrés, HRV, pulso, SpO2 y temperatura. El permiso de socio que Samsung
   exige aplica a *una app que lee el dato en vivo*, **no** al usuario bajándose sus propios datos.

**Decisión:**
El producto **no incorpora almacenamiento de datos de salud**. El cruce entre convulsiones y
contexto se hace **fuera de la aplicación**: exportación manual periódica de los eventos de OSD y de
Samsung Health, unidos por fecha en un análisis offline. Este repo (`:wear`) **no cambia en nada**.

Procedimiento operativo en [`docs/RUNBOOK_EXPORTACION_DATOS.md`](docs/RUNBOOK_EXPORTACION_DATOS.md).

**Por qué esta y no las otras:**

| Alternativa | Por qué se descartó |
|---|---|
| Persistencia en el reloj (Room) | El dato de sueño/estrés es inalcanzable desde Wear OS (hecho 1). Además contradice DEC-046 y DEC-030, que definen el reloj como transporte sin memoria a propósito. |
| App/módulo companion en el teléfono | Reabre el módulo `:phone` retirado, introduce PHI persistente que exige gobernanza que hoy no existe (ver TODO-003), y depende de la señal más débil (el broadcast implícito). Meses de trabajo **antes** del primer dato. Queda disponible si el enfoque elegido demuestra que el dato rinde. |
| Pedirle a OSD que agregue campos de sueño/estrés a su registro de eventos | Es la opción más limpia a largo plazo —el diario ya es de ellos y le serviría a todos sus usuarios— pero no la controlamos. Vale como conversación upstream en paralelo, no como plan. |

**La razón de fondo (esta es la que importa dentro de seis meses):**
Hoy hay **cero eventos registrados** — OSD nunca llegó a funcionar con el reloj. Y para que una
comparación simple sea más que casualidad hacen falta del orden de 20-30 eventos confirmados; para
mirar dos o tres variables juntas, 30-60. A frecuencias reales de convulsiones nocturnas eso son
meses o años. **Construir infraestructura de producto para un dataset que todavía no existe es
invertir en el orden equivocado.** La exportación manual cuesta casi nada y contesta, en unos meses,
si vale la pena automatizar.

**Riesgo que esta decisión asume explícitamente:**
OSD detecta ~76% de las convulsiones reportadas y genera falsas alarmas por movimientos repetitivos.
Contar alarmas crudas como convulsiones corrompe el dato por los dos lados (falsos positivos, y
falsos negativos que contaminan el grupo de comparación). **La única etiqueta usable es la marca
manual genuino/falso del Data Sharing de OSD**, que solo existe si se viene registrando desde el
principio. Está documentado como Fase 1 del runbook.

**Lo que esta decisión NO cierra:**
El hueco de gobernanza. `CLINICAL_SIGNOFF.md` solo contempla constantes de lógica de detección; no
tiene categoría para "nueva clase de dato de salud almacenado". Hoy no hace falta porque no se
almacena nada — pero si algún día se retoma el enfoque companion, ese hueco es bloqueante. Anotado
como **TODO-003**.

**Archivos afectados:** ninguno del módulo `:wear`. Documentación:
`docs/RUNBOOK_EXPORTACION_DATOS.md` (nuevo),
`openspec/changes/context-logging/exploration.md` (la investigación completa con fuentes).

---

## DEC-050: Causa raíz confirmada del fallo de entrega reloj→OSD: AppKey mismatch en el Wear Data Layer

**Fase:** SDD `watch-osd-message-delivery` (exploración) | **Fecha:** Agosto–Septiembre 2026

**Decisión:** Registrar la causa raíz **confirmada** del bloqueante de campo. La Wear Data Layer API
solo entrega mensajes entre apps que comparten **package name Y certificado de firma** (Google lo
llama "AppKey" = `packageName` + hash del signing cert). Google Play Services
(`com.google.android.gms.persistent`) en el teléfono aplica ese filtro **antes** de que corra
cualquier listener de app. SeizureGuard (`com.seizureguard.wear`, firmado con una debug key propia)
y OSD (`uk.org.openseizuredetector`, release key de Graham) no comparten ninguno de los dos, así que
cada mensaje reloj→teléfono se descarta con la línea de log
`Failed to deliver message to AppKey[<oculto#...>, <hash hex de 40 chars del signing cert>]`.

**Qué reemplaza:** la hipótesis "OSD desactualizado" de la prueba de campo de junio 2026 (ver
`FIELD_TEST_NOTES.md`), que atribuía el "Data source fault" a que la V5.0.5 instalada no traía el
`SdDataSourceAw` completo (esa parte vivía en la rama `beta`, fusionada al release en V5.0.8).
**Esa hipótesis quedó refutada:** con OSD V5.0.8, V5.0.9 y la beta actual —todas con `SdDataSourceAw`
completo— el fallo es idéntico. Estar en una versión sin el soporte de reloj terminado era un
problema secundario real, pero no es por lo que la conexión falla.

**Evidencia:**

- **Logcat simultáneo de los dos dispositivos (2026-08-29):** con el logcat del teléfono a la vista,
  la línea `Failed to deliver message to AppKey[...]` la emite `com.google.android.gms.persistent`
  (Play Services), no OSD. El mensaje muere en el router de GMS; `SdDataSourceAw` nunca lo ve.
- **WearSD (`github.com/OpenSeizureDetector/WearSD`), la app de reloj oficial de OSD, funciona por
  una única razón:** declara `applicationId = uk.org.openseizuredetector` (igual que la app de
  teléfono) y se firma con la misma key. Usa el mismo patrón runtime (`MessageClient` +
  `NodeClient.connectedNodes`, sin `WearableListenerService` ni `<capability>` xml) y los mismos
  paths que SeizureGuard. Lo único distinto es la identidad (package + firma).
- **El flag `com.google.android.wearable.standalone` queda descartado como causa:** WearSD lo tiene
  igual que SeizureGuard.

**Qué se descartó como salida:**

| Alternativa | Por qué no |
|---|---|
| `DataClient` en vez de `MessageClient` | Mismo scoping por firma. No ayuda. |
| `CapabilityClient` / descubrimiento por capability | Mismo scoping por firma. No ayuda. |
| Parchear `SdDataSourceAw` para aceptar un package de terceros | Sobre el Data Layer es imposible: GMS descarta el mensaje antes de la entrega; `MessageClient`/`DataClient` no le dan a OSD forma de recibirlo. |
| "Shim" en el teléfono bajo `uk.org.openseizuredetector` reinyectando en OSD | OSD lee su data source en proceso, no por IPC. Sin efecto. |

No existe patrón soportado de mensajería Data Layer cross-package.

**Implicación:** cualquier arreglo que siga usando el Wear Data Layer exige que SeizureGuard presente
el **mismo AppKey que el build de OSD con el que habla** — es decir, mismo `applicationId`
(`uk.org.openseizuredetector`) y el mismo certificado de firma que la app OSD *instalada*. Como la
release keystore privada de Graham no es obtenible, eso solo cierra si el teléfono corre una OSD
compilada y firmada por el propio usuario. La alternativa que **no** depende del Data Layer es BLE:
SeizureGuard como periférico BLE hablándole a `SdDataSourceBLE` de OSD, igual que BangleSD y
PineTimeSD (bypassa Play Services y la regla de AppKey por completo).

**Estado:** causa raíz **CONFIRMADA**. El enfoque del fix ya está **ELEGIDO** — ver **DEC-051**
(Opción F: companion app `:phone` en el teléfono con el mismo AppKey que `:wear`, que reenvía a OSD
por HTTP al `SdWebServer` embebido). Queda descartada la Opción A que se barajaba acá (SeizureGuard
adopta `applicationId = uk.org.openseizuredetector`, mantiene `namespace = com.seizureguard.wear`, y
comparte una signing key con una OSD de teléfono compilada localmente): Graham no la endorsó.
Exploración y propuesta en `openspec/changes/watch-osd-message-delivery/`; espejo en engram
`sdd/watch-osd-message-delivery/explore`, detalle en
`architecture/seizureguard-aw-appkey-delivery-failure`.

**Documentación corregida junto con esta decisión:** `FIELD_TEST_NOTES.md` (bloque de
actualización), `docs/GUIA_CONECTAR_RELOJ_TELEFONO.md` (sección de causa real y paso 1) y
`docs/RUNBOOK_EXPORTACION_DATOS.md` (mención de paso en Fase 0).

**Archivos afectados:** ninguno del módulo `:wear` (el fix aún no está decidido). Solo documentación.

---

## DEC-051: Enfoque elegido para el fallo de entrega reloj→OSD: companion app en el teléfono (Opción F)

**Fase:** SDD `watch-osd-message-delivery` (propuesta) | **Fecha:** Septiembre 2026

**Decisión:** Adoptar la **Opción F**. En vez de cambiar la identidad del reloj o depender de una
OSD compilada por el usuario:

- El reloj (`:wear`) mantiene `applicationId = com.seizureguard.wear` y todo su pipeline (sensores,
  `CircularBuffer`, máquina de estados, guard DEC-047, watchdog DEC-048) sin cambios de lógica.
- Se agrega un **módulo `:phone`** nuevo al proyecto SeizureGuard, con **el mismo `applicationId` y
  la misma clave de firma** que `:wear`. Con eso el AppKey del Wear Data Layer coincide (las dos
  apps son del mismo autor) y los mensajes reloj→`:phone` se entregan.
- El `:phone` recibe `/osd/accel_data` y `/osd/settings` por `MessageClient` en un foreground
  service propio, y los reenvía a OSD como HTTP `POST /data` / `POST /settings` a
  `http://127.0.0.1:8080` (servidor `SdWebServer` embebido en OSD, verificado en la rama beta:
  NanoHTTPD puerto 8080 → `mSdDataSource.updateFromJSON(...)`).
- En OSD el usuario selecciona el data source **"Garmin"** (`SdDataSourceGarmin` es el receptor
  pasivo de ese POST). OSD queda como el **APK oficial de releases** — sin cambios en el código de
  OSD, sin compilar OSD.
- El estado de alarma vuelve al reloj vía el `:phone` (mecanismo y latencia máxima los define
  `sdd-design`; candidato: `:phone` hace `GET /data` a `SdWebServer` y reenvía `/osd/alarm_state` al
  reloj por `MessageClient`).

**Por qué sobre las alternativas:**

| Opción | Veredicto |
|---|---|
| A — el reloj adopta `uk.org.openseizuredetector` + firma compartida con una OSD compilada por el usuario | No endorsada por Graham; obliga a mantener una OSD propia para siempre; choca con la identidad de WearSD. **Descartada como dirección.** |
| B — reemplazar la app de reloj por WearSD | Pierde el watchdog/DEGRADED (DEC-048) y el guard (DEC-047); igual necesita firma compartida; ~6 h de batería. **Solo se conserva como experimento de validación.** |
| C — el reloj como periférico BLE | Graham: el SO del reloj Wear OS "probablemente controla el stack de Bluetooth", lo hace difícil. **Despriorizada.** |
| D — mensajería Data Layer cross-package | Técnicamente imposible; solo serviría un ingreso nuevo del lado de OSD (ej. Android Broadcasts). **Fuera de alcance.** |

**Basado en:** recomendación por mail de Graham Jones (mantenedor de OSD) — patrón "companion app en
el teléfono, igual que el detector de Garmin". Ver **DEC-050** (causa raíz confirmada) y
`openspec/changes/watch-osd-message-delivery/` (exploración + propuesta; espejo engram
`sdd/watch-osd-message-delivery/explore` #1195 y `.../proposal` #1198).

**Decisiones cerradas con el usuario** (entran a spec y diseño):

1. Retorno del estado de alarma reloj←OSD: mecanismo y tope de latencia **los define `sdd-design`**.
2. Manejo de fallo del `:phone`: **notificación de fallo al cuidador, SIN pantalla de estado**.
   Headless con aviso claro si deja de recibir del reloj o de reenviar a OSD.
3. `minSdk` del `:phone`: **API 26** (Android 8) — cubre el Samsung A52, coincide con el piso de OSD.
4. El código actual del reloj que habla directo a OSD (`WearDataLayerManager` → OSD): **se deja
   detrás de un flag de compilación**, no se borra.
5. Experimento de validación (WearSD + OSD beta, `docs/EXPERIMENTO_WEARSD_OSD_BETA.md`): **en
   paralelo, NO bloqueante**; el usuario lo corre cuando tenga el reloj, antes de `sdd-apply`. Si
   falla, se frena antes de escribir código.

**Reversión parcial de la decisión del 2026-06-05** (borrado de `:phone` como "código muerto que
duplicaba la inferencia de OSD"): este `:phone` **no hace inferencia, ni umbral, ni SMS** — es un
puente de transporte puro, necesario solo porque OSD no puede recibir mensajes Data Layer de una app
de otro package. Propósito distinto, no la redundancia que se removió.

**Estado:** enfoque **ELEGIDO**. Pendiente: `sdd-spec` + `sdd-design` (en curso), luego `sdd-tasks`
(PRs encadenados: el cambio supera el presupuesto de 400 líneas). `safety-reviewer` PASS
**obligatorio** antes de cualquier PR (toca la ruta de la alarma). El experimento de validación
corre antes de `sdd-apply`.

**Archivos afectados (previstos):** `settings.gradle.kts` y build raíz (re-agregar `:phone`); nuevo
`phone/` (bridge service, listener MessageClient, forwarder HTTP, watchdog watch→companion,
notificación de fallo); `wear/src/main/java/com/seizureguard/wear/data/WearDataLayerManager.kt` (el
peer pasa a ser el companion, detrás de flag);
`wear/src/main/java/com/seizureguard/wear/service/SeizureMonitorService.kt` (la salud de entrega mide
reloj→companion); config de firma compartida `:wear`/`:phone`;
`docs/GUIA_CONECTAR_RELOJ_TELEFONO.md` y `README.md` (instalación de dos APKs, data source = Garmin).

---

## DEC-052: Batch 1 de `watch-osd-message-delivery` — firma compartida `:wear`/`:phone`

**Fase:** SDD `watch-osd-message-delivery` (`sdd-apply`, Batch 1 de 9) | **Fecha:** Septiembre 2026

**Decisión:** Primer PR de la cadena (`sdd/watch-osd-delivery-01-signing`, PR #12) que sienta la base
para que el futuro módulo `:phone` (Batch 2) comparta `applicationId` + certificado de firma con
`:wear` — condición necesaria para que el AppKey del Wear Data Layer coincida (ver DEC-050, causa
raíz confirmada, y DEC-051, enfoque elegido).

**Qué se agregó (T1.1–T1.3 de `tasks.md`):**
- `signing.gradle.kts` (raíz): un único bloque `signingConfigs` que lee `keystore.properties`
  (gitignoreado) y lo expone vía `rootProject.extra` para que cualquier módulo lo consuma más
  adelante.
- `keystore.properties.template`: valores placeholder, sin secretos reales, para que quien clone el
  repo sepa qué completar.
- `build.gradle.kts` (raíz): `apply(from = "signing.gradle.kts")` para wirear el archivo nuevo.

**Por qué en un PR separado y sin tocar `wear/build.gradle.kts` todavía:** `tasks.md` divide
deliberadamente la firma compartida (Batch 1) de su consumo real por módulo (Batch 2 para `:phone`,
Batch 7 para retargetear `:wear`) para mantener cada PR chico y de bajo riesgo — el presupuesto de
revisión (`review_budget_lines`) es 400 líneas y el cambio completo se estimó en ~1.910, de ahí la
cadena de 10 PRs `stacked-to-main`.

**Contexto del override de GATE-0 (importante, no repetir en PRs futuros sin releer esto):**
`tasks.md` exige un PASS registrado del experimento de hardware real
(`docs/EXPERIMENTO_WEARSD_OSD_BETA.md`, WearSD + OSD beta en el Galaxy Watch 8 físico) antes de
arrancar cualquier tarea de código. El usuario no tuvo acceso al reloj (~1 semana) y decidió
explícitamente asumir GATE-0 como PASS para no bloquear el arranque de `sdd-apply` — una aceptación
de riesgo informada del dueño del proyecto, no un descuido. Registro completo en engram
`sdd/watch-osd-message-delivery/gate-0-override`.

Lo no verificado en hardware real sigue siendo específicamente si dos apps SeizureGuard firmadas
igual pueden intercambiar mensajes Wear Data Layer **en este Galaxy Watch 8** (Graham solo lo probó
en un Watch 7) — ese riesgo se concentra en Batch 7 / PR9 (retargeting de `:wear`), no en Batch 1.
El contrato HTTP con OSD (`SdWebServer`/`SdDataSource`) se verificó leyendo el código fuente real de
la rama beta, no se adivinó, así que tiene más confianza aun sin test en dispositivo. Las tareas
DV-1..DV-6 de verificación en hardware siguen siendo obligatorias para considerar el feature
"terminado" — este override no las salta ni las debilita.

**Estado:** Batch 1 mergeado a la cadena vía PR #12. Progreso espejado en engram
(`sdd/watch-osd-message-delivery/apply-progress`). Sigue Batch 2 (`:phone` module scaffold).

---

## DEC-053: Batches 2–3 de `watch-osd-message-delivery` — scaffold `:phone` y lógica pura del puente

**Fase:** SDD `watch-osd-message-delivery` (`sdd-apply`, Batches 2, 3a, 3b) | **Fecha:** Septiembre 2026

**Qué se hizo:**
- **Batch 2 (PR #13):** módulo `:phone` vacío — mismo `applicationId` que `:wear`, `namespace`
  propio, `minSdk 26`, sin dependencias nuevas. El bloque `signingConfigs` lo declara el propio
  `phone/build.gradle.kts` leyendo lo que expone `signing.gradle.kts`; Batch 7 (`:wear`) debe
  replicar ese patrón.
- **Batch 3a (PR #14):** `OsdPayloadCodec` (JSON + cuerpo `dataObj=` percent-encoded) y
  `OsdResponseParser` (`PostOutcome`). Cualquier código HTTP distinto de 200 clasifica como
  `UNREACHABLE`; un 200 con cuerpo desconocido o vacío, como `OSD_PARSE_ERROR`.
- **Batch 3b (PR #15):** `BridgeHealth` (`BridgeFault` + `evaluate`) y un test de loopback con un
  `ServerSocket` que verifica los bytes exactos que aceptaría el `NanoHTTPD` de OSD. Los umbrales
  (30 s / 20 s) son estrictos: exactamente en el límite todavía es `NONE`.

**Contrato verificado contra el código real de OSD (rama beta):** respuestas `OK` / `sendSettings` /
`ERROR` en `SdDataSource.updateFromJSON`; el marcador de "data source equivocado" es el mensaje
placeholder de `SdWebServer.java:87`, que vuelve con HTTP 200 cuando la fuente no es "Garmin".

**Decisión de testing:** los tests del codec y del loopback corren con Robolectric
(`@Config(sdk = [34])`), porque `org.json` en `android.jar` es un stub que lanza "not mocked" en JVM
puro. El diseño lo permitía; no se agregó ninguna dependencia de runtime.

**Deuda conocida:** el CI (`.github/workflows/ci.yml`) solo corre los tests de `:wear`; los tests de
`:phone` no tienen red automática. Además el trigger `branches: [main]` no corre en PRs apilados
sobre otra rama. Pendiente agregar `:phone:testDebugUnitTest` al workflow.

**Estado:** Batches 1–3b mergeados a `main`. Sigue Batch 4 (`OsdHttpForwarder`). El override de
GATE-0 (DEC-052) sigue vigente; DV-1..DV-6 siguen pendientes.

---

## DEC-054: Batches 4 y 5a de `watch-osd-message-delivery` — forwarder HTTP y servicio del puente

**Fase:** SDD `watch-osd-message-delivery` (`sdd-apply`, Batches 4 y 5a) | **Fecha:** Septiembre 2026

**Batch 4 (PR #16, mergeado):** `OsdHttpForwarder`. El host es la constante `127.0.0.1` (no se puede
apuntar a la LAN: el servidor de OSD no tiene autenticación), puerto 8080, timeouts de 4 s, sin
reintentos, sin cola, sin batching. Las llamadas son **bloqueantes** (no `suspend`): quien las use
debe llamarlas desde `Dispatchers.IO`. Nunca lanza: un fallo de red da `UNREACHABLE` (POST) o `null`
(`GET /data`). El parseo del estado de alarma queda para Batch 5b.

**Batch 5a (PRs #17 y #18, abiertos):** el cambio pesaba 670 líneas contra un presupuesto de 400, así
que se entregó como **dos PRs apilados**: 5a-1 (validación de mensajes + estado de salud) y 5a-2
(el servicio en primer plano). Decisión tomada con el usuario.

**Hallazgos de la revisión de Batch 3/4 resueltos en 5a:**
1. **Relojes de salud inicializados en "ahora", no en 0.** Con 0, `evaluate()` reportaba
   `OSD_UNREACHABLE` / `NO_WATCH_DATA` en el primer tick (falsa alarma al arrancar).
2. **Latch de fallos.** `BridgeHealth.evaluate()` solo mira el último resultado, así que un
   `WRONG_DATASOURCE` se borraba con un solo `OK`. El diseño dice "latched immediately" sin definir
   cuándo se limpia. Se eligió la lectura más segura: `WRONG_DATASOURCE` y `OSD_PARSE_ERROR` se
   mantienen hasta **2 `OK` consecutivos** (`LATCH_CLEAR_OK_STREAK`; poner 1 para limpiar con uno).
   `UNREACHABLE` reinicia la racha; `SEND_SETTINGS` es neutro.

**Decisiones de implementación (desviaciones o detalles que el diseño no fijaba):**
- **DV-1 (falta `BLUETOOTH_CONNECT`):** en API 34+ el servicio NO llama `startForeground`; registra
  ERROR, muestra una alerta de importancia alta ("el monitoreo NO está funcionando") y se detiene sin
  reiniciarse. Si `POST_NOTIFICATIONS` también está denegado, esa alerta puede no verse: DV-1 sigue
  necesitando una prueba en dispositivo real.
- **Permisos extra:** `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_CONNECTED_DEVICE`. No estaban en
  T5.1 pero, con `targetSdk 34`, un servicio `connectedDevice` lanza `SecurityException` sin ellos.
- **Batería por defecto = 100** si OSD pide los ajustes (`sendSettings`) antes de que el reloj haya
  mandado los suyos (`DEFAULT_HANDSHAKE_BATTERY`). Evita una falsa alerta de batería baja, pero puede
  ocultar una batería baja real durante los primeros segundos. **Abierto a revisión.**
- **Solo los chunks de acelerómetro válidos** reinician el reloj de "sin datos del reloj"; los
  malformados se descartan y se cuentan, así que una ráfaga de basura termina en `NO_WATCH_DATA`.
- **Pausa de 250 ms** antes de consultar `/data` solo tras un `OK` de acelerómetro, no tras
  `SEND_SETTINGS`.
- El largo exacto del array de acelerómetro (125) y los rangos de `battery` (0..100) y `sample_freq`
  (1..200) los fijó el agente; validar contra el reloj real.

**Modelo de concurrencia:** el callback de `MessageClient` (hilo principal) solo valida y deja el
chunk en un slot único "gana el último"; un solo worker lo drena y hace los POST/GET bloqueantes en
`Dispatchers.IO`. Un OSD lento demora únicamente al worker; hay como máximo un chunk pendiente y los
reemplazados se cuentan y se loguean (WARN), sin cola ni reintento. Las consultas de estado son
mutuamente excluyentes. `onDestroy`: quitar listener, liberar WakeLock (con `isHeld`), cerrar canal,
cancelar scope.

**Verificado contra el código real de OSD (rama beta):** `SdWebServer` llama a `updateFromJSON` en el
hilo de NanoHTTPD, y `alarmState` se escribe antes de que el POST devuelva. Eso respalda la pausa de
250 ms. No se encontró nada que contradiga el diseño.

**Sin verificar, no adoptado:** el agente sospechó que declarar otro permiso de instalación (por
ejemplo `CHANGE_NETWORK_STATE`) permitiría un servicio `connectedDevice` sin `BLUETOOTH_CONNECT` en
runtime, lo que esquivaría DV-1. No se agregó; conviene confirmarlo en la documentación de Android.

**Deuda:** el CI solo corre los tests de `:wear`, y no se dispara en PRs apilados sobre una rama
distinta de `main`. Conviene sumar `:phone:testDebugUnitTest` al workflow.

**Estado:** Batches 1–5a mergeados (5a-1 #17 y 5a-2 #18, con CI verde). Sigue 5b (ver DEC-055).
GATE-0 sigue asumido como PASS (DEC-052); DV-1..DV-6 pendientes.

---

## DEC-055: Batch 5b de `watch-osd-message-delivery` — relay del estado de alarma y notificaciones de falla

**Fase:** SDD `watch-osd-message-delivery` (`sdd-apply`, Batch 5b) | **Fecha:** Septiembre 2026

**Qué se hizo (PRs #19 y #20, mergeados; el lote pesaba ~480 líneas, se entregó apilado):**
- `AlarmStateRelay`: reenvía al reloj el estado de alarma que OSD devuelve en `GET /data`, por
  `/osd/alarm_state` con `{"alarm_state","alarm_phrase"}`. Manda cuando el estado cambia y como
  refresco cada 10 s.
- `BridgeNotifications`: reemplaza a la notificación mínima de 5a (`BridgeStatusNotification`,
  borrada; se conservan los ids de canal y la alerta de "no se puede iniciar"). Dos canales:
  `osd_bridge_status` (LOW, ongoing) y `osd_bridge_fault` (HIGH, `CATEGORY_ERROR`, suena y vibra).
- `OsdBridgeServiceTest` (Robolectric, 7 tests) y `AlarmStateRelayTest` (8). Suite de `:phone`: 65.

**Silencio ante fallo (punto de seguridad de vida):** si el cuerpo de `/data` es nulo, está vacío,
malformado, o `alarmState` falta, no es número o es negativo, el celular **no manda nada** y nunca
inventa un código de falla. El detector de ese caso es el watchdog de staleness del reloj, no el
celular. Un poll fallido después de uno bueno tampoco genera refresco.

**Notificación de falla:** se publica al aparecer o cambiar la falla, se vuelve a publicar cada 60 s
mientras dure (`setOnlyAlertOnce(false)`, para que suene cada vez) y se cancela sola cuando el puente
vuelve a `NONE`. Se evalúa en el tick de salud de 10 s, así que el re-post cae entre 60 y 70 s.

**Desviaciones del diseño:**
1. El refresco de 10 s se evalúa en la cadencia del poll (≤5 s), así que el refresco efectivo es de
   10–15 s. Sigue muy dentro de la ventana de 40 s del reloj.
2. El observer se cablea por defecto en `onCreate` con un compuesto anónimo; los tests pueden
   reemplazar `service.observer`.
3. `WearAlarmSender.send` bloquea en la corrutina del poll con timeout de 3 s y mantiene `pollMutex`
   mientras envía.
4. Robolectric no puede probar el listener real de `MessageClient` (no hay Play Services). El
   registro del listener queda como ítem de verificación en dispositivo.

**Riesgo abierto (DV-4, no debilitado):** si OSD dejara de analizar pero `GET /data` siguiera
devolviendo el último `alarmState`, el celular seguiría reenviándolo como refresco. El watchdog del
reloj vería mensajes recientes y no marcaría la falla. Es la mayor exposición de seguridad de vida de
esta arquitectura y hay que llevársela al `safety-reviewer` antes de Batch 7 (ver si `/data` trae un
timestamp o contador que permita detectar un estado congelado).

**Proceso:** al mergear #20 apareció un conflicto en `OsdBridgeService.kt` (ambos PRs tocaron el
cableado de `onCreate` tras el squash de #19). Se resolvió quedando con la versión de 5b-2, que además
cablea las notificaciones; los 65 tests pasan sobre el resultado.

**Estado:** Batches 1–5b mergeados. Sigue Batch 6 (`SetupActivity` + `BootReceiver`). GATE-0 sigue
asumido como PASS (DEC-052); DV-1..DV-6 pendientes.

---

## DEC-056: `safety-reviewer` pre-Batch 7 dio BLOCK; se abre el Batch 5c y se crea el registro de hallazgos

**Fase:** SDD `watch-osd-message-delivery` (checkpoint entre Batch 6 y Batch 7) | **Fecha:** 2026-09-19

**Decisión:** no arrancar el Batch 7 (`:wear`) hasta cumplir las condiciones de desbloqueo del
`safety-reviewer`. Registro completo, con estado por hallazgo, forma de verificarlo y revisiones
hechas/pendientes, en **`docs/SAFETY_FINDINGS_WATCH_OSD.md`** (fuente de verdad para revisar si
quedó arreglado). Detalle del veredicto en engram `sdd/watch-osd-message-delivery/safety-review-pre-batch7`.

**Hallazgos principales (F1–F8):**
- **F1 (crítico):** el relay del teléfono reenviaba `alarmState` sin mirar la salud del puente ni si
  OSD seguía analizando; con OSD congelado o en otra fuente de datos el reloj recibe `0` para siempre
  y nunca marca DEGRADED.
- **F2 (crítico, verificado):** OSD emite estados 3–7; el reloj hace `else -> vibrateAlarm()` para
  todo ≥2, así que un FAULT de OSD (p. ej. batería baja del teléfono) vibra como convulsión.
- **F3–F8:** DEGRADED de un solo pulso; falla invisible si las notificaciones están bloqueadas;
  listener muerto sin reintento; rangos de datos laxos; flavor `osdDirect`; boot/Doze.

**Decisión de proceso — Batch 5c:** corrección de F1 solo en `:phone`, antes de tocar el reloj.
Regla "fail-loud": los estados ≥1 se reenvían siempre; el `0` y el refresco solo si el puente está
sano (`BridgeFault == NONE`) y el timestamp de datos de OSD avanzó dentro de una ventana
(`OSD_DATA_FRESH_MS`, propuesta 15 s); si no, silencio para que el watchdog del reloj lo detecte.
Nuevo fault `OSD_DATA_STALE`. Nunca se fabrica un código de falla.

**Pendiente de tu decisión (clínica, no técnica):** política para los estados 3–7 de OSD en el reloj
(propuesta: 2, 3 y 5 → alarma; 4 y 7 → falla del sistema; 6 → sin vibración) y firma de las
constantes de detección en `CLINICAL_SIGNOFF.md` (la aprobación previa #1196 no reemplaza la firma).

**Corrección de proceso:** la nota "ya aprobado, no re-levantar" de `tasks.md` T7.3 contradecía la
regla del skill y se elimina. Los Batches 1–6 se mergearon sin `safety-reviewer` ni RDD por batch; la
ruta de alarma del teléfono se revisó de forma adversarial por primera vez recién ahora.

**Estado:** Batch 7 BLOQUEADO. Batch 5c en curso. GATE-0 sigue asumido como PASS (DEC-052).

---

## DEC-057: Las fallas del sistema son totalmente silenciosas; solo una emergencia interrumpe

**Fase:** SDD `watch-osd-message-delivery` (política de F2 del `safety-reviewer`) | **Fecha:** 2026-09-19

**Decisión (del usuario):** ninguna falla del sistema produce sonido ni vibración, ni en el celular
ni en el reloj. La única interacción con el cuidador es una **emergencia** (correr a la cama). El
cuidador usa **otro celular** distinto del que corre OSD. Se eligió la opción A ("fallas totalmente
silenciosas") sobre la B ("avisar solo si la falla dura más de N minutos"), que era la recomendada.

**Reemplaza:** la decisión #2 de DEC-051 (falla del `:phone` = notificación al cuidador), el criterio
de éxito "falla visible en ≤60 s" entendido como alerta, y los hallazgos F3 (vibración persistente
de DEGRADED) y F4 (que la notificación llegue a una persona) del registro de seguridad.

**Política de estados de OSD en el reloj:**

| Estado | Comportamiento |
|---|---|
| 0 OK | Sin acción |
| 1 WARNING | Sin cambios |
| 2 ALARM, 3 FALL, 5 MANUAL | Alarma de convulsión |
| 4 FAULT, 7 NETFAULT, valor desconocido | Falla del sistema: **silenciosa** (indicador visual pasivo + registro) |
| 6 MUTE | Sin vibración |

**Riesgos aceptados de forma consciente:**
1. Un sistema caído se ve igual que una noche tranquila. Mitigación prevista, sin interrumpir a
   nadie: indicador pasivo, registro de cada período de falla y un resumen silencioso a la mañana.
2. OSD fuerza FAULT por encima de ALARM (`SdServer.java:1274-1281`): con una falla activa (por
   ejemplo batería baja del teléfono) una convulsión **no genera alarma**. Mitigación de proceso:
   dejar el teléfono de OSD cargando durante la noche.
3. MUTE también tapa las alarmas y cancela el SMS mientras esté activo.

**Configuración necesaria fuera de este repo:** en OSD hay que apagar `AudibleFaultWarning` (por
defecto está activada y suena por el canal de alarma). Debe figurar en las instrucciones de
`SetupActivity` y en `CAREGIVER_GUIDE.md`.

**Mute con el cuidador en otro celular.** La alarma de OSD suena por `USAGE_ALARM`
(`SdServer.java:951-1000`), así que atraviesa el modo silencio del timbre (depende del volumen de
alarma y de la excepción de alarmas de No Molestar). Pero el SMS a un celular distinto no se puede
controlar desde nuestro código. Vía candidata: correr OSD también en el celular del cuidador con la
fuente de datos "Network", que consulta `http://<IP>:8080/data` cada 2 s
(`SdDataSourceNetwork.java:32,309`) y suena por el canal de alarma. **Sin verificar de punta a
punta:** que ambos estén en la misma red, qué pasa cuando se corta el enlace (avisa NETFAULT), y que
el servidor web de OSD queda expuesto sin autenticación en la red local. Alternativa sin código:
configurar en el celular del cuidador No Molestar con el contacto del paciente como prioritario.

**Trabajo derivado (aún sin hacer):** Batch 5d en `:phone` (notificaciones de falla silenciosas,
registro de fallas, resumen matutino, texto de setup) y ajustes del Batch 7 en `:wear` (DEGRADED solo
visual, mapeo de estados). Las tareas T7.5/T7.6 de `tasks.md` hay que reescribirlas.

**Estado:** política definida; implementación pendiente. Registro en
`docs/SAFETY_FINDINGS_WATCH_OSD.md`.

---

## DEC-058: Batch 5d de `watch-osd-message-delivery` — implementación de la política de fallas silenciosas

**Fase:** SDD `watch-osd-message-delivery` (`sdd-apply`, Batch 5d, PRs #24, #25 y #26 mergeados) | **Fecha:** 2026-09-19

**Qué se hizo (solo `:phone`, sin tocar el reloj):**
- **Notificaciones silenciosas (#24):** canal nuevo `osd_bridge_fault_silent` (importancia baja, sin
  sonido, vibración ni luces, sin pantalla completa). La falla se publica al aparecer o cambiar, se
  actualiza si cambia el tipo y se cancela al resolverse; ya no se repite cada 60 s. El canal viejo
  `osd_bridge_fault` (HIGH) se borra. Los avisos de "no se puede iniciar" (DV-1) y de "reiniciá el
  puente" (DV-2) usan el mismo canal silencioso.
- **Registro de fallas y caídas del servicio (#25):** `FaultLog` guarda períodos de falla (tipo,
  inicio, fin) durante 14 días y hasta 300 entradas. `ServiceLiveness` guarda cada ~60 s una marca de
  "seguía vivo"; si al arrancar hay un hueco de más de 120 s, con `was_bridging` y sin Stop limpio,
  registra un período `SERVICE_DOWN`.
- **Resumen matutino (#26):** notificación silenciosa a las 8:00 (canal `osd_bridge_summary`) con las
  interrupciones de las últimas 12 h. Usa `AlarmManager.setAndAllowWhileIdle` (inexacto, sin permiso
  de alarmas exactas) y un receptor que no depende del servicio; se arma desde `SetupActivity` y
  `BootReceiver`. Si el usuario detuvo el puente, no publica nada.
- **Setup:** indica apagar "Enable Audible System FaultWarnings" en OSD y dejar el teléfono cargando.

**No cambió:** la detección de fallas, el latch, el relay de alarma (fail-loud de 5c) ni el reloj.

**Límites conocidos (aceptados, ver riesgo A1):** el resumen no llega tras un cierre forzado de la
app ni con el teléfono apagado; un Stop del usuario no se distingue de olvidarse de iniciar; un
servicio que reinicia en <120 s no se registra. Sin probar en dispositivo: Doze y el comportamiento de
la importancia baja en One UI.

**Proceso:** los PRs apilados dieron conflictos al mergear (checkbox de `tasks.md`, y en 5d-3 cuatro
archivos). Se verificó que `main` fuera idéntico al tip de la rama base en `phone/` y se tomó la
versión de la rama superior. Suite de `:phone`: 120 tests.

**Estado:** Batches 1–6, 5c y 5d mergeados. Pendiente antes de Batch 7: enmendar spec WCT-8, firmar
constantes, reescribir `CAREGIVER_GUIDE.md` y la sección 4 de `HARDWARE_RUNBOOK.md`, y un segundo
`safety-reviewer`. Batch 7 sigue bloqueado.

---

## DEC-059: Firma de las constantes de detección del puente reloj ↔ OSD y enmienda de los specs

**Fase:** SDD `watch-osd-message-delivery` (desbloqueo del Batch 7) | **Fecha:** 2026-09-19

**Decisión (del usuario):** firmar **15 de 16** constantes tal cual se propusieron; quedan escritas en
`CLINICAL_SIGNOFF.md` (sección "Firmadas"). Firmadas: `WATCHDOG_INTERVAL_MS` 10 s, `DELIVERY_STALE_MS`
40 s, `ALARM_STATE_STALE_MS` 40 s, histéresis 2, warm-up 60 s, `SAMPLE_STALE_MS` 10 s, techo de "falla
visible" (60 s enlace, ≈75 s OSD congelado), `ALARM_KEEP_ALIVE_MS` 10 s, `OSD_DATA_FRESH_MS` 15 s,
`NO_WATCH_DATA_MS` 30 s, `POST_OK_STALE_MS` 20 s y `MAX_POST_FAILURES` 3, `LATCH_CLEAR_OK_STREAK` 2,
techo del relay 8 s, `sample_freq` exactamente 25 y `DEFAULT_HANDSHAKE_BATTERY` 100.

**Sin firmar:** `techo_latencia_clinica` (no tenía valor propuesto; es una decisión clínica del usuario).

**Nombre de la firma:** el usuario no indicó cómo quería figurar; se registró `agus-chaud` (el usuario
de Git del proyecto). Puede corregirse en `CLINICAL_SIGNOFF.md`.

**Cuentas de peor caso firmadas** (calculadas, no medidas en hardware): enlace reloj→celular 60 s
(hoy 120 s); estado de alarma que deja de llegar 60 s; OSD congelado con el celular consultando
≈75 s; sensor muerto 30 s; primer DEGRADED tras arrancar ≈80 s.

**Pendiente de código (por la firma):** el parser sigue aceptando `sample_freq` de 1 a 200; hay que
endurecerlo a exactamente 25 (hallazgo F6). Se hará con los demás pendientes de `:phone` (reintento
del listener F5, acelerómetro plano F6).

**Specs enmendados** (`openspec/changes/watch-osd-message-delivery/specs/`, sin trackear en el repo):
`watch-companion-transport.md` (tabla de estados 0–7, DEGRADED solo visual, constantes firmadas,
cambios permitidos en `:wear`) y `phone-companion-bridge.md` (fallas silenciosas, fail-loud del relay,
resumen matutino).

**Estado del Batch 7:** sigue bloqueado hasta reescribir `CAREGIVER_GUIDE.md` y la sección 4 de
`HARDWARE_RUNBOOK.md`, resolver el pendiente de código de `:phone` y pasar un segundo `safety-reviewer`.

---

## DEC-060: Batch 5e (endurecimiento del puente) y reescritura de las guías para el flujo de dos apps

**Fase:** SDD `watch-osd-message-delivery` (Batch 5e, PRs #27 y #28 mergeados con CI verde; documentación de cuidadores) | **Fecha:** 2026-09-19

**Batch 5e (solo `:phone`, sin tocar el reloj):**
- **`sample_freq` exactamente 25** (`SAMPLE_FREQ_HZ`): cualquier otro valor se descarta y no se reenvía a
  OSD. Aplica la constante firmada en DEC-059. El handshake usa 25 Hz por defecto, y un mensaje de
  ajustes descartado nunca pisa los ajustes válidos ya guardados, así que OSD no queda sin ajustes ni
  recibe una frecuencia equivocada. Efecto colateral: la batería informada puede quedar en el valor
  por defecto (100) o vieja hasta que el reloj mande ajustes válidos.
- **Chunk congelado:** un chunk de 125 muestras donde todas son idénticas se rechaza (comparación
  exacta, sin tolerancia). No se reenvía y no cuenta como mensaje válido del reloj, así que el fault
  silencioso `NO_WATCH_DATA` lo hace visible a los 30 s. **Es una regla nueva que no está en la tabla
  de constantes firmadas.** Riesgo sin verificar: un sensor muy cuantizado podría producir 125 valores
  iguales en 5 s y ser rechazado por error; no hay datos de la cuantización real del Watch 8.
- **Listener de mensajes (F5):** si `addListener` falla se reintenta con backoff de 5 s a 60 s; si pasan
  más de 60 s sin mensajes válidos del reloj se quita y se vuelve a registrar el listener, como
  máximo una vez por minuto. No se agregó ninguna alerta ni fault nuevo. Constantes de recuperación
  (no requieren firma): `LISTENER_RETRY_INITIAL_MS` 5 s, `LISTENER_RETRY_MAX_MS` 60 s,
  `LISTENER_REREGISTER_AFTER_MS` 60 s, `LISTENER_REREGISTER_MIN_INTERVAL_MS` 60 s. Si el reloj está
  legítimamente ausente (fuera de la muñeca) el listener se re-registra una vez por minuto: inofensivo
  pero ruidoso en el log.
- **Sin verificar:** si Play Services descarta listeners tras una actualización, si quitar y volver a
  agregar el listener con mensajes en vuelo puede perder alguno, y los modos de falla reales de
  `addListener`.
- Tests de `:phone`: 124 en el PR #27 y 128 en el #28 (había 120). El #27 pasó el CI; el #28 está apilado
  y el CI no corre sobre él hasta retargetearlo a `main`.

**Guías reescritas (2026-09-19):**
- **`CAREGIVER_GUIDE.md`** (completa): banner de "no probado en el equipo real", las tres piezas, la
  política de fallas silenciosas dicha sin vueltas, cuándo el sistema no protege, checklist nocturno
  por dispositivo, cómo configurar el teléfono del cuidador (contacto prioritario y prueba), y cómo leer
  el resumen matutino. Se conservó el aviso, los primeros auxilios y la sección de falsa alarma.
- **`HARDWARE_RUNBOOK.md` §4**: flujo de dos apps (firma compartida, configurar OSD, iniciar el puente,
  verificar datos y retorno de alarma), tabla DV-1..DV-7, chequeos extra y qué hace hoy cada build y qué
  falta (Batch 7 y 5e). La sección 5 recibió solo una nota: su tag `SdDataSourceAw` y el modo
  secuencial ya no aplican con la fuente Garmin.
- Los textos citados de la app (resumen, botones, avisos) se verificaron contra `strings.xml`. El aviso
  de batería baja de OSD viene activado por defecto (`PhoneBatteryAlarmActive` = true), así que esa
  advertencia de la guía es correcta; el umbral no se verificó.
- **La app del reloj no tiene botón de MUTE:** el mute es de OSD, así que la guía habla solo de eso.
- No se encontró un ajuste para apagar el servidor web de OSD, y la etiqueta exacta "Garmin" de la
  fuente de datos quedó sin verificar.

**Pendiente de revisión humana:** el texto médico de la guía se conservó sin cambios, pero dice
"llamá a emergencias si dura más de 3 minutos" y "la mayoría pasan solas en 1–2 minutos"; la referencia
habitual es 5 minutos. Confirmarlo con el médico tratante antes de imprimirla.

**Documentos que siguen mandando por el camino roto:** `docs/GUIA_CONECTAR_RELOJ_TELEFONO.md` (le toca el
Batch 9, T9.1) y `docs/RUNBOOK_EXPORTACION_DATOS.md` todavía indican activar "Android Wear data source".

**Estado:** Batch 5e mergeado. Batch 7 sigue bloqueado. Falta: firmar `techo_latencia_clinica`, revisar el
texto médico de la guía y pasar un segundo `safety-reviewer` de re-chequeo.

---

## DEC-061: Segundo `safety-reviewer` (R6) — verificación contra código real, PARCIAL PASS

**Fase:** SDD `watch-osd-message-delivery` (re-chequeo antes del Batch 7) | **Fecha:** 2026-09-28

**Qué se hizo:** a diferencia de R5 (que revisó un plan), este re-chequeo leyó el código ya mergeado
en `origin/main` y lo comparó línea por línea contra `docs/SAFETY_FINDINGS_WATCH_OSD.md`. Detalle
completo en la sección 9 de ese registro; espejo en engram `sdd/watch-osd-message-delivery/safety-review-r6`.

**Resultado:** ningún hallazgo de F1-F8 estaba mal documentado. F1, F4, F5 y F6 confirmados arreglados
en código exactamente como se afirmaba; F2, F3, F7 y F8 confirmados sin implementar, como corresponde.
Se confirmó con `git diff --stat` que el Batch 7 no dejó ningún cambio filtrado en `wear/`.

**Tres hallazgos documentales, corregidos el mismo día:**
1. La copia local (sin trackear) de `tasks.md` en la copia de trabajo estaba congelada desde
   antes del Batch 1, sin las tareas T7.5-T7.7 de la enmienda DEC-057. Riesgo real: cualquier agente
   que trabajara leyendo por path absoluto en el árbol principal (instrucción usada varias veces en
   este SDD) vería el plan viejo. Corregida: sobrescrita con el contenido tracked de `origin/main`.
2. `CAREGIVER_GUIDE.md` afirmaba silencio total en el reloj ante una falla, pero el reloj real
   (sin el Batch 7) todavía vibra 2 pulsos en DEGRADED. Corregida con una excepción explícita.
3. `CLINICAL_SIGNOFF.md` y `HARDWARE_RUNBOOK.md` §4.8 seguían marcando como "pendiente" el Batch 5e,
   ya mergeado. Corregidos.

**Estado:** Batch 7 sigue siendo el próximo paso. Condiciones sin cambios: firmar
`techo_latencia_clinica` y revisión humana del texto médico de `CAREGIVER_GUIDE.md`.

---

## DEC-062: Firma de `techo_latencia_clinica` (40 s) y aprobación del texto médico de la guía

**Fase:** SDD `watch-osd-message-delivery` (antes del Batch 7) | **Fecha:** 2026-09-30

**Qué se decidió:** el usuario firmó `techo_latencia_clinica` = **40 s**: tiempo máximo aceptable
entre el inicio de la convulsión y la alarma. No había valor propuesto; se le presentaron dos
opciones (30 s, estricta y probablemente incumplible; 60 s, realista con margen) y eligió 40 s.
Además aprobó el texto médico de `CAREGIVER_GUIDE.md` ("llamar a emergencias si dura más de
3 minutos") como correcto; no se vuelve a pedir su revisión.

**Presupuesto del techo:** paso de análisis de OSD ≤ 5 s + relay de alarma ≤ 8 s (ya firmado) =
hasta 13 s. Quedan ≈ 27 s para que el modelo reconozca la convulsión dentro de su ventana de 30 s.
Ese tiempo de reconocimiento **no está medido**; se valida en las pruebas con hardware (DV). Si la
medición lo supera, se revisa el valor y se vuelve a firmar.

**Consecuencia:** las 16 constantes de detección quedan firmadas. El stride de 5 s queda dentro
del techo. No cambia código del Batch 7. Nada más bloquea el inicio del Batch 7.

---

## DEC-063: Batch 7 (`:wear`) implementado en 4 PRs apilados; `safety-reviewer` R7 PASS condicional

**Fase:** SDD `watch-osd-message-delivery`, Batch 7 | **Fecha:** 2026-09-30

**Qué se hizo:** `sdd-apply` implementó T7.1 a T7.7 en un árbol de trabajo aparte (desde
`origin/main`), sin push ni PR. Cortes apilados, cada uno bajo 400 líneas:
- `07a` (T7.1, T7.2 y script de instalación): flavors `companion`/`osdDirect`, `osdDirectRelease`
  deshabilitada (F7), destino del reloj según el flavor. 198+/14−.
- `07b-1` (T7.3, T7.4): vigilancia del estado entrante, constantes firmadas 10 s / 40 s / 40 s,
  tests de peor caso. 216+/20−.
- `07b-2` (T7.5 a T7.7): política de estados DEC-057 (F2), DEGRADED sin vibración (F3), techos
  firmados 60 s / ≈75 s aceptados. 234+/50−.
- `07c` (arreglo H7-1): la pantalla del reloj muestra "⚠ MONITOREO DEGRADADO" y nunca presenta un
  estado vencido como vigente ni oculta una alarma vigente. 240+/20−.

**Revisión:** `safety-reviewer` R7 (PARTIAL PASS, 2 hallazgos ALTOS) y re-chequeo R7b tras los
arreglos: **PASS condicional**. Detalle en `docs/SAFETY_FINDINGS_WATCH_OSD.md` sección 10.
Condición: mergear `07b-2` y `07c` seguidos, sin instalar un build intermedio.

**Cambios no pedidos en las tareas, aceptados:** `MainActivity` usa la misma clasificación que la
vibración (antes mostraba "ALARMA" para cualquier estado ≥ 2); alias de tareas Gradle
`testDebugUnitTest`/`lintDebug` para que CI siga corriendo ambos flavors.

**Documentación actualizada:** `CAREGIVER_GUIDE.md` (fallas silenciosas también en el reloj, dónde
ver DEGRADED, falla de OSD, MUTE), `HARDWARE_RUNBOOK.md` (APK `companion`), `CLINICAL_SIGNOFF.md`
(política de estados). Estos textos describen el comportamiento **después** de mergear el Batch 7.

**PRs:** #29 (`07a`), #30 (`07b-1`), #31 (`07b-2`), #32 (`07c`, incluye además el arreglo H7-3: el estado mostrado vuelve a OK al iniciar y detener).

**Seguimientos:** H7-4 a H7-7 (sección 10 del registro). Tests: `:wear:testDebugUnitTest` verde en
ambos flavors (105 tests cada uno), re-corrido por el orquestador en la punta de `07c`.

---

## DEC-064: Mejoras de seguridad del reloj H7-4 a H7-7 (PR 7d) y texto del cartel de MUTE

**Fase:** SDD `watch-osd-message-delivery`, seguimiento del Batch 7 | **Fecha:** 2026-09-30

**Qué se hizo:** en dos ramas apiladas desde `main` (`07d-1` 199+/24−, `07d-2` 307+/15−):
- H7-5: la vigilancia del enlace usa `elapsedRealtime` (reloj que no se puede ajustar); un cambio de
  hora del sistema ya no la ciega.
- H7-4: la frescura del estado de alarma se publica como snapshot atómico; una ALARMA recién llegada
  se muestra al instante.
- H7-6: MUTE muestra **"SILENCIADO, no avisa convulsiones"** (texto elegido por el usuario; la
  propuesta "sin alarmas" se descartó por ambigua, hallazgo R8-F1).
- H7-7: colores legibles (falla violeta claro, ALARMA rojo claro, todos ≥ 4.5:1 y testeados), números
  fuera de rango → falla silenciosa (nunca alarma), test de 75 s simulado de verdad, cartel
  "VERSIÓN DE PRUEBA" en `osdDirect`.
- R8-F3: la pantalla se actualiza antes de vibrar.

**Revisión:** `safety-reviewer` R8: `07d-1` PASS, `07d-2` PARTIAL PASS resuelto (sección 11 del
registro). Sin constantes nuevas ni cambios en cuándo vibra el reloj. Tests `:wear` 130/130 en ambos
flavors, re-corridos por el orquestador. Guía del cuidador actualizada (MUTE, color de falla,
"hasta 1 minuto y medio", "VERSIÓN DE PRUEBA").

---

## DEC-065: Batch 8 en tres partes; una diferencia de versión reloj/celular se anota y nunca corta la detección

**Fase:** SDD `watch-osd-message-delivery`, Batch 8 | **Fecha:** 2026-10-01

**Qué se decidió:** el Batch 8 (compatibilidad de versiones) se divide en 8a (el reloj informa su
versión, PR #35), 8b (el celular compara y anota) y 8c (aviso silencioso). En 8b:
- Versión del contrato del celular = 1; resultados `MATCH`, `MISMATCH` y `MISSING`. Un reloj que no
  manda versión (anterior a la 8a, o incluso al Batch 7, mismo `applicationId`) cuenta como
  incompatible.
- La diferencia se anota como período `VERSION_MISMATCH` propio en el registro de fallas. **No** es un
  `BridgeFault`: si lo fuera, el celular dejaría de mandar OK al reloj y el reloj quedaría en DEGRADED.
- **Nunca se corta el envío a OSD** por una diferencia de versión. La spec PCB-9 dice "rather than
  proceeding"; se interpreta como "no ignorarla en silencio", porque cortar el envío garantizaría
  convulsiones sin detectar. Los parsers estrictos ya rechazan mensajes fuera de contrato.

**También:** el CI ahora corre tests y lint de `:phone` (PR #36). El check nuevo todavía no es
obligatorio en la regla de `main`. *Actualización 2026-10-01: ya es obligatorio; los checks del reloj y del
teléfono son requeridos en `main`.*

**Revisión:** `safety-reviewer` R10: 8b-1 PASS, 8b-2 PASS condicionado a R10-F3 en 8c (sección 13).

---

## DEC-066: Batch 8c — una diferencia de versión se muestra en silencio y persiste hasta que coincidan

**Fase:** SDD `watch-osd-message-delivery`, Batch 8c | **Fecha:** 2026-10-01

**Qué se decidió:**
- La anotación `VERSION_MISMATCH` **sobrevive a reinicios** del celular y solo se cierra cuando el
  reloj manda una versión que coincide (arregla R10-F3).
- Mientras esté abierta, el celular muestra una notificación **silenciosa** y fija, "SeizureGuard:
  update needed" (ID 4105, mismo canal sin sonido que las fallas, DEC-057), con texto distinto para
  "versiones distintas" y para "el reloj no informa versión" (más suave). No se cancela con "Stop
  bridge": la incompatibilidad sigue existiendo.
- El resumen de la mañana agrega una nota aparte; **no cuenta como corte** porque los datos siguieron
  llegando a OSD. Las noches sin diferencia de versión dan el mismo texto que antes.
- No se movió la anotación fuera del hilo principal (R10-F2): requería un ejecutor serializado para
  no invertir el orden MISMATCH/MATCH; hoy es una escritura breve solo en las transiciones.

**Revisión:** `safety-reviewer` R11: 8c-1 PASS, 8c-2/8c-3 PARTIAL PASS con la condición de la guía,
cumplida (sección 14 del registro). Residual R11-F1 (aviso viejo hasta reiniciar el monitoreo del
reloj), mitigado en la guía.

**Actualización 2026-10-01 (estado de `main`):** los Batches 1 a 8 están mergeados (reloj #29 a #34;
versión #35 a #41; CI #36) y la documentación se llevó a `main` alineada con ese código (Batch 9a).
Las entradas anteriores que dicen "sin mergear", "Batch 7 bloqueado" o "pendiente de código" describen
el estado de su fecha. Sigue pendiente la verificación en hardware real (DV-1..DV-7).

---

## DEC-067: HTTP en claro permitido solo hacia el propio teléfono (127.0.0.1)

**Fase:** primera prueba en hardware real | **Fecha:** 2026-10-06

**Qué pasó:** en la primera prueba con el reloj y el teléfono reales, el Companion no podía mandarle
nada a OSD. Android bloquea por defecto las conexiones HTTP sin cifrar (desde targetSdk 28) y OSD solo
habla HTTP plano en `127.0.0.1:8080`. El error quedaba escondido como "OSD no responde". Los tests
corren en la computadora, donde esa política no existe, por eso no lo detectaron.

**Qué se decidió:** se permite HTTP en claro **únicamente** hacia `127.0.0.1` y `localhost` mediante
`network_security_config`. Cualquier otro destino mantiene el bloqueo por defecto (el servidor de OSD no
tiene autenticación). Además, el Companion registra el motivo real de cada falla de conexión.

**Consecuencia:** antes de este arreglo el puente no funcionaba en hardware real. Verificado en el
equipo real después del arreglo (los datos llegan a OSD). `safety-reviewer` R14: PASS (sección 15 del
registro de seguridad).

---

## DEC-068: El reloj fuerza una grilla de 25 Hz por timestamp (decimación)

**Fase:** primera prueba en hardware real | **Fecha:** 2026-10-07

**Qué pasó:** OSD marcaba "Data arriving too quickly" y a veces entraba en FAULT. Medición del
2026-10-07 (Galaxy Watch 8, 17 min): 208 chunks, 199 de 5.0 s y 9 de menos de 4 s (mínimo 2.5 s). Los
6 episodios rápidos coincidieron con `SecTiltDetectorImpl` de Samsung registrando el acelerómetro
LSM6DSV a 20000 µs (50 Hz). El sensor es compartido: Android entrega 50 Hz a todos los listeners
aunque el nuestro pida 40000 µs, que es solo una sugerencia. Los chunks se arman por cantidad de
muestras (125), así que a 50 Hz cada chunk abarca 2.5 s. El 2026-10-06 hubo dos períodos de ~32 s a
50 Hz, más que la persistencia de falla de OSD (30 s): FAULT durante minutos. `SdDataSource` de OSD
valida que el intervalo entre paquetes sea de 4 a 6 s y descarta sin analizar los paquetes con falla.
Además, durante los 50 Hz OSD analiza un espectro con la frecuencia duplicada, porque asume 25 Hz.

**Qué se decidió:** decimar en el reloj con una grilla por timestamp (`SampleRateDecimator`,
`SensorEvent.timestamp`): se conserva una muestra cuando `ts >= próximoInstante - período/4`, con la
grilla anclada y el período derivado de `SENSOR_SAMPLING_PERIOD_US`. Entrada a 50 Hz: una de cada dos;
a 25 Hz con jitter: todas; tras un hueco del sensor la grilla se re-sincroniza sin ráfaga.

**Alternativas descartadas:**
- Pacing en el Companion: esconde el FAULT pero OSD sigue analizando el espectro duplicado y se suma
  latencia.
- Desactivar `DataFrequencyCheck` en OSD: quita una protección real y no corrige los datos.

**Consecuencias:**
- No cambia ninguna constante firmada (25 Hz, `TRANSPORT_CHUNK_SIZE` 125, `SAMPLE_STALE_MS`).
- Sin filtro anti-aliasing: con entrada a 50 Hz el contenido sobre 12.5 Hz puede plegarse. La energía
  del movimiento de muñeca ahí es baja y la banda de OSD es 3-8 Hz.
- Al arrancar (o tras un hueco) la primera muestra se conserva y define la grilla; el primer chunk
  puede tardar hasta un período más en completarse.
- La liveness no cambia: `lastSampleAtMs` se actualiza con cada evento crudo del sensor.

---

## Decisiones pendientes (a tomar en fases futuras)

| ID | Decisión | Fase | Estado |
|----|---------|------|--------|
| DEC-017 | Ownership del `Interpreter` de inferencia | — | **Obsoleta** — la inferencia la hace OSD (ExecuTorch en el teléfono), no este repo |
| DEC-018 | Umbral de decisión: ¿0.5 o valor calibrado contra OSDB? | 2.4 | **Obsoleta para este repo** — el umbral vive en OSD, no en el reloj |
| DEC-019 | Frecuencia de inferencia: ¿cada ventana nueva o cada 5s? | 2.3 | **Obsoleta para este repo** — la inferencia es de OSD |
| DEC-020 | Protocolo de mensajes Wear Data Layer: formato del payload | 3.1 | **Resuelta por DEC-046** — JSON UTF-8 |
| DEC-021 | Samsung Privileged Health SDK — ¿vale la complejidad extra? | 1.4 | **Resuelta por DEC-049 — no.** El Sensor SDK (lado reloj) expone señales crudas (accel, ECG, PPG), no fases de sueño ni score de estrés. El Data SDK (lado teléfono) sí los tiene pero exige aprobación de socio de Samsung. Ninguno de los dos hace falta: la descarga personal de Samsung Health ya entrega esos datos en CSV sin gate alguno |
