# SeizureGuard

App de detección de convulsiones nocturnas para **Samsung Galaxy Watch 8** (Wear OS — el sistema
operativo de los relojes inteligentes de Samsung/Google).

Este repo tiene **dos partes**: la app del reloj (`:wear`, captura el movimiento) y una app puente
en el teléfono (`:phone`, solo reenvía esos datos). Ninguna de las dos detecta la convulsión — eso lo
hace la app oficial **[OpenSeizureDetector](https://openseizuredetector.org.uk) V5.0** (otro
proyecto, rama beta), que corre el modelo **DeepEpiCnn Run24** con **PyTorch ExecuTorch** (el motor
que ejecuta el modelo ya entrenado). **El reloj no infiere: solo captura, transmite y reacciona a lo
que le contestan.**

> **Por qué hace falta una app puente:** Android exige que dos apps compartan la misma "identidad"
> (nombre de paquete + certificado de firma) para poder mandarse mensajes por Bluetooth entre reloj y
> teléfono. El reloj y OSD son de autores distintos y no la comparten, así que sin un intermediario
> los mensajes del reloj se pierden en silencio antes de llegar a OSD — esto se descubrió como
> bloqueante real en pruebas de campo. El puente sí comparte identidad con el reloj, y le habla a OSD
> por otra vía (HTTP local) que no tiene ese problema. Detalle técnico completo: `DECISIONS.md` →
> **DEC-050** (causa raíz) y **DEC-051** (solución elegida).

---

> ## 🛟 Invariante de seguridad #1 — el reloj SIEMPRE manda datos reales
>
> Existe un "modo validación" de desarrollo que manda **números sintéticos** (`1,2,3,...`) en vez
> del acelerómetro real, para probar el transporte. Si el detector recibe datos sintéticos creyendo
> que son reales, **una convulsión real no se detecta**. Por eso:
>
> - **Está APAGADO por defecto** en todos los builds, incluso debug (`isSequentialMode = false`).
> - Solo se activa a propósito, con `EXTRA_VALIDATION_MODE=true` en el Intent **y** solo en builds
>   debug. En **release es imposible activarlo**, ni queriendo.
> - El Intent normal de la app nunca lo enciende.
>
> **Regla para quien prueba en campo:** si dudás de si estás en modo validación, NO confíes en una
> "no-detección" como señal de que todo anda. Verificá que arrancaste el monitoreo sin el extra.
> Detalle técnico completo en `DECISIONS.md` → **DEC-047**.

---

> ## 🛟 Invariante de seguridad #2 — si el monitoreo se rompe, el reloj lo MUESTRA (sin vibrar)
>
> El peor estado de una app así no es la alarma falsa: es la **falsa sensación de seguridad** (el
> sistema dice "todo bien" mientras algo está roto). Un **watchdog** — un chequeo automático que
> corre cada 10 segundos, dentro del propio reloj — vigila que:
>
> - el acelerómetro siga emitiendo datos (si no hay una muestra nueva hace más de 10s, el sensor está
>   muerto), y
> - los datos sigan llegando a la app puente del teléfono (si no hay una entrega exitosa hace más de
>   40s, algo cortó la cadena), y
> - la app puente siga contestando con el estado de alarma (si no llega ninguna respuesta hace más de
>   40s, el reloj ya no sabe si OSD está funcionando).
>
> Si algo falla, el monitoreo pasa a **DEGRADADO**: la pantalla del reloj cambia a *"⚠ MONITOREO
> DEGRADADO"* y queda registrado, **sin vibrar**. **Una no-detección con el reloj en DEGRADADO NO
> significa "sin convulsión" — significa "no estoy mirando".**
>
> **Regla vigente en reloj y teléfono (desde el Batch 7, ya en `main`):** "ninguna falla del sistema
> suena ni vibra, solo una emergencia real lo hace" (`DECISIONS.md` → **DEC-057**). El reloj vibra
> solo ante los estados de alarma de OSD (2, 3 y 5) y da un aviso corto ante el estado 1; una falla
> del sistema en OSD (estados 4, 7 o un valor desconocido) se muestra en pantalla como falla, sin
> vibrar; y el estado "silenciado" (6) dice *"SILENCIADO, no avisa convulsiones"* en vez de
> "Monitoreo activo". Los tiempos de aviso (10s / 40s / 40s) son **calculados, no medidos** en el
> reloj real: la verificación en hardware sigue pendiente. Detalle técnico en `DECISIONS.md` →
> **DEC-048**, **DEC-057**, **DEC-059** y **DEC-061**.

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

