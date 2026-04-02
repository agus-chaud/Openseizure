# SeizureGuard

App de detección de convulsiones nocturnas para **Samsung Galaxy Watch 8** (Wear OS 4).

Basado en [OpenSeizureDetector](https://openseizuredetector.org.uk) + modelo **CNN v0.24** (TFLite, 204.5 KB).

> Desarrollado por Agus — Marzo 2026

---

## Para el lector data scientist: qué es esto y por qué importa

Si venís del mundo de datos y nunca tocaste Android/Kotlin, este proyecto te va a resultar familiar en lo conceptual y nuevo en la implementación. La idea central es simple:

> **Tomar datos de un sensor físico → pasarlos por un modelo CNN → tomar una decisión en tiempo real.**

La diferencia con tu entorno habitual (Python, Jupyter, GPU) es que acá el modelo corre en una CPU de reloj inteligente con batería de 300 mAh, sin internet, a las 3 de la mañana. Cada decisión de arquitectura existe por esa restricción.

---

## El problema

Las convulsiones tónico-clónicas nocturnas son las más peligrosas: la persona está dormida, no puede gritar, y el cuidador tampoco está despierto. Los dispositivos comerciales de detección cuestan entre USD 500 y USD 2000. Este proyecto es la alternativa open-source.

---

## Cómo funciona: el pipeline completo

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              SAMSUNG GALAXY WATCH 8                             │
│                                                                                 │
│  Acelerómetro 3D (25Hz)                                                         │
│  X, Y, Z muestras/segundo                                                       │
│         │                                                                       │
│         ▼                                                                       │
│  Magnitud vectorial: √(x² + y² + z²)   ← convierte 3D en 1D                   │
│         │                                                                       │
│         ▼                                                                       │
│  Ring Buffer (125 muestras = 5 segundos)   ← ventana deslizante               │
│  [t-124, t-123, ..., t-1, t]                                                   │
│         │                                                                       │
│         ▼                                                                       │
│  Tensor input: shape (1, 125, 1)   ← listo para el modelo                     │
│         │                                                                       │
│         ▼                                                                       │
│  ┌─────────────────────────────┐                                               │
│  │   CNN v0.24  (cnn_v024.tflite)│  204.5 KB en memoria del reloj             │
│  │   TFLite Interpreter         │  ~15-30ms de inferencia                     │
│  └─────────────────────────────┘                                               │
│         │                                                                       │
│         ▼                                                                       │
│  Output: shape (1, 2)                                                           │
│  [prob_normal=0.03, prob_seizure=0.97]                                          │
│         │                                                                       │
│         ▼                                                                       │
│  Máquina de estados:                                                            │
│  OK → WARNING (prob > 0.5, 1 ventana) → ALARM (prob > 0.5, N ventanas)        │
│         │                                                                       │
│         ▼                                                                       │
│  Vibración + envío por Wear Data Layer                                          │
└─────────────────────────────────────────────────────────────────────────────────┘
                                   │
                        Wear Data Layer API
                     (Bluetooth, sin internet)
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              ANDROID PHONE                                      │
│                                                                                 │
│  AlarmActivity (pantalla completa) + sirena                                     │
│  SMS automático al cuidador                                                     │
│  Historial de eventos en Room DB                                                │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## El modelo CNN v0.24 — lo que necesitás saber como data scientist

### Qué aprende el modelo

La CNN aprende a reconocer **patrones de movimiento característicos de convulsiones tónico-clónicas** en señales de acelerómetro. Una convulsión TC tiene movimientos rítmicos, de alta amplitud, con frecuencias típicas entre 1-3 Hz.

### Arquitectura

```
Input: (1, 125, 1)
  = 1 muestra del batch
  × 125 timesteps (5 segundos a 25Hz)
  × 1 feature (magnitud vectorial)
         │
   ┌─────▼──────────────────┐
   │  Conv1D layers         │  Detectan patrones locales en el tiempo
   │  (filtros, activación) │  (análogo a n-grams en NLP)
   └─────────────────────────┘
         │
   ┌─────▼──────────────────┐
   │  Pooling layers        │  Reducen dimensionalidad
   └─────────────────────────┘
         │
   ┌─────▼──────────────────┐
   │  Dense layers          │  Clasificación final
   └─────────────────────────┘
         │
Output: (1, 2)
  = [prob_normal, prob_seizure]
  Softmax → suman 1.0
```

### Origen y datos de entrenamiento

El modelo fue entrenado por el proyecto [OpenSeizureDetector](https://github.com/OpenSeizureDetector) usando datos del Open Seizure Database (OSDB), que contiene grabaciones de acelerómetro de pacientes reales con epilepsia + controles negativos (movimientos normales de sueño).

### Performance documentada

| Métrica | Valor |
|---------|-------|
| Sensibilidad (recall TC) | ~97% |
| Tasa de falsas alarmas | ~7% |
| Tamaño del modelo | 204.5 KB |
| Latencia de inferencia | ~15-30ms (CPU Wear OS) |
| Ventana temporal | 5 segundos (125 muestras a 25Hz) |

### Por qué TFLite y no el modelo .h5 o .pt original

TFLite es el formato de inferencia optimizado de TensorFlow para dispositivos con recursos limitados. El proceso es:

```
Entrenamiento (PC/GPU)         Despliegue (reloj)
─────────────────────────      ──────────────────────────
Keras model (.h5)              cnn_v024.tflite
  └── tf.lite.TFLiteConverter  └── tflite.Interpreter
        (cuantización,               (C++, sin Python,
         optimización)                sin TF completo)
```

El `.tflite` es un FlatBuffer (formato binario eficiente). Este proyecto usa el modelo ya convertido. Si en el futuro querés reentrenar, necesitás el `.h5` o `.keras` original más los datos OSDB.

---

## Arquitectura del proyecto Android

### Concepto de multi-módulo

El proyecto tiene dos apps separadas que se comunican:

```
OpenSeizure/                         ← carpeta raíz del proyecto
├── wear/                            ← app del reloj (Wear OS)
│   └── com.seizureguard.wear
└── phone/                           ← app del teléfono (Android)
    └── com.seizureguard.phone
```

Esto es como tener dos microservicios en el mismo repositorio. Cada uno tiene su propio `build.gradle.kts` (su `requirements.txt`), sus propias dependencias, y se compila independientemente.

**Analogía para data scientists:** es como tener un `training/` y un `serving/` en el mismo repo. El módulo `wear` hace la inferencia; el módulo `phone` hace la presentación de resultados.

### Estructura de archivos

```
OpenSeizure/
├── settings.gradle.kts          ← "este proyecto tiene 2 módulos: :wear y :phone"
├── build.gradle.kts             ← configuración global (solo declara qué versiones de
│                                   plugins existen, no los aplica)
├── gradle/
│   └── libs.versions.toml       ← Version Catalog: todas las versiones centralizadas
│                                   (el pip freeze / pyproject.toml de Android)
├── DECISIONS.md                 ← Por qué tomamos cada decisión técnica
├── TODOS.md                     ← Trabajo identificado pero fuera del scope actual
│
├── wear/                        ← Módulo del reloj
│   ├── build.gradle.kts         ← dependencias del reloj (TFLite, Compose Wear, etc.)
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml   ← permisos + declaración de la app
│   │   │   ├── assets/
│   │   │   │   └── cnn_v024.tflite   ← el modelo CNN (204.5 KB)
│   │   │   └── java/com/seizureguard/wear/
│   │   │       ├── MainActivity.kt   ← pantalla principal: toggle inicio/stop
│   │   │       ├── logging/
│   │   │       │   └── CsvLogger.kt          ← logging de muestras a CSV (Fase 1.6)
│   │   │       ├── ml/
│   │   │       │   ├── TFLiteModelLoader.kt  ← carga el modelo en memoria
│   │   │       │   └── CircularBuffer.kt     ← ring buffer de 125 muestras (Fase 1.5)
│   │   │       └── service/
│   │   │           └── SeizureMonitorService.kt  ← ForegroundService nocturno
│   │   └── test/
│   │       ├── assets/
│   │       │   └── model_fixture.tflite  ← modelo mínimo para tests (no producción)
│   │       └── java/com/seizureguard/wear/
│   │           ├── WearModuleTest.kt
│   │           ├── logging/
│   │           │   └── CsvLoggerTest.kt      ← tests del logger CSV (Robolectric)
│   │           ├── ml/
│   │           │   ├── TFLiteModelLoaderTest.kt  ← tests del loader (Robolectric)
│   │           │   └── CircularBufferTest.kt     ← tests del ring buffer (Robolectric)
│   │           └── service/
│   │               └── SeizureMonitorServiceTest.kt  ← tests del service (Robolectric)
│
└── phone/                       ← Módulo del teléfono
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/seizureguard/phone/
            └── MainActivity.kt
```

---

## El stack tecnológico explicado

| Tecnología | Para qué sirve | Analogía Python |
|-----------|---------------|-----------------|
| **Kotlin 2.0.21** | Lenguaje principal — compilado, tipado fuerte, null-safe por diseño | Python con type hints estrictos y enforcement en compilación |
| **Gradle + Version Catalog** | Gestión de dependencias y build | pip + pyproject.toml |
| **Wear OS SDK (API 30-34)** | SDK del sistema operativo del reloj | La API de un dispositivo IoT |
| **Jetpack Compose** | UI declarativa (solo pantallas básicas por ahora) | React pero para Android |
| **TFLite 2.14.0** | Runtime de inferencia del modelo CNN | onnxruntime o tflite-runtime en Python |
| **Kotlin Coroutines** | Concurrencia sin bloquear el hilo principal | asyncio en Python |
| **KSP** | Generador de código en tiempo de compilación | Equivalente a Cython/codegen |
| **Room** | Base de datos SQLite con ORM | SQLAlchemy, para el módulo phone |
| **Wear Data Layer** | Canal de comunicación Bluetooth watch→phone | gRPC o WebSocket entre procesos |
| **Robolectric** | Tests de Android que corren en JVM (sin dispositivo) | pytest-mock para código Android |

---

## Qué hace `TFLiteModelLoader` (Fase 0.3)

Este es el primer componente de ML del proyecto. Su única responsabilidad: **cargar el archivo `.tflite` del APK a memoria** de forma segura.

```
APK (almacenamiento del reloj)
  └── assets/cnn_v024.tflite   ← empaquetado en el APK sin comprimir
           │
           │  AssetManager.openFd()  ← abre un file descriptor al asset
           ▼
  AssetFileDescriptor
           │
           │  FileInputStream.channel.map()  ← memory-mapping (mmap)
           ▼
  MappedByteBuffer              ← el modelo en memoria, listo para TFLite
           │
           │  (en Fase 2.1)
           ▼
  tflite.Interpreter(buffer)   ← crea el intérprete que hace la inferencia
```

**Por qué memory-mapping y no leer los bytes directamente:**

Memory-mapping (`mmap`) le dice al sistema operativo que trate el archivo como si fuera memoria RAM. La CPU accede solo a las páginas que necesita, el OS gestiona el caché. Para un modelo de 204KB es casi instantáneo y no duplica el uso de memoria.

**Por qué `suspend fun` (coroutine):**

Leer del disco nunca puede correr en el "main thread" (el hilo que maneja la UI y los sensores). Si bloqueás el main thread por más de ~5 segundos, el sistema operativo mata la app con ANR (Application Not Responding). Las coroutines de Kotlin son la solución idiomática — `suspend fun` con `Dispatchers.IO` garantiza que la carga corre en un hilo de I/O.

```kotlin
// En Python, esto sería algo así:
async def load_model(path: str) -> bytes:
    async with aiofiles.open(path, 'rb') as f:
        return await f.read()

// En Kotlin:
suspend fun load(context: Context, modelFileName: String): MappedByteBuffer =
    withContext(Dispatchers.IO) { ... }
```

---

## Tests implementados

### Filosofía de tests en este proyecto

Los tests verifican **comportamiento**, no implementación. El objetivo no es 100% de line coverage sino que cada camino crítico esté cubierto.

### Tests del módulo wear (actuales)

```
wear/src/test/
├── WearModuleTest.kt                      ← Smoke tests del módulo
│   ├── smokeTest                           tests que el módulo compila
│   ├── modelConstants_inputShapeIsCorrect  125 samples × 25Hz = 5 seg
│   └── sensorSampling_frequencyIs25Hz
│
├── ml/TFLiteModelLoaderTest.kt            ← Tests del loader (Robolectric)
│   ├── modelLoads_withValidFixture_returnsBuffer    happy path
│   ├── modelLoad_returnsNonEmptyBuffer              buffer.limit() > 0
│   └── modelLoad_withMissingFile_throwsModelLoadException  error path
│
├── ml/CircularBufferTest.kt               ← Tests del ring buffer (Fase 1.5)
│   ├── buffer_startsEmpty                           size == 0, isFull == false
│   ├── buffer_afterAddingLessThanCapacity_isNotFull 124 muestras → isFull false
│   ├── buffer_afterAddingExactCapacity_isFull       125 muestras → isFull true
│   ├── buffer_snapshot_returnsElementsInChronologicalOrder  orden cronológico exacto
│   ├── buffer_afterOverflow_containsMostRecentSamples       ventana deslizante correcta
│   ├── buffer_snapshot_whenNotFull_returnsEmptyArray        sin datos parciales al CNN
│   ├── buffer_reset_clearsAllSamples                        reset() limpia todo
│   ├── buffer_snapshot_returnsIndependentCopy               copia independiente del array
│   ├── buffer_magnitude_calculatedCorrectly                 √(3²+4²+0²) = 5.0
│   └── buffer_concurrentAccess_doesNotCorrupt               2 coroutines × 1000 ops, sin corrupción
│
├── logging/CsvLoggerTest.kt              ← Tests del logger CSV (Fase 1.6)
│   ├── csvLogger_open_createsFile                   open() crea el archivo en disco
│   ├── csvLogger_open_writesHeader                  primera línea es el header correcto
│   ├── csvLogger_write_appendsRow                   write() agrega fila con datos correctos
│   ├── csvLogger_write_beforeOpen_doesNotCrash      write() sin open() es silencioso
│   ├── csvLogger_close_flushesData                  close() escribe los últimos datos a disco
│   ├── csvLogger_close_isIdempotent                 close() dos veces no lanza excepción
│   ├── csvLogger_isLogging_afterOpen_isTrue         isLogging=true después de open()
│   ├── csvLogger_isLogging_afterClose_isFalse       isLogging=false después de close()
│   ├── csvLogger_filename_containsTimestamp         nombre sigue patrón raw_accel_YYYYMMDD_HHmmss.csv
│   └── csvLogger_open_secondCall_returnsNull        segundo open() retorna null
│
└── service/SeizureMonitorServiceTest.kt   ← Tests del ForegroundService (Robolectric)
    ├── service_onCreate_doesNotCrash               el Service arranca sin crash
    ├── service_onCreate_registersNotification      notificación persistente registrada
    ├── service_onBind_returnsNull                  es un started service, no bound
    ├── service_actionStart_doesNotCrash            smoke test de ACTION_START
    ├── serviceCompanion_startIntent_hasCorrectAction  factory method correcto
    ├── serviceCompanion_stopIntent_hasCorrectAction   factory method correcto
    ├── wakeLock_afterActionStart_isHeld            WakeLock adquirido al iniciar (Fase 1.2)
    ├── wakeLock_afterOnDestroy_isReleased          WakeLock liberado al destruir (Fase 1.2)
    ├── wakeLock_isPartialWakeLock                  tipo PARTIAL, no FULL (Fase 1.2)
    ├── wakeLock_hasCorrectTag                      tag "SeizureGuard::MonitoringWakeLock" (Fase 1.2)
    ├── sensorManager_afterActionStart_isRegistered     listener registrado al iniciar (Fase 1.3)
    ├── sensorManager_afterActionStop_isUnregistered    listener desregistrado al parar (Fase 1.3)
    ├── sensorManager_afterOnDestroy_isUnregistered     listener desregistrado en onDestroy (Fase 1.3)
    ├── sensorManager_samplingPeriod_is25Hz             contrato 40,000µs = 40ms = 25Hz (Fase 1.3)
    └── sensorManager_usesLinearAcceleration_notRawAccelerometer  TYPE_LINEAR_ACCELERATION (Fase 1.3)
```

**¿Qué es Robolectric?**

Android necesita un dispositivo (físico o emulador) para ejecutar tests que usan APIs del sistema como `AssetManager`. Robolectric simula el sistema Android en la JVM, sin hardware real. El resultado: tests que corren en segundos en cualquier PC.

```
Sin Robolectric:                Con Robolectric:
────────────────                ───────────────────────────
Necesita watch o emulador   →   Corre en la PC, sin dispositivo
~2-5 minutos por suite      →   ~5-15 segundos
Requiere ADB conectado      →   ./gradlew :wear:test
```

**El fixture `model_fixture.tflite`** es un modelo TFLite mínimo válido (144 bytes, FlatBuffer v3) generado con Python + flatbuffers. Permite testear el mecanismo de carga sin el modelo real. No es el CNN de producción — ese es `cnn_v024.tflite`.

---

## Permisos de Android (por qué cada uno)

Los permisos en Android son declaraciones explícitas de qué recursos va a usar la app. Son como `import` pero el usuario puede rechazarlos.

| Permiso | Cuándo se usa | Por qué es necesario |
|---------|--------------|---------------------|
| `BODY_SENSORS` | Siempre que la app está en foreground | Para leer el acelerómetro |
| `BODY_SENSORS_BACKGROUND` | Durante el monitoreo nocturno | Wear OS 4 requiere permiso explícito para sensores cuando la app está en background. El usuario lo otorga manualmente. |
| `FOREGROUND_SERVICE` | Al iniciar el servicio de monitoreo | Permite a un Service sobrevivir cuando el usuario no interactúa con la app |
| `FOREGROUND_SERVICE_HEALTH` | Idem | Wear OS 4 requiere especificar el tipo de foreground service. Para sensores: tipo "health" |
| `WAKE_LOCK` | Durante el monitoreo nocturno | Sin esto, el CPU del reloj duerme y los sensores se apagan a los pocos minutos |
| `VIBRATE` | Cuando se detecta convulsión | Para la alarma háptica |

---

## Cómo analizar los datos CSV (Fase 1.6)

Los archivos CSV se generan automáticamente en builds de debug. Para descargarlos y analizarlos:

```bash
# Descargar todos los logs del reloj a la PC
adb pull /sdcard/Android/data/com.seizureguard.wear/files/logs/ ./logs/
```

```python
import pandas as pd

df = pd.read_csv("logs/raw_accel_20260402_230000.csv")

# Frecuencia real de muestreo
df['delta_ms'] = df['timestamp_ms'].diff()
print("Frecuencia real:")
print(df['delta_ms'].describe())
# mean ≈ 40ms → correcto (25Hz)
# std < 5ms   → sin jitter problemático

# Magnitud en reposo
print(f"\nMagnitud media: {df['magnitude'].mean():.3f} m/s²")
# ≈ 0.0 → TYPE_LINEAR_ACCELERATION funcionando
```

**Qué verificar antes de pasar a Fase 2.1 (inferencia TFLite):**

| Métrica | Valor esperado | Cómo verificarlo |
|---------|---------------|-----------------|
| Frecuencia media | ~40ms entre muestras | `df['delta_ms'].mean()` |
| Jitter | std < 5ms | `df['delta_ms'].std()` |
| Magnitud en reposo | < 0.5 m/s² | `df['magnitude'].mean()` con reloj quieto |
| Gaps largos | < 5 instancias > 200ms | `(df['delta_ms'] > 200).sum()` |

---

## Cómo correr los tests

Hay dos tipos de tests con propósitos distintos:

```
Tests unitarios (Robolectric)            Tests instrumented
────────────────────────────────         ────────────────────────────────────────
src/test/                                src/androidTest/
Sin dispositivo, corre en la PC          Requiere Samsung Watch 8 conectado
./gradlew :wear:test                     ./gradlew :wear:connectedAndroidTest
Testa el MECANISMO de carga              Testa la carga desde el APK REAL
Usa fixture de 144 bytes                 Usa cnn_v024.tflite empaquetado
```

### Tests unitarios (sin watch)

```bash
./gradlew :wear:test

# Output esperado:
# WearModuleTest > smokeTest PASSED
# WearModuleTest > modelConstants_inputShapeIsCorrect PASSED
# WearModuleTest > sensorSampling_frequencyIs25Hz PASSED
# TFLiteModelLoaderTest > modelLoads_withValidFixture_returnsBuffer PASSED
# TFLiteModelLoaderTest > modelLoad_returnsNonEmptyBuffer PASSED
# TFLiteModelLoaderTest > modelLoad_withMissingFile_throwsModelLoadException PASSED
# CircularBufferTest > buffer_startsEmpty PASSED
# CircularBufferTest > buffer_afterAddingLessThanCapacity_isNotFull PASSED
# CircularBufferTest > buffer_afterAddingExactCapacity_isFull PASSED
# CircularBufferTest > buffer_snapshot_returnsElementsInChronologicalOrder PASSED
# CircularBufferTest > buffer_afterOverflow_containsMostRecentSamples PASSED
# CircularBufferTest > buffer_snapshot_whenNotFull_returnsEmptyArray PASSED
# CircularBufferTest > buffer_reset_clearsAllSamples PASSED
# CircularBufferTest > buffer_snapshot_returnsIndependentCopy PASSED
# CircularBufferTest > buffer_magnitude_calculatedCorrectly PASSED
# CircularBufferTest > buffer_concurrentAccess_doesNotCorrupt PASSED
# CsvLoggerTest > csvLogger_open_createsFile PASSED
# CsvLoggerTest > csvLogger_open_writesHeader PASSED
# CsvLoggerTest > csvLogger_write_appendsRow PASSED
# CsvLoggerTest > csvLogger_write_beforeOpen_doesNotCrash PASSED
# CsvLoggerTest > csvLogger_close_flushesData PASSED
# CsvLoggerTest > csvLogger_close_isIdempotent PASSED
# CsvLoggerTest > csvLogger_isLogging_afterOpen_isTrue PASSED
# CsvLoggerTest > csvLogger_isLogging_afterClose_isFalse PASSED
# CsvLoggerTest > csvLogger_filename_containsTimestamp PASSED
# CsvLoggerTest > csvLogger_open_secondCall_returnsNull PASSED
# SeizureMonitorServiceTest > service_onCreate_doesNotCrash PASSED
# SeizureMonitorServiceTest > service_onCreate_registersNotification PASSED
# SeizureMonitorServiceTest > service_onBind_returnsNull PASSED
# SeizureMonitorServiceTest > service_actionStart_doesNotCrash PASSED
# SeizureMonitorServiceTest > serviceCompanion_startIntent_hasCorrectAction PASSED
# SeizureMonitorServiceTest > serviceCompanion_stopIntent_hasCorrectAction PASSED
# SeizureMonitorServiceTest > wakeLock_afterActionStart_isHeld PASSED
# SeizureMonitorServiceTest > wakeLock_afterOnDestroy_isReleased PASSED
# SeizureMonitorServiceTest > wakeLock_isPartialWakeLock PASSED
# SeizureMonitorServiceTest > wakeLock_hasCorrectTag PASSED
# SeizureMonitorServiceTest > sensorManager_afterActionStart_isRegistered PASSED
# SeizureMonitorServiceTest > sensorManager_afterActionStop_isUnregistered PASSED
# SeizureMonitorServiceTest > sensorManager_afterOnDestroy_isUnregistered PASSED
# SeizureMonitorServiceTest > sensorManager_samplingPeriod_is25Hz PASSED
# SeizureMonitorServiceTest > sensorManager_usesLinearAcceleration_notRawAccelerometer PASSED
# BUILD SUCCESSFUL
```

### Tests instrumented (con watch conectado — Fase 0.4)

```bash
# 1. Conectar el watch
./scripts/connect_watch.sh 192.168.x.x   # IP del reloj

# 2. Correr los tests instrumented
./gradlew :wear:connectedAndroidTest

# Output esperado en el watch:
# TFLiteModelLoaderInstrumentedTest > modelLoads_fromInstalledApk_returnsBuffer PASSED
# TFLiteModelLoaderInstrumentedTest > modelLoads_fromInstalledApk_bufferSizeMatchesRealModel PASSED
# TFLiteModelLoaderInstrumentedTest > modelLoads_fromInstalledApk_bufferMatchesExpectedSize PASSED
# TFLiteModelLoaderInstrumentedTest > modelLoad_withNonExistentFile_throwsModelLoadException PASSED
```

---

## Setup del Samsung Watch 8 para desarrollo (Fase 0.4)

### Por qué ADB Wireless y no USB

El Samsung Galaxy Watch 8 no tiene puerto USB expuesto. La única forma de conectarlo para desarrollo es via Bluetooth o WiFi. ADB Wireless usa la red WiFi local.

```
                    Red WiFi local
   PC ─────────────────────────────── Samsung Watch 8
   adb connect 192.168.x.x:5555       Depuración inalámbrica ON
```

### Habilitar Developer Mode en el watch

```
En el reloj:
Ajustes → Acerca del reloj → Información de software
→ Tocar "Número de compilación" 7 veces seguidas
→ Aparece: "Modo desarrollador activado"

Luego:
Ajustes → Opciones de desarrollador
→ Depuración ADB → ON
→ Depuración inalámbrica → ON
→ Aparece la IP y el puerto (ej: 192.168.1.42:5555)
```

### Conectar y deployar

```bash
# Conectar (script helper)
./scripts/connect_watch.sh 192.168.1.42

# Build + install en el watch
./scripts/deploy_wear.sh

# Ver logs en tiempo real
adb logcat -s SeizureGuard:D TFLiteModelLoader:D

# Correr tests instrumented en el watch
./gradlew :wear:connectedAndroidTest
```

### Qué hace cada script

| Script | Qué hace |
|--------|---------|
| `scripts/connect_watch.sh [IP]` | Conecta al watch via ADB, verifica estado, muestra modelo/Android version |
| `scripts/deploy_wear.sh` | Build debug + instala el APK + verifica que el .tflite NO está comprimido |
| `scripts/deploy_wear.sh --tests-only` | Solo corre tests unitarios (sin watch) |

---

## Cómo abrir el proyecto en Android Studio

1. `File → Open` → seleccionar la carpeta `OpenSeizure/`
2. Android Studio detecta `settings.gradle.kts` y configura el proyecto automáticamente
3. Esperar el Gradle Sync (primera vez: descarga ~200MB de dependencias)
4. Para correr tests unitarios: click derecho en `TFLiteModelLoaderTest.kt` → Run
5. Para tests instrumented: conectar el watch primero, luego click derecho → Run

### Requisitos

- Android Studio Hedgehog (2023.1.1) o superior
- JDK 17 (incluido en Android Studio)
- Android SDK API 34
- Samsung Galaxy Watch 8 (para Fase 0.4+)

---

## Plan de desarrollo por fases

### Fase 0: Setup del proyecto (COMPLETADA ✅)
- [x] **0.1** Estructura multi-módulo `:wear` + `:phone` — Smoke tests verdes
- [x] **0.2** Stack completo de dependencias (TFLite, Coroutines, Room, KSP, Lifecycle, Compose)
- [x] **0.3** `TFLiteModelLoader.kt` + modelo `cnn_v024.tflite` descargado e integrado (209,456 bytes, TFL3) + 3 tests Robolectric
- [x] **0.4** ADB Wireless — scripts de conexión/deploy + 4 tests instrumented (TODO-001 resuelto)

### Fase 1: Captura de sensores (wear)
- [x] **1.1** ForegroundService con notificación persistente ("SeizureGuard activo") + UI toggle en MainActivity
- [x] **1.2** WakeLock + lifecycle management (evitar que el reloj duerma)
- [x] **1.3** SensorManager: acelerómetro 3D a 25Hz (TYPE_LINEAR_ACCELERATION, 40ms period)
- [ ] **1.4** Samsung Privileged Health SDK (opcional — mejor acceso a sensores)
- [x] **1.5** Ring buffer circular de 125 muestras + cálculo de magnitud vectorial
- [x] **1.6** Logging a CSV (para verificar y analizar los datos crudos)

### Fase 2: Inferencia TFLite (wear)
- [ ] **2.1** Crear `Interpreter` + verificar shapes en logcat
- [ ] **2.2** Pipeline de preprocesamiento: `FloatArray(125)` → `ByteBuffer` → tensor
- [ ] **2.3** Inferencia cada ventana + log de probabilidades
- [ ] **2.4** Máquina de estados: OK → WARNING → ALARM
- [ ] **2.5** Vibración háptica en estado ALARM
- [ ] **2.6** Benchmark de batería (objetivo: ≥8h con monitoreo continuo)

### Fase 3: Companion App (phone)
- [ ] **3.1** Wear Data Layer: canal watch → phone
- [ ] **3.2** AlarmActivity full-screen con sirena
- [ ] **3.3** SMS automático al cuidador
- [ ] **3.4** Pantalla de configuración (umbral, contacto, nombre del paciente)
- [ ] **3.5** Room DB: historial de eventos con timestamp

### Fase 4: Integración y testing real
- [ ] **4.1** Test E2E manual (simular convulsión con movimiento del reloj)
- [ ] **4.2** Test nocturno real (8 horas de sueño con monitoreo activo)
- [ ] **4.3** Análisis de falsas alarmas + tuning del umbral de decisión
- [ ] **4.4** Edge cases: watch sacado, batería baja, Bluetooth perdido
- [ ] **4.5** UI polish: Tile de Wear OS + complicación

### Fase 5: Mejora del modelo (futuro)
- [ ] Solicitar acceso a datos OSDB y analizar el dataset
- [ ] Reentrenar CNN con datos adicionales
- [ ] A/B testing CNN v0.24 vs modelo nuevo
- [ ] Agregar HR + SpO2 como features adicionales
- [ ] Google Play Store

---

## Obtener el modelo `cnn_v024.tflite`

El modelo real ya está incluido en el repositorio (`wear/src/main/assets/cnn_v024.tflite`, 204.5 KB). Fue descargado directamente del repositorio open-source de OpenSeizureDetector:

**Fuente:** [OpenSeizureDetector/Android_Pebble_SD](https://github.com/OpenSeizureDetector/Android_Pebble_SD)
`app/src/main/assets/cnn_v0.24.tflite`

Licencia: GPL v3 (el proyecto completo hereda esta licencia).

---

## Datos de entrenamiento (OSDB)

Para la Fase 5 (reentrenamiento del modelo) se necesita acceso al Open Seizure Database:

- Email: osdb@openseizuredetector.org.uk
- Explicar: qué se va a hacer con los datos + confirmar cumplimiento de la licencia

---

## Licencia

Open source. Basado en OpenSeizureDetector (GPL v3).
