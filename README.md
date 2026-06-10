# SeizureGuard

App de detección de convulsiones nocturnas para **Samsung Galaxy Watch 8** (Wear OS 4).

Este repo aporta el **lado reloj** (un *data source* Android Wear) que alimenta la app oficial
**[OpenSeizureDetector](https://openseizuredetector.org.uk) V5.0**, la cual corre el modelo
**DeepEpiCnn Run24** (PyTorch ExecuTorch) en el teléfono. **El reloj no infiere.**


---

## Para el lector data scientist: qué es esto y por qué importa

Si venís del mundo de datos y nunca tocaste Android/Kotlin, este proyecto te va a resultar familiar en lo conceptual y nuevo en la implementación. La idea central es simple:

> **Tomar datos de un sensor físico → pasarlos por un modelo  → tomar una decisión en tiempo real.**

La diferencia con tu entorno habitual (Python, Jupyter, GPU) es que acá el modelo corre en una CPU de reloj inteligente con batería de 300 mAh, sin internet, a las 3 de la mañana. Cada decisión de arquitectura existe por esa restricción.

---

## El problema

Las convulsiones tónico-clónicas nocturnas son las más peligrosas: la persona está dormida, no puede gritar, y el cuidador tampoco está despierto. Los dispositivos comerciales de detección cuestan entre USD 500 y USD 2000. Este proyecto es la alternativa open-source.

---

## Cómo funciona: el pipeline completo

Este repo es el **lado reloj**. La detección la hace la app **OpenSeizureDetector V5.0**. El flujo:

```
RELOJ (este repo)                          TELÉFONO (app OSD V5.0)
─────────────────                          ───────────────────────
Acelerómetro 25Hz (TYPE_ACCELEROMETER)
  → magnitud √(x²+y²+z²) en milli-g
  → ring buffer
  → chunks de ~125 muestras (~5s)
        │  Wear Data Layer
        │  /osd/accel_data  {"samples":[...]}
        ▼
                                           SdDataSourceAw recibe y acumula 750
                                           → ExecuTorch + deepEpiCnn_Run24.pte
                                           → prob de convulsión → umbral
                                           → alarma + sirena + SMS al cuidador
        ┌──────────────────────────────────────┘
        │  /osd/alarm_state  {"alarm_state":N}
        ▼
Vibración háptica + UI (OK / WARNING / ALARM)
```

**El reloj NO infiere.** Solo captura, transmite y reacciona. El modelo, el umbral y las alertas
son responsabilidad de la app OSD. El reloj es un *data source Android Wear* compatible con
`SdDataSourceAw`. Ver engram `architecture/seizureguard-executorch-api`.

---

## El modelo DeepEpiCnn Run24 — lo que necesitás saber como data scientist

> **Importante:** este modelo **NO corre en este repo** — lo corre la app OSD V5.0 en el teléfono,
> con **PyTorch ExecuTorch** (`org.pytorch:executorch-android:1.0.1`), cargando
> `deepEpiCnn_2026_01_24_Run24.pte`. Lo explicamos igual para que entiendas qué hace con los datos
> que tu reloj le manda.

### Qué aprende el modelo

La CNN aprende a reconocer **patrones de movimiento característicos de convulsiones tónico-clónicas** en señales de acelerómetro. Una convulsión TC tiene movimientos rítmicos, de alta amplitud, con frecuencias típicas entre 1-3 Hz.

### Arquitectura

```
Input ExecuTorch: (1, 1, 750)   ← tensor real del modelo (30 s a 25 Hz)
Transporte Wear /osd/accel_data: el reloj manda JSON {"samples":[...]} en milli-g
  (chunks de ~125 muestras / ~5 s que OSD acumula hasta 750 — DEC-039)

Input: (1, 1, 750)
  = 1 muestra del batch
  × 1 feature (magnitud vectorial en milli-g)
  × 750 timesteps (30 segundos a 25Hz)
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
| Modelo / runtime | DeepEpiCnn Run24 (`.pte`) · PyTorch ExecuTorch |
| Dónde corre | En el teléfono, dentro de la app OSD V5.0 (no en el reloj) |
| Tamaño del modelo | ~425 KB (`deepEpiCnn_2026_01_24_Run24.pte`) |
| Tensor de input | `(1, 1, 750)` |
| Ventana temporal (modelo) | 30 segundos (750 muestras a 25Hz) — distinto del tamaño N de cada mensaje `accel_data` (DEC-039) |
| Validación | Graham Jones reportó buena detección con Run24 en PineTime |


---

## Arquitectura del proyecto Android

### Módulo único: `:wear`

Este repo tiene **un solo módulo**, la app del reloj:

```
OpenSeizure/                         ← carpeta raíz del proyecto
└── wear/                            ← app del reloj (Wear OS)
    └── com.seizureguard.wear
```

El "teléfono" en este sistema es la **app OpenSeizureDetector V5.0** (un proyecto aparte que
instalás en el celular). Ella recibe los datos del reloj, corre el modelo y dispara las alarmas.
Por eso acá **no hay módulo `:phone` propio** — se retiró cuando confirmamos que OSD ya hace todo eso.

**Analogía para data scientists:** este repo es solo el `serving/` del sensor — captura y
transmite los datos. El `model/` y el `inference/` viven en la app OSD, no acá.

### Estructura de archivos

```
OpenSeizure/
├── settings.gradle.kts          ← "este proyecto tiene 1 módulo: :wear"
├── build.gradle.kts             ← configuración global (solo declara qué versiones de
│                                   plugins existen, no los aplica)
├── gradle.properties            ← config global (android.useAndroidX, etc.)
├── gradle/
│   └── libs.versions.toml       ← Version Catalog: todas las versiones centralizadas
│                                   (el pip freeze / pyproject.toml de Android)
├── DECISIONS.md                 ← Por qué tomamos cada decisión técnica
├── HARDWARE_RUNBOOK.md          ← Pruebas reales con el reloj + la app OSD (sin Android Studio)
├── CAREGIVER_GUIDE.md           ← Guía para el cuidador (no técnico)
├── CLINICAL_SIGNOFF.md          ← Constantes clínicas (se firman en la config de OSD)
│
└── wear/                        ← Módulo del reloj (ÚNICO módulo del repo)
    ├── build.gradle.kts         ← dependencias del reloj (Compose Wear, Wear Data Layer, etc.
    │                              SIN TFLite/ExecuTorch — la inferencia es de OSD)
    ├── src/
    │   ├── main/java/com/seizureguard/wear/
    │   │   ├── MainActivity.kt              ← pantalla principal: toggle inicio/stop
    │   │   ├── logging/CsvLogger.kt         ← logging de muestras a CSV (Fase 1.6)
    │   │   ├── ml/CircularBuffer.kt         ← ring buffer 750 muestras (Fase 1.5)
    │   │   ├── data/WearDataLayerManager.kt ← protocolo OSD (JSON samples / alarm_state)
    │   │   ├── alarm/AlarmStateManager.kt   ← vibración según el alarmState de OSD
    │   │   └── service/SeizureMonitorService.kt  ← ForegroundService nocturno (captura+transporte)
    │   └── test/java/com/seizureguard/wear/
    │       ├── WearModuleTest.kt
    │       ├── logging/CsvLoggerTest.kt          ← tests del logger CSV (Robolectric)
    │       ├── ml/CircularBufferTest.kt          ← tests del ring buffer (Robolectric)
    │       └── data/WearDataLayerManagerTest.kt  ← tests del protocolo OSD (Robolectric)
```

> El modelo, su loader y el módulo `:phone` ya no están en el repo: la inferencia la hace la app OSD.

---

## El stack tecnológico explicado

| Tecnología | Para qué sirve | Analogía Python |
|-----------|---------------|-----------------|
| **Kotlin 2.0.21** | Lenguaje principal — compilado, tipado fuerte, null-safe por diseño | Python con type hints estrictos y enforcement en compilación |
| **Gradle + Version Catalog** | Gestión de dependencias y build | pip + pyproject.toml |
| **Wear OS SDK (API 30-34)** | SDK del sistema operativo del reloj | La API de un dispositivo IoT |
| **Jetpack Compose** | UI declarativa (solo pantallas básicas por ahora) | React pero para Android |
| **PyTorch ExecuTorch** | Runtime de inferencia del modelo — corre **en la app OSD**, no en este repo | onnxruntime / torch en Python |
| **Kotlin Coroutines** | Concurrencia sin bloquear el hilo principal | asyncio en Python |
| **KSP** | Generador de código en tiempo de compilación | Equivalente a Cython/codegen |
| **Wear Data Layer** | Canal de comunicación Bluetooth reloj→app OSD | gRPC o WebSocket entre procesos |
| **Robolectric** | Tests de Android que corren en JVM (sin dispositivo) | pytest-mock para código Android |

---

## Tests implementados

### Filosofía de tests en este proyecto

Los tests verifican **comportamiento**, no implementación. El objetivo no es 100% de line coverage sino que cada camino crítico esté cubierto.

### Tests del módulo wear (actuales)

```
wear/src/test/
├── WearModuleTest.kt                      ← Smoke tests del módulo
│   ├── smokeTest                           tests que el módulo compila
│   ├── modelConstants_inputShapeIsCorrect  750 samples × 25Hz = 30 seg
│   └── sensorSampling_frequencyIs25Hz
│
├── ml/CircularBufferTest.kt               ← Tests del ring buffer (Fase 1.5)
│   ├── buffer_startsEmpty                           size == 0, isFull == false
│   ├── buffer_afterAddingLessThanCapacity_isNotFull 749 muestras → isFull false
│   ├── buffer_afterAddingExactCapacity_isFull       750 muestras → isFull true
│   ├── buffer_snapshot_returnsElementsInChronologicalOrder  orden cronológico exacto
│   ├── buffer_afterOverflow_containsMostRecentSamples       ventana deslizante correcta
│   ├── buffer_snapshot_whenNotFull_returnsEmptyArray        sin datos parciales al CNN (< 750)
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
└── data/WearDataLayerManagerTest.kt      ← Tests del protocolo OSD (formato JSON, Fase A)
    ├── samplesToJson_producesSamplesArrayWithCorrectCount   {"samples":[...]} con N elementos
    ├── samplesToJson_preservesValuesInOrder                 valores en orden (1,2,3,...)
    ├── samplesToJson_sequentialPattern_matchesGrahamProtocol  [1.0..750.0] para validación
    ├── samplesToJson_emptyArray_producesEmptySamples        caso borde array vacío
    ├── parseAlarmState_readsAlarmStateFromOsdJson           {"alarm_state":N} → N (0/1/2)
    ├── parseAlarmState_toleratesExtraFieldsAndWhitespace    robusto a campos extra
    ├── parseAlarmState_returnsNullOnMalformed_withoutCrashing  payload roto → null, no crashea
    ├── alarmStatePath_matchesOsdProtocol                    contrato "/osd/alarm_state"
    └── accelDataPath_matchesOsdProtocol                     contrato "/osd/accel_data"
```

**Resultado:** `./gradlew :wear:testDebugUnitTest` corre **59 tests, todos verdes** — incluye
`WearModuleTest`, `CircularBufferTest`, `CsvLoggerTest`, `WearDataLayerManagerTest`,
`AlarmStateManagerTest` y `SeizureMonitorServiceTest` (este último con los tests de contrato de
C1/H1, ver DEC-041 y DEC-044/045 en `DECISIONS.md`).

> 🤖 **CI activo:** cada push y cada Pull Request corre estos tests + `lintDebug` automáticamente
> vía GitHub Actions (`.github/workflows/ci.yml`). `main` está protegido: nada entra sin el check
> en verde. Ver DEC-042 y DEC-043.

**¿Qué es Robolectric?**

Android necesita un dispositivo (físico o emulador) para ejecutar tests que usan APIs del sistema como `AssetManager`. Robolectric simula el sistema Android en la JVM, sin hardware real. El resultado: tests que corren en segundos en cualquier PC.

```
Sin Robolectric:                Con Robolectric:
────────────────                ───────────────────────────
Necesita watch o emulador   →   Corre en la PC, sin dispositivo
~2-5 minutos por suite      →   ~5-15 segundos
Requiere ADB conectado      →   ./gradlew :wear:test
```

> Cómo correr los tests sin Android Studio (build por línea de comandos): ver `BUILD_SETUP.md`.

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
print(f"\nMagnitud media: {df['magnitude'].mean():.3f} milli-g")
# ≈ 1000 milli-g → TYPE_ACCELEROMETER funcionando (1g de gravedad en reposo)
```

**Qué verificar en los datos crudos antes de confiar en la detección:**

| Métrica | Valor esperado | Cómo verificarlo |
|---------|---------------|-----------------|
| Frecuencia media | ~40ms entre muestras | `df['delta_ms'].mean()` |
| Jitter | std < 5ms | `df['delta_ms'].std()` |
| Magnitud en reposo | 950–1050 milli-g | `df['magnitude'].mean()` con reloj quieto |
| Gaps largos | < 5 instancias > 200ms | `(df['delta_ms'] > 200).sum()` |

---

## Protocolo de validación del transporte (Graham Jones) — Fase 2.1

Antes de conectar el modelo CNN al Data Layer, verificar que el transporte Bluetooth es confiable con dos pasos:

### Paso 1: modo secuencial (`isSequentialMode = true` — activo por defecto en DEBUG)

El reloj envía números secuenciales **como JSON** `{"samples":[1.0, 2.0, 3.0, ...]}` (chunks de
125, con numeración continua entre chunks) en lugar de datos reales. En el logcat del teléfono:

```
adb logcat -s SdDataSourceAw:D
# Si el transporte funciona: los samples llegan en orden 1,2,3,... a través de los chunks
# Si hay desorden: los valores llegan salteados o repetidos → problema de transporte/orden
```

> Nota: el contrato de transporte es **JSON UTF-8**, no binario. Ver DEC-046 en `DECISIONS.md`.
> Queda **por confirmar** si este protocolo de validación de Graham sigue vigente (ver T3 del plan).

### Paso 2: reloj quieto (`isSequentialMode = false`)

Cambiar en `SeizureMonitorService.companion`:
```kotlin
var isSequentialMode: Boolean = false  // datos reales
```

Con el reloj en reposo sobre la mesa, verificar ~1000 milli-g en logcat:
```
# Correcto: magnitud ≈ 1000 milli-g (1g de gravedad con TYPE_ACCELEROMETER)
# Incorrecto: magnitud ≈ 0 → se estaría usando TYPE_LINEAR_ACCELERATION
```

Solo cuando ambos pasos pasen, el transporte está validado y se puede conectar el modelo.

---

## Cómo correr los tests

Hay dos tipos de tests con propósitos distintos:

Los tests unitarios (Robolectric, en `src/test/`) corren en la PC, sin dispositivo. Para
correrlos sin Android Studio, ver `BUILD_SETUP.md`.

### Tests unitarios (sin watch)

```bash
./gradlew :wear:testDebugUnitTest

# Output esperado: 59 tests, todos verdes
# WearModuleTest, CircularBufferTest, CsvLoggerTest,
# WearDataLayerManagerTest (formato JSON del protocolo OSD),
# AlarmStateManagerTest, SeizureMonitorServiceTest (incluye tests de contrato C1/H1)
# BUILD SUCCESSFUL
```

> 🤖 Estos mismos tests + `lintDebug` corren en cada push y PR vía GitHub Actions. Ver DEC-042.

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

# Ver logs en tiempo real (reloj + lo que recibe la app OSD)
adb logcat -s SeizureGuard:D WearDataLayerManager:D SdDataSourceAw:D
```

### Qué hace cada script

| Script | Qué hace |
|--------|---------|
| `scripts/connect_watch.sh [IP]` | Conecta al watch via ADB, verifica estado, muestra modelo/Android version |
| `scripts/deploy_wear.sh` | Build debug + instala el APK del reloj |
| `scripts/deploy_wear.sh --tests-only` | Solo corre tests unitarios (sin watch) |

---

## Cómo compilar y testear (sin Android Studio)

Este proyecto se buildea por **línea de comandos**, no requiere Android Studio. El paso a paso
completo (instalar JDK 17, Android SDK cmdline-tools, `local.properties` y el gradle wrapper)
está en **`BUILD_SETUP.md`**. Una vez configurado:

```powershell
$env:JAVA_HOME = "...\jdk-17..."   # JDK 17 (ver BUILD_SETUP.md)
$env:ANDROID_HOME = "C:\Android"
.\gradlew.bat :wear:test           # corre los tests unitarios
.\gradlew.bat :wear:assembleDebug  # genera el APK del reloj
```

### Requisitos

- JDK 17 (AGP 8.5 no garantiza el 21)
- Android SDK API 34 + build-tools 34 (cmdline-tools, sin Android Studio)
- Samsung Galaxy Watch 8 + la app OpenSeizureDetector V5.0 en el teléfono (para pruebas reales)

---

## Plan de desarrollo por fases

### Fase 0: Setup del proyecto (COMPLETADA ✅)
- [x] **0.1** Estructura del módulo `:wear` — Smoke tests verdes (el `:phone` se retiró luego: la inferencia es de OSD)
- [x] **0.2** Stack de dependencias (Coroutines, Wear Compose, Wear Data Layer, KSP). **Sin TFLite/ExecuTorch** — la inferencia corre en la app OSD
- [x] **0.3** Entorno de build por CLI sin Android Studio (`BUILD_SETUP.md`) + suite de tests unitarios verde (59 tests) + CI en GitHub Actions
- [x] **0.4** ADB Wireless — scripts de conexión/deploy al reloj

### Fase 1: Captura de sensores (wear)
- [x] **1.1** ForegroundService con notificación persistente ("SeizureGuard activo") + UI toggle en MainActivity
- [x] **1.2** WakeLock + lifecycle management (evitar que el reloj duerma)
- [x] **1.3** SensorManager: acelerómetro 3D a 25Hz (TYPE_ACCELEROMETER, 40ms period, salida en milli-g)
- [ ] **1.4** Samsung Privileged Health SDK (opcional — mejor acceso a sensores)
- [x] **1.5** Ring buffer circular de 750 muestras + cálculo de magnitud vectorial en milli-g
- [x] **1.6** Logging a CSV (para verificar y analizar los datos crudos)

### Arquitectura: el reloj alimenta la app OSD V5.0 (NO construimos phone app)

La inferencia, el umbral y las alarmas son responsabilidad de la **app OpenSeizureDetector V5.0**
(rama beta). Este repo solo aporta el **lado reloj**, compatible con `SdDataSourceAw`.

Cada fase se trackea en **dos estados**: **Agent-Done** (código + tests verdes + Safety Reviewer
PASS + PR aprobado) y **Field-Done** (validado en hardware con la app OSD real, por el humano).

#### Fase A: Compatibilidad reloj ↔ OSD V5.0
- [x] (base) Captura 25Hz, milli-g, CircularBuffer 750, Wear Data Layer `/osd/accel_data` + `/osd/alarm_state`, haptics+UI (Fases 0/1/2.1/2.2)
- **A.1** Verificar contrato contra `SdDataSourceAw.java` (paths + bytes) — Agent-Done [ ]
- **A.2** Alinear tamaño de chunk con lo que espera OSD (DEC-039) — Agent-Done [ ]
- **A.3** Modo debug de números secuenciales para validación de Graham — Agent-Done [ ]

#### Fase B: Retirar el módulo :phone (código muerto)
- **B.1** Borrar `phone/` (la inferencia la hace OSD) — Agent-Done [ ]
- **B.2** Quitar `:phone` de `settings.gradle.kts` + borrar restos de ML (`.pte`, TFLite) — Agent-Done [ ]
- **B.3** Verificar que `:wear` compila y sus tests pasan — Agent-Done [ ]

#### Fase C: Documentación + comunidad
- **C.1** Actualizar README/CAREGIVER_GUIDE/CLINICAL_SIGNOFF a la arquitectura "reloj → OSD" — Agent-Done [ ]
- **C.2** Post en GitHub discussion #69 (definir interfaz AndroidWear) — Agent-Done [ ]

#### Fase D: Validación de campo (hardware-gated — ver `HARDWARE_RUNBOOK.md`)
> SOLO un humano con el Watch 8 + la app OSD instalada. El agente prepara el runbook e interpreta.
- **D.1** Instalar OSD V5.0 beta APK + developer mode + activar AW data source — Field-Done [ ]
- **D.2** Validación secuencial `[1.0..750.0]` (orden correcto en OSD) — Field-Done [ ]
- **D.3** Bench test: reloj quieto → un eje ~1000 milli-g — Field-Done [ ]
- **D.4** End-to-end: simular convulsión → OSD alarma + SMS — Field-Done [ ]
- **D.5** Test nocturno + ajuste de umbral en la **config de la app OSD** (firma clínica) — Field-Done [ ]

### Fase 5: Mejora del modelo (futuro — lo hace OSD)
> El modelo lo entrena y distribuye OpenSeizureDetector. Esta fase es contribución upstream, no de este repo.
- [ ] Solicitar acceso a datos OSDB y analizar el dataset
- [ ] Comparar DeepEpiCnn Run24 vs versiones futuras del modelo
- [ ] Agregar HR + SpO2 como features adicionales
- [ ] (no aplica a este repo) Google Play Store

---

## El modelo `deepEpiCnn_2026_01_24_Run24.pte`

El modelo **no vive en este repo** — lo trae y lo corre la app **OpenSeizureDetector V5.0**. Es un
modelo PyTorch exportado a ExecuTorch (`.pte`), recomendado por OSD en `osdapi.org.uk`.

**Fuente:** [OpenSeizureDetector/Android_Pebble_SD](https://github.com/OpenSeizureDetector/Android_Pebble_SD) (rama `beta`, V5.0) — ahí está el `.pte`, la dependencia `org.pytorch:executorch-android:1.0.1` y el código de inferencia (`SdAlgMl.java`).

Licencia: GPL v3 (el proyecto completo hereda esta licencia).

---

## Datos de entrenamiento (OSDB)

Para la Fase 5 (reentrenamiento del modelo) se necesita acceso al Open Seizure Database:

- Email: osdb@openseizuredetector.org.uk
- Explicar: qué se va a hacer con los datos + confirmar cumplimiento de la licencia

---

## Licencia

Open source. Basado en OpenSeizureDetector (GPL v3).