Hoy el sistema son **tres apps en dos dispositivos**: el reloj (este repo, módulo `:wear`), una app
puente en el teléfono (este repo, módulo `:phone`) y la app oficial **OpenSeizureDetector V5.0**
(otro proyecto — no vive en este repo). El flujo real:

```
RELOJ (:wear, este repo)          PUENTE (:phone, este repo)           OSD V5.0 (otro proyecto)
─────────────────────────         ────────────────────────────         ────────────────────────
Acelerómetro 25Hz (TYPE_ACCELEROMETER)
  → magnitud √(x²+y²+z²) en milli-g
  → ring buffer
  → chunks de ~125 muestras (~5s)
        │  Wear Data Layer
        │  /osd/accel_data
        ▼
                                   Recibe el chunk (comparte
                                   identidad de firma con :wear,
                                   por eso Android SÍ le entrega
                                   el mensaje — antes, con OSD
                                   directo, lo descartaba)
                                   → lo reenvía por HTTP local
                                   ─────────────────────────────►
                                                                        Data source "Garmin" recibe
                                                                        → ExecuTorch + deepEpiCnn Run24
                                                                        → prob. de convulsión → umbral
                                                                        → alarma + sirena + SMS al
                                                                          cuidador
                                   ◄─────────────────────────────
                                   Consulta el estado de alarma
                                   y lo reenvía al reloj
        ┌───────────────────────────────────┘
        │  /osd/alarm_state
        ▼
Vibración háptica (solo WARNING / ALARM) + UI (OK / WARNING / ALARM / falla / silenciado / degradado)
```

**Por qué hay un puente en el medio, en vez del reloj hablándole directo a OSD:** Android exige que
dos apps compartan la misma "identidad" (nombre de paquete + certificado de firma) para poder
mandarse mensajes por Bluetooth entre reloj y teléfono. El reloj y OSD son de autores distintos y no
la comparten, así que el sistema **descarta el mensaje en silencio** antes de que OSD lo vea — un
bloqueante real que se encontró en pruebas de campo (`DECISIONS.md` → **DEC-050**). El puente
(`:phone`) sí comparte identidad con el reloj, así que la entrega reloj→puente funciona sin tocar el
código del reloj; el puente después le habla a OSD por HTTP local, una vía que no tiene ese problema
de identidad. Detalle completo en `DECISIONS.md` → **DEC-051** y
`openspec/changes/watch-osd-message-delivery/design.md`.

**Estado real (octubre 2026):** el puente (`:phone`) y el retargeting del reloj (`:wear`) ya están
mergeados en `main` (Batches 1 a 8 — ver el checklist de Fase E más abajo). El reloj tiene dos
"sabores" de build (`transport`): **`companion`** (el normal: le habla al puente) y **`osdDirect`**
(solo para desarrollo: le habla directo a OSD; muestra un cartel *"VERSIÓN DE PRUEBA"*, y su versión
de lanzamiento está deshabilitada a propósito). Lo que **no** se hizo todavía es la **verificación
en el reloj y el teléfono reales** (DV-1..DV-7): hasta entonces, nada de esto está probado de punta a
punta con el equipo real.

