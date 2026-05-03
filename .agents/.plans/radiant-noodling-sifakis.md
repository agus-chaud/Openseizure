# SeizureGuard — Fases Granulares de Desarrollo

## Context

**Proyecto**: App de detección de convulsiones nocturnas para Samsung Galaxy Watch 8.
**Stack**: Kotlin 2.0.21 + Wear OS 4 + PyTorch ExecuTorch (DeepEpiCnn Run24, 425KB) + SensorManager estándar.
**Arquitectura**: Dos módulos Android — `wear` (captura + transporte) y `phone` (inferencia ML + alertas).
**Decisión clave (DEC-027)**: ML corre en el TELÉFONO, no en el reloj. Watch → raw accel data → phone → alarmState → watch.
**Compatibilidad**: Protocolo Wear Data Layer compatible con OSD V5.0 (`SdDataSourceAw.java`).
**Modelo**: `deepEpiCnn_2026_01_24_Run24.pte` — recommended en `osdapi.org.uk/static/ml_models/index.json`.

---

## Pregunta: ¿Cursor en vez de Android Studio?

**Respuesta corta: SÍ, con matices.**

| Necesidad | Cursor | Android Studio |
|-----------|--------|----------------|
| Escribir Kotlin + Compose | ✅ Excelente (AI assist) | ✅ |
| Gradle (build/run desde terminal) | ✅ via terminal | ✅ integrado |
| ADB wireless + deploy to watch | ✅ via terminal `adb` | ✅ GUI |
| Emulador Wear OS | ❌ No incluido | ✅ integrado |
| Debugger con breakpoints | ⚠️ Más manual | ✅ integrado |
| XML layout editor | N/A (usamos Compose) | ✅ (no necesario) |

**Recomendación**: Usá Cursor para escribir código + Android Studio **instalado** (sin usarlo como IDE principal) para correr el emulador y el AVD Manager. Los builds los hacés con `./gradlew` desde el terminal de Cursor.

El flujo real es: **Cursor** (código + AI) → `./gradlew assembleDebug` → `adb install` → test en watch físico.

---

## Fases Granulares (estado actual)

### FASE 0 — Setup y Preparación ✅ COMPLETADA

**0.1 — Estructura del proyecto multi-módulo** ✅
- `settings.gradle.kts` con módulos `:wear` y `:phone`

**0.2 — Gradle y dependencias** ✅
- KSP (no kapt), coroutines 1.8.1, Room 2.6.1, Compose BOM 2024.05.00

**0.3 — Integrar modelos** ✅
- `wear/src/main/assets/cnn_v024.tflite` — bundled pero NO usado para inferencia (over-trained, confirmado por Graham Jones)
- `phone/src/main/assets/deepEpiCnn_2026_01_24_Run24.pte` — modelo real, 425KB, descargado de `osdapi.org.uk`
- Input: 750 muestras (30s a 25Hz), formato `1d_mag` (magnitud en milli-g)

**0.4 — Setup dispositivo físico + ADB** ✅
- ADB Wireless: `adb connect <watch-ip>:5555`
- Deploy confirmado en Samsung Galaxy Watch 8

---

### FASE 1 — Wear App: Captura de Sensores ✅ COMPLETADA

**1.1 — Foreground Service skeleton** ✅
- `SeizureMonitorService` con `startForeground()`, notificación persistente, `START_STICKY`

**1.2 — WakeLock y lifecycle correcto** ✅
- `PARTIAL_WAKE_LOCK`, timeout 10h, `isHeld` guard en `releaseWakeLock()`
- `serviceScope` ligado al lifecycle del Service

**1.3 — SensorManager estándar** ✅
- `TYPE_ACCELEROMETER` a 25Hz (`SENSOR_SAMPLING_PERIOD_US = 40_000`)
- **CRÍTICO**: usar `TYPE_ACCELEROMETER` (con gravedad), NO `TYPE_LINEAR_ACCELERATION`
- Conversión: `magnitudeMilliG = sqrt(x²+y²+z²) * (1000f / 9.81f)`
- `CsvLogger`: `raw_accel_YYYYMMDD_HHmmss.csv` en `getExternalFilesDir()/logs/` (solo DEBUG)

**1.4 — Samsung Privileged Health SDK** — DESCARTADO
- SensorManager estándar es suficiente para el MVP

