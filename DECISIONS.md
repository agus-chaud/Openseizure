# SeizureGuard — Registro de Decisiones de Arquitectura

Este archivo documenta **por qué** tomamos cada decisión técnica relevante.
La pregunta "por qué" es más valiosa que el "qué" — el código ya explica el qué.

> Para un data scientist trainee: esto es el log de experimentos, pero para decisiones de ingeniería.
> Cada entrada es una elección con alternativas consideradas y razones claras.

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
  la app OSD).

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

**Elección: `START_STICKY`.** Si el OS mata el Service en condiciones de memoria extrema (muy raro con WakeLock activo, pero posible), Android lo reinicia. El Intent llega `null` — manejado en `onStartCommand()` con el `when` que ignora los casos `null`. El monitoreo nocturno no puede quedar detenido sin que el usuario lo sepa.

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

**Fase:** 2.1+ (documentación de contrato) | **Fecha:** Mayo 2026

**Decisión:** Esta entrada es la **única fuente de verdad** para el protocolo de mensajes entre reloj y teléfono (paths, payload binario, endianness, unidades y relación con el tensor del modelo). DEC-033 y DEC-034 describen el mecanismo (`MessageClient`) y la serialización; aquí se congela el contrato completo.

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

## Decisiones pendientes (a tomar en fases futuras)

| ID | Decisión | Fase | Estado |
|----|---------|------|--------|
| DEC-017 | Ownership del `tflite.Interpreter` — ¿quién lo crea, quién lo cierra? | 2.1 | Pendiente (ver TODO-002) |
| DEC-018 | Umbral de decisión: ¿0.5 o valor calibrado contra OSDB? | 2.4 | Pendiente |
| DEC-019 | Frecuencia de inferencia: ¿cada ventana nueva (cada 40ms) o cada 5s? | 2.3 | Pendiente |
| DEC-020 | Protocolo de mensajes Wear Data Layer: JSON vs Protobuf vs bytes raw | 3.1 | Pendiente — DEC-033 resuelve el mecanismo (MessageClient), no el formato |
| DEC-021 | Samsung Privileged Health SDK — ¿vale la complejidad extra? | 1.4 | Pendiente |