**Cómo se instala (dos APKs):** el APK del reloj (`:wear`, sabor `companion`) y el APK del puente
(`:phone`) tienen que estar firmados con la **misma clave**, y OSD tiene que estar instalado en el
teléfono con su fuente de datos en **"Garmin"** y su servidor web andando (puerto 8080). Paso a paso
en [`docs/GUIA_CONECTAR_RELOJ_TELEFONO.md`](docs/GUIA_CONECTAR_RELOJ_TELEFONO.md) y
`HARDWARE_RUNBOOK.md`.

**El reloj NO infiere.** Solo captura, transmite y reacciona a lo que le contestan. El modelo, el
umbral y las alertas son responsabilidad de la app OSD. Ver engram
`architecture/seizureguard-executorch-api`.

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

### Módulos: `:wear` + `:phone` (companion bridge, DEC-051/052)

> ⚠️ **Actualizado (septiembre 2026):** esta sección decía "módulo único `:wear`, no hay `:phone`".
> Eso dejó de ser cierto — ver DEC-050/051/052 en `DECISIONS.md`. El Wear Data Layer solo entrega
> mensajes entre apps con el mismo AppKey (`applicationId` + firma), y la app OSD no comparte el
> nuestro. Por eso se reintrodujo `:phone`, pero con un propósito **distinto** al que tenía antes
> de retirarse: no hace inferencia ni ML, es un **puente de transporte puro** reloj→OSD.

```
OpenSeizure/                         ← carpeta raíz del proyecto
├── wear/                            ← app del reloj (Wear OS) — com.seizureguard.wear
└── phone/                           ← companion bridge (Android) — mismo applicationId/firma
                                        que :wear, para que el AppKey del Wear Data Layer coincida
```

El "teléfono" en este sistema sigue siendo, del lado de la inferencia, la **app OpenSeizureDetector
V5.0** (proyecto aparte). Lo que corre `:phone` es solo el tramo intermedio: recibe del reloj por
`MessageClient`, reenvía por HTTP al `SdWebServer` embebido de OSD (`http://127.0.0.1:8080`, data
source "Garmin"), y devuelve el estado de alarma. Detalle completo en
`openspec/changes/watch-osd-message-delivery/design.md`.

**Analogía para data scientists:** `:wear` sigue siendo el `serving/` del sensor. `:phone` es el
adapter/proxy entre ese serving y el `model/`+`inference/` que vive en la app OSD, no un modelo propio.

### Estructura de archivos

```
OpenSeizure/
├── settings.gradle.kts          ← "este proyecto tiene 2 módulos: :wear, :phone" (desde Batch 2
│                                   de watch-osd-message-delivery, ver DEC-052)
├── build.gradle.kts             ← configuración global (solo declara qué versiones de
│                                   plugins existen, no los aplica)
├── signing.gradle.kts           ← firma compartida :wear/:phone (Batch 1, DEC-052)
├── gradle.properties            ← config global (android.useAndroidX, etc.)
├── gradle/
│   └── libs.versions.toml       ← Version Catalog: todas las versiones centralizadas
│                                   (el pip freeze / pyproject.toml de Android)
├── DECISIONS.md                 ← Por qué tomamos cada decisión técnica
├── HARDWARE_RUNBOOK.md          ← Pruebas reales con el reloj + la app OSD (sin Android Studio)
├── CAREGIVER_GUIDE.md           ← Guía para el cuidador (no técnico)
├── CLINICAL_SIGNOFF.md          ← Constantes clínicas (se firman en la config de OSD)
│
├── wear/                        ← Módulo del reloj
│   ├── build.gradle.kts         ← dependencias del reloj (Compose Wear, Wear Data Layer, etc.
│   │                              SIN TFLite/ExecuTorch — la inferencia es de OSD)
│   ├── src/
│   │   ├── main/java/com/seizureguard/wear/
│   │   │   ├── MainActivity.kt              ← pantalla principal: toggle inicio/stop
│   │   │   ├── logging/CsvLogger.kt         ← logging de muestras a CSV (Fase 1.6)
│   │   │   ├── ml/CircularBuffer.kt         ← ring buffer (clase de 750 por defecto; el servicio lo usa con 125 = un chunk, Fase 1.5)
│   │   │   ├── data/WearDataLayerManager.kt ← protocolo OSD (JSON samples / alarm_state)
│   │   │   ├── alarm/AlarmStateManager.kt   ← vibración según el alarmState de OSD (2/3/5 alarma; 4/7/desconocido: falla en silencio; 6: sin vibrar)
│   │   │   └── service/SeizureMonitorService.kt  ← ForegroundService nocturno (captura+transporte)
│   │   └── test/java/com/seizureguard/wear/
│   │       ├── WearModuleTest.kt
│   │       ├── logging/CsvLoggerTest.kt          ← tests del logger CSV (Robolectric)
│   │       ├── ml/CircularBufferTest.kt          ← tests del ring buffer (Robolectric)
│   │       └── data/WearDataLayerManagerTest.kt  ← tests del protocolo OSD (Robolectric)
│
└── phone/                       ← Companion bridge (en construcción, PR por PR — ver Fase E abajo)
    └── build.gradle.kts         ← mismo applicationId/firma que :wear, cero deps nuevas
                                    (HttpURLConnection, sin OkHttp/Retrofit)
```