**1.5 — Ring buffer de 750 muestras** ✅
- `CircularBuffer(capacity = 750)` — 30 segundos a 25Hz
- `magnitude = sqrt(x² + y² + z²) * MS2_TO_MILLIG`
- Thread-safe via `synchronized(lock)`, `snapshot()` retorna copia cronológica

**1.6 — Logging y verificación de datos** ✅
- CSV por sample con timestamp, x, y, z, magnitude
- 49 tests Robolectric en verde

---

### FASE 2 — Wear Data Layer + Respuesta a Alarmas ✅ COMPLETADA

> Objetivo: Watch envía raw data al teléfono, recibe alarmState, activa hápticos y UI reactiva.

**2.1 — Wear Data Layer: canal watch → phone** ✅
- `WearDataLayerManager` con `MessageClient` (no `DataClient` — streaming, no sincronización)
- `sendAccelData(samples: FloatArray)`: serializa 750 floats en `ByteArray` little-endian, path `/osd/accel_data`
- `addAlarmStateListener`: escucha `/osd/alarm_state`, decodifica Int del primer byte
- Paths compatibles con OSD V5.0 `SdDataSourceAw.java`

**Modo de prueba secuencial** (`BuildConfig.DEBUG`):
- En lugar de datos reales, envía `FloatArray(750) { i -> (i+1).toFloat() }` (valores 1.0 a 750.0)
- El teléfono debe recibir exactamente esos números en orden → confirma que el transporte funciona

**2.2 — Respuesta al alarmState (hápticos + UI reactiva)** ✅
- `AlarmStateManager.handleAlarmState(Int)`:
  - `0` = OK → no-op
  - `1` = WARNING → pulso 100ms, amplitude 80
  - `2+` = ALARM → `createWaveform(3×500ms, amplitude 255, no-repeat)`
- `SeizureMonitorService.alarmState: StateFlow<Int>` en companion object
- `MainActivity` observa con `collectAsState()`, UI con color amarillo/rojo

---

### FASE 2 — Validación del transporte (pendiente antes de Fase 3)

> Objetivo: Confirmar que los datos que llegan al phone son correctos ANTES de correr ML.

**Paso 1 — Test de números secuenciales** (implementado en 2.1)
- Activar `isSequentialMode = BuildConfig.DEBUG`
- Watch envía [1.0, 2.0, ..., 750.0]
- Phone loguea los valores recibidos → deben ser exactamente esos, en orden
- Entregable: Log en phone mostrando secuencia 1-750 sin pérdida ni reordenamiento

**Paso 2 — Bench test con datos reales**
- Desactivar `isSequentialMode`, dejar watch quieto sobre la mesa
- Expectativa física: `TYPE_ACCELEROMETER` con gravedad → un eje ≈ **1000 milli-g**, los otros dos ≈ 0 milli-g, magnitud total ≈ **1000 milli-g**
- Verificar en CSV (`raw_accel_*.csv`) que los valores coinciden con la expectativa
- Entregable: CSV con magnitud estable ≈ 1000 milli-g

**Paso 3 — Interoperabilidad con OSD V5.0 beta**
- Instalar **OSD V5.0 beta** (app de Graham Jones) en el teléfono
- Conectar con nuestra wear app
- OSD V5.0 debe recibir `/osd/accel_data` y procesar como si fuera su propio sensor
- Verificar que alarmState que OSD devuelve por `/osd/alarm_state` llega correctamente al watch
- Entregable: OSD V5.0 y SeizureGuard watch se comunican sin modificar OSD

---

### FASE 3 — Phone App: Inferencia ML + Alertas

> Objetivo: Phone recibe raw accel data del watch, corre DeepEpiCnn Run24, envía alarmState de vuelta, notifica al cuidador.

**3.1 — Módulo phone: setup inicial**
- `phone/build.gradle.kts`: deps ExecuTorch, Wear Data Layer, Room, Compose
- `AndroidManifest.xml`: permisos INTERNET, VIBRATE, RECEIVE_BOOT_COMPLETED, SEND_SMS
- Entregable: `:phone` módulo compila

**3.2 — Cargar DeepEpiCnn Run24 con ExecuTorch**
- `phone/src/main/assets/deepEpiCnn_2026_01_24_Run24.pte` (ya descargado, 425KB)
- `ExecuTorchModule.load("deepEpiCnn_2026_01_24_Run24.pte")`
- Input: `FloatArray(750)` de magnitudes en milli-g
- Output: probabilidad de convulsión (float entre 0 y 1)
- Entregable: `InferenceEngine.runInference(FloatArray(750))` retorna probabilidad en < 200ms

**3.3 — WearDataLayerService en phone**
- `WearableListenerService` que escucha `/osd/accel_data`
- Deserializa ByteArray → `FloatArray(750)` (little-endian)
- Llama a `InferenceEngine.runInference()`
- Envía resultado como `Int` a `/osd/alarm_state` de vuelta al watch
- Protocolo: `prob < 0.5` → 0 (OK), `0.5 ≤ prob < 0.8` → 1 (WARNING), `prob ≥ 0.8` → 2 (ALARM)
- Entregable: Ciclo completo watch → phone → watch funcionando

**3.4 — Full-screen alarm UI en phone**
- `AlarmActivity` con `FLAG_SHOW_WHEN_LOCKED | FLAG_TURN_SCREEN_ON`
- Mostrar: hora, duración estimada, botones "Cancelar" / "Llamar al cuidador"
- Sirena: `MediaPlayer` con sonido de alerta
- Entregable: Phone se enciende con pantalla de alarma al recibir ALARM

**3.5 — Notificación SMS a cuidadores**
- `SmsManager.sendTextMessage()` al número configurado
- Texto: "ALERTA SeizureGuard — posible convulsión detectada a las HH:MM"
- Entregable: SMS enviado con número de prueba

**3.6 — Pantalla de configuración**
- Número de contacto de emergencia
- Umbral de sensibilidad (slider 0.3–0.8, default 0.5)
- Horario nocturno (hora inicio / hora fin)
- `DataStore` para persistencia
- Entregable: Settings persisten entre reinicios

**3.7 — Room DB: historial de eventos**
- Entity: `SeizureEvent(id, timestamp, duration, maxProbability, acknowledged)`
- Lista en Compose con `LazyColumn`
- Entregable: Cada alarma queda registrada

---

### FASE 4 — Validación Nocturna Real

> Objetivo: App estable para uso nocturno real, tasa de falsas alarmas conocida.

**Paso 1 — Test de números secuenciales** ← ya planificado en Fase 2-validación

**Paso 2 — Bench test (watch quieto = 1000 milli-g)** ← ya planificado en Fase 2-validación

**Paso 3 — Instalar OSD V5.0 beta**
- Descargar APK de OSD V5.0 beta desde el repo oficial de Graham Jones
- Instalar en phone de prueba
- Conectar con nuestra wear app
- Verificar interoperabilidad completa (nuestro watch ↔ OSD phone)
- Esto confirma compatibilidad antes de nuestra implementación propia de phone

**4.1 — Test de integración end-to-end (manual, 30 min)**
- Encender modo nocturno → esperar 30 min → revisar logs
- Verificar: Service no murió, ventanas enviadas, inference corriendo, estados correctos
- Entregable: Checklist de integración completado

**4.2 — Test nocturno real (8h)**
- Dormir con watch + ambas apps activas toda la noche
- Registrar: ¿cuántas alarmas? ¿eran falsas? ¿el watch y el phone duraron la noche?
- Entregable: Informe de Noche 1

**4.3 — Análisis de falsas alarmas + tuning**
- Revisar CSV de probabilidades durante la noche
- Ajustar umbral y ventanas consecutivas para reducir falsas alarmas
- Target: < 1 falsa alarma por noche
- Entregable: Tasa validada

**4.4 — Edge cases**
- Watch sacado de la muñeca durante la noche → detectar y notificar
- Batería baja → alarma antes de apagarse
- Pérdida de conexión BT con phone → modo standalone (vibración directa en watch)
- Entregable: Los 3 edge cases manejados sin crashes

**4.5 — UI/UX polish**
- Tile de Wear OS para quick-start/stop
- Complicación que muestra estado actual (ON/OFF)
- Onboarding en primera apertura
- Entregable: UX completo

---

### FASE 5 — Upgrade Path (Futuro)