> El modelo y su loader (`TFLiteModelLoader`, ExecuTorch) no están en este repo: la inferencia la
> hace la app OSD. El módulo `:phone` sí volvió — no para inferencia, sino como puente de
> transporte (ver arriba y DEC-051/052).

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

**Resultado:** `./gradlew :wear:testDebugUnitTest` corre los tests unitarios de **los dos sabores** del reloj (`companion` y `osdDirect`), todos verdes en CI (el
número exacto cambia con cada lote; ya no se fija acá) — incluye
`WearModuleTest`, `CircularBufferTest`, `CsvLoggerTest`, `WearDataLayerManagerTest`,
`AlarmStateManagerTest`, `DisplayStatusMapperTest` y `SeizureMonitorServiceTest` (este último con los tests de contrato de
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

> ⚠️ Este protocolo se escribió para cuando el reloj le hablaba directo a OSD (tag `SdDataSourceAw`).
> Con el flujo actual (reloj → puente `:phone` → OSD por data source "Garmin", ver DEC-060) ese tag y
> el modo secuencial tal como está descripto acá ya no aplican tal cual. Sigue sin confirmarse si vale
> la pena adaptar este protocolo a la nueva ruta (ver T3 del plan).

Antes de conectar el modelo CNN al Data Layer, verificar que el transporte Bluetooth es confiable con dos pasos:

### Paso 1: modo secuencial (`isSequentialMode = true` — APAGADO por defecto; solo se activa con `EXTRA_VALIDATION_MODE=true` en un build debug)

El reloj envía números secuenciales **como JSON** `{"samples":[1.0, 2.0, 3.0, ...]}` (chunks de
125, con numeración continua entre chunks) en lugar de datos reales. En el logcat del teléfono:

```
adb logcat -s SdDataSourceAw:D
# Si el transporte funciona: los samples llegan en orden 1,2,3,... a través de los chunks
# Si hay desorden: los valores llegan salteados o repetidos → problema de transporte/orden
```

> Nota: el contrato de transporte es **JSON UTF-8**, no binario. Ver DEC-046 en `DECISIONS.md`.
> Queda **por confirmar** si este protocolo de validación de Graham sigue vigente (ver T3 del plan).

### Paso 2: reloj quieto (`isSequentialMode = false`, el valor por defecto)

Arrancar el monitoreo normalmente, sin el extra de validación (el valor por defecto en `SeizureMonitorService.companion` es `var isSequentialMode: Boolean = false`, datos reales).

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

# Output esperado: todos verdes (compila y corre los dos sabores: companion + osdDirect)
# El CI corre este mismo comando y también `:phone:testDebugUnitTest` y los dos `lintDebug`.
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
| `scripts/deploy_wear.sh` | Build debug del sabor `companion` (`:wear:assembleCompanionDebug`) + instala el APK del reloj |
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
.\gradlew.bat :wear:assembleCompanionDebug  # genera el APK del reloj (sabor companion)
```

### Requisitos

- JDK 17 (AGP 8.5 no garantiza el 21)
- Android SDK API 34 + build-tools 34 (cmdline-tools, sin Android Studio)
- Samsung Galaxy Watch 8 + la app OpenSeizureDetector V5.0 en el teléfono (para pruebas reales)

---

## Plan de desarrollo por fases

### Fase 0: Setup del proyecto (COMPLETADA ✅)
- [x] **0.1** Estructura del módulo `:wear` — Smoke tests verdes (el `:phone` se retiró luego por duplicar la inferencia de OSD; septiembre 2026 se reintrodujo con otro propósito — ver Fase E)
- [x] **0.2** Stack de dependencias (Coroutines, Wear Compose, Wear Data Layer, KSP). **Sin TFLite/ExecuTorch** — la inferencia corre en la app OSD
- [x] **0.3** Entorno de build por CLI sin Android Studio (`BUILD_SETUP.md`) + suite de tests unitarios verde + CI en GitHub Actions
- [x] **0.4** ADB Wireless — scripts de conexión/deploy al reloj

### Fase 1: Captura de sensores (wear)
- [x] **1.1** ForegroundService con notificación persistente ("SeizureGuard activo") + UI toggle en MainActivity
- [x] **1.2** WakeLock + lifecycle management (evitar que el reloj duerma)
- [x] **1.3** SensorManager: acelerómetro 3D a 25Hz (TYPE_ACCELEROMETER, 40ms period, salida en milli-g)
- [ ] **1.4** Samsung Privileged Health SDK (opcional — mejor acceso a sensores)
- [x] **1.5** Ring buffer circular (la clase tiene capacidad 750 por defecto; el servicio del reloj lo usa con capacidad 125, un chunk de transporte, `SeizureMonitorService.kt` `BUFFER_CAPACITY`) + cálculo de magnitud vectorial en milli-g
- [x] **1.6** Logging a CSV (para verificar y analizar los datos crudos)

### Arquitectura: el reloj alimenta la app OSD V5.0 (vía companion `:phone`, desde septiembre 2026)

La inferencia, el umbral y las alarmas siguen siendo responsabilidad de la **app
OpenSeizureDetector V5.0** (rama beta) — eso no cambió. Lo que sí cambió: el reloj ya no le puede
hablar directo a OSD por Wear Data Layer (AppKey distinto — DEC-050), así que un módulo `:phone`
nuevo (mismo AppKey que `:wear`) hace de puente hacia el `SdWebServer` de OSD por HTTP. Ver Fase E
más abajo y `DEC-051`/`DEC-052`.

Cada fase se trackea en **dos estados**: **Agent-Done** (código + tests verdes + Safety Reviewer
PASS + PR aprobado) y **Field-Done** (validado en hardware con la app OSD real, por el humano).

#### Fase A: Compatibilidad reloj ↔ OSD V5.0
- [x] (base) Captura 25Hz, milli-g, CircularBuffer (750 por defecto; 125 en el servicio), Wear Data Layer `/osd/accel_data` + `/osd/alarm_state`, haptics+UI (Fases 0/1/2.1/2.2)
- **A.1** Verificar contrato contra `SdDataSourceAw.java` (paths + bytes) — Agent-Done [ ]
- **A.2** Alinear tamaño de chunk con lo que espera OSD (DEC-039) — Agent-Done [ ]
- **A.3** Modo debug de números secuenciales para validación de Graham — Agent-Done [ ]

#### Fase B: Retirar el módulo :phone (código muerto) — **SUPERADA, ver Fase E**
> ⚠️ Esta fase se ejecutó (el `:phone` original, de inferencia, se borró). Pero DEC-050 encontró que
> el Wear Data Layer no entrega mensajes entre apps de distinto AppKey, y DEC-051 eligió reintroducir
> `:phone` con un propósito distinto (puente de transporte, no inferencia). No es una reversión de
> esta fase — es un módulo nuevo con otro rol. Se deja como historia, no se re-abren estos ítems.
- [x] **B.1** Borrar `phone/` (la inferencia la hace OSD) — Agent-Done
- [x] **B.2** Quitar `:phone` de `settings.gradle.kts` + borrar restos de ML (`.pte`, TFLite) — Agent-Done
- [x] **B.3** Verificar que `:wear` compila y sus tests pasan — Agent-Done

#### Fase C: Documentación + comunidad
- **C.1** Actualizar README/CAREGIVER_GUIDE/CLINICAL_SIGNOFF a la arquitectura "reloj → OSD" — Agent-Done [ ]
- **C.2** Post en GitHub discussion #69 (definir interfaz AndroidWear) — Agent-Done [ ]

#### Fase E: SDD `watch-osd-message-delivery` — companion `:phone` como puente de transporte
> Causa raíz confirmada en DEC-050 (Wear Data Layer descarta mensajes entre apps con distinto
> AppKey). Enfoque elegido: DEC-051 (Opción F). Detalle completo en
> `openspec/changes/watch-osd-message-delivery/{proposal,design,tasks}.md`, espejo en engram
> `sdd/watch-osd-message-delivery/*`. Se entrega en 10 PRs encadenados (`stacked-to-main`).
> **GATE-0** (experimento WearSD+OSD-beta en hardware real) fue asumido como PASS por decisión
> explícita del usuario sin correrlo aún — ver DEC-052 y engram `.../gate-0-override`. Las tareas
> DV-1..DV-7 (verificación en hardware) siguen pendientes y son obligatorias antes de dar el
> feature por terminado.
- [x] **Batch 1** Firma compartida `:wear`/`:phone` (`signing.gradle.kts`) — [PR #12](https://github.com/agus-chaud/Openseizure/pull/12) — Agent-Done
- [x] **Batch 2** Scaffold del módulo `:phone` (manifest, build.gradle, recursos mínimos) — [PR #13](https://github.com/agus-chaud/Openseizure/pull/13) — Agent-Done
- [x] **Batch 3a** Codec/parser puros + tests — [PR #14](https://github.com/agus-chaud/Openseizure/pull/14) — Agent-Done
- [x] **Batch 3b** BridgeHealth + test de integración loopback — [PR #15](https://github.com/agus-chaud/Openseizure/pull/15) — Agent-Done
- [x] **Batch 4** `OsdHttpForwarder` — [PR #16](https://github.com/agus-chaud/Openseizure/pull/16) — Agent-Done
- [x] **Batch 5a** `OsdBridgeService` (core), en dos PRs apilados — [#17](https://github.com/agus-chaud/Openseizure/pull/17) (validación + estado de salud) y [#18](https://github.com/agus-chaud/Openseizure/pull/18) (servicio) — Agent-Done
- [x] **Batch 5b** `AlarmStateRelay` + `BridgeNotifications` + test del servicio, en dos PRs apilados — [#19](https://github.com/agus-chaud/Openseizure/pull/19) (relay) y [#20](https://github.com/agus-chaud/Openseizure/pull/20) (notificaciones + test) — Agent-Done
- [x] **Batch 6** `SetupActivity` + `BootReceiver` — [PR #21](https://github.com/agus-chaud/Openseizure/pull/21) — Agent-Done
- [x] **Batch 5c** (correctivo, solo `:phone`) el relay de alarma deja de reenviar `0` si OSD está congelado o el puente falla (hallazgo F1) — [#23](https://github.com/agus-chaud/Openseizure/pull/23) y [#22](https://github.com/agus-chaud/Openseizure/pull/22) — Agent-Done (falta DV-4 en hardware)
- [x] **Batch 5d** (política de fallas silenciosas, DEC-057, solo `:phone`) notificaciones de falla sin sonido ni vibración, registro de períodos de falla, resumen silencioso de la mañana y texto de setup — [#24](https://github.com/agus-chaud/Openseizure/pull/24), [#25](https://github.com/agus-chaud/Openseizure/pull/25), [#26](https://github.com/agus-chaud/Openseizure/pull/26) — Agent-Done (sin probar en dispositivo)
- [x] **Batch 5e** (endurecimiento, solo `:phone`, hallazgos F5/F6 y constante firmada `sample_freq == 25`) `sample_freq` exactamente 25, rechazo de chunks con las 125 muestras idénticas y reintento/re-registro del listener de mensajes — [#27](https://github.com/agus-chaud/Openseizure/pull/27) (CI verde) y [#28](https://github.com/agus-chaud/Openseizure/pull/28) (apilado sobre el #27) — mergeados, CI verde (128 tests de `:phone`) — Agent-Done
- [x] **Batch 7** Retargeting de `:wear` (mayor riesgo — toca la ruta de alarma): sabores `companion`/`osdDirect` y script de deploy seguro ([#29](https://github.com/agus-chaud/Openseizure/pull/29)); watchdog de entrada con constantes firmadas 10s/40s/40s ([#30](https://github.com/agus-chaud/Openseizure/pull/30)); política de fallas silenciosas y DEGRADADO solo visual ([#31](https://github.com/agus-chaud/Openseizure/pull/31)); la pantalla muestra DEGRADADO y borra el estado viejo ([#32](https://github.com/agus-chaud/Openseizure/pull/32)). Ver [`docs/SAFETY_FINDINGS_WATCH_OSD.md`](docs/SAFETY_FINDINGS_WATCH_OSD.md) — Agent-Done
- [x] **Batch 7d** (correctivo, `:wear`) reloj monotónico y frescura atómica del estado de alarma ([#33](https://github.com/agus-chaud/Openseizure/pull/33)); cartel "SILENCIADO, no avisa convulsiones", colores legibles y lectura segura del estado ([#34](https://github.com/agus-chaud/Openseizure/pull/34)) — Agent-Done
- [x] **Batch 8** Handshake de versión/compatibilidad: el reloj manda `contract_version` ([#35](https://github.com/agus-chaud/Openseizure/pull/35)); CI corre los tests y el lint de `:wear` y `:phone` en cada PR ([#36](https://github.com/agus-chaud/Openseizure/pull/36)); el teléfono compara versiones ([#37](https://github.com/agus-chaud/Openseizure/pull/37), [#38](https://github.com/agus-chaud/Openseizure/pull/38)); aviso silencioso "SeizureGuard: update needed" y nota en el resumen de la mañana ([#39](https://github.com/agus-chaud/Openseizure/pull/39), [#40](https://github.com/agus-chaud/Openseizure/pull/40), [#41](https://github.com/agus-chaud/Openseizure/pull/41)) — Agent-Done
- [ ] **Batch 9** Ajustes de documentación — 9a (documentación llevada a `main` y alineada con el código) en revisión; el resto del Batch 9 sigue abierto — Agent-Done
- [ ] **DV-1..DV-7** Verificación en hardware real (Galaxy Watch 8 + OSD beta) — Field-Done. **Pendiente: todavía no se hizo ninguna.**

#### Fase D: Validación de campo (hardware-gated — ver `HARDWARE_RUNBOOK.md`)
> SOLO un humano con el Watch 8 + la app OSD instalada. El agente prepara el runbook e interpreta.
- **D.1** Instalar OSD V5.0 beta APK + developer mode + activar la fuente de datos **"Garmin"** (no "Android Wear", DEC-051/DEC-060) — Field-Done [ ]
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