**5.1 — Solicitar acceso a datos OSDB** → Email a osdb@openseizuredetector.org.uk (ya iniciado con Graham)
**5.2 — Contribuir datos propios al OSDB** → Post en discusión #69
**5.3 — A/B testing de modelos** → Comparar CNN v0.24 vs DeepEpiCnn Run24 vs versiones futuras
**5.4 — HR + SpO2 como features** → Enriquecer el modelo con señales cardíacas
**5.5 — Google Play Store** → Publicar como app open-source

---

## Orden de ejecución actualizado

```
✅ 0.1 → 0.2 → 0.3 → 0.4   (setup)
✅ 1.1 → 1.2 → 1.3 → 1.5 → 1.6   (sensores)
✅ 2.1 → 2.2   (data layer + hápticos)
   ↓
⏳ Validación-Paso1 (números secuenciales)
   ↓
⏳ Validación-Paso2 (bench test 1000 milli-g)
   ↓
⏳ Validación-Paso3 (OSD V5.0 beta interop)
   ↓
⏳ 3.1 → 3.2 → 3.3 → 3.4   (phone ML + alarmas)
   ↓
⏳ 4.1 → 4.2 → 4.3   (primera noche real)
   ↓
⏳ 3.5 → 3.6 → 3.7 → 4.4 → 4.5   (polish + features completos)
```

---

## Archivos críticos

| Archivo | Rol |
|---------|-----|
| `wear/src/main/assets/cnn_v024.tflite` | Modelo viejo — bundled pero NO usado |
| `phone/src/main/assets/deepEpiCnn_2026_01_24_Run24.pte` | Modelo real de inferencia (425KB) |
| `wear/src/main/java/.../SeizureMonitorService.kt` | Core del monitoreo en watch |
| `wear/src/main/java/.../data/WearDataLayerManager.kt` | Transport watch → phone y phone → watch |
| `wear/src/main/java/.../ml/CircularBuffer.kt` | Ring buffer 750 muestras, thread-safe |
| `wear/src/main/java/.../alarm/AlarmStateManager.kt` | Hápticos según alarmState (0/1/2+) |
| `wear/src/main/java/.../MainActivity.kt` | UI reactiva con StateFlow |
| `phone/src/main/.../WearDataLayerService.kt` | (Fase 3) Escucha /osd/accel_data, corre ML |
| `phone/src/main/.../InferenceEngine.kt` | (Fase 3) Wrapper ExecuTorch |
| `phone/src/main/.../AlarmActivity.kt` | (Fase 3) UI full-screen de alarma |

---

## Decisiones críticas (resumen)

| # | Decisión | Por qué |
|---|----------|---------|
| DEC-023 | `TYPE_ACCELEROMETER` (con gravedad) | Graham Jones: modelo entrenado con gravedad incluida |
| DEC-024 | Unidades milli-g | Graham Jones: `MS2_TO_MILLIG = 1000f / 9.81f` |
| DEC-025 | Buffer de 750 muestras (30s) | DeepEpiCnn Run24 requiere `input_size: 750` |
| DEC-027 | ML en teléfono, no en watch | Arquitectura OSD V5.0, ExecuTorch más eficiente en phone |
| DEC-033 | `MessageClient` (no `DataClient`) | Streaming unidireccional, sin sincronización de estado |
| DEC-034 | Little-endian en ByteArray | Compatibilidad con `SdDataSourceAw.java` de OSD V5.0 |
| DEC-035 | Modo secuencial para testing | Valida transporte antes de usar datos reales |

---

## Verificación por fase

Cada fase se verifica con:
1. **Compilar** sin errores (`./gradlew build`)
2. **Test unitario** verde (cuando aplica)
3. **Deploy en watch físico** y confirmar entregable en logcat o UI
4. No avanzar a la siguiente fase hasta que el entregable esté confirmado

---

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | — |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR (PLAN) | 5 issues, 1 critical gap |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | — |

**OUTSIDE VOICE:** Claude subagent ran — 5 findings: fixture sintético (resuelto con FlatBuffer Python), FileDescriptor leak (resuelto en impl), Robolectric Wear OS assumption (pending verification), package lifecycle pressure Fase 2.1 (TODO-002), missing runtime detection (cubierto por ModelLoadException).

**UNRESOLVED:** 0 decisiones abiertas.

**VERDICT:** ENG REVIEW CLEARED — Fases 0-2.2 completadas. Próximo paso: validación del transporte (números secuenciales + bench test) antes de iniciar Fase 3.
