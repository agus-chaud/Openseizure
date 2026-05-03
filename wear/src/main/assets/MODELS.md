# Modelos en producción

Esta carpeta contiene los modelos de inferencia embebidos en la Wear App.

## cnn_v024.tflite ✅ (integrado en Fase 0.3)

| Campo | Valor |
|-------|-------|
| Archivo | `cnn_v024.tflite` |
| Tamaño | 204.5 KB (209,456 bytes) |
| Identificador FlatBuffer | `TFL3` (TFLite schema v3) |
| Fuente | [OpenSeizureDetector/Android_Pebble_SD](https://github.com/OpenSeizureDetector/Android_Pebble_SD) — `app/src/main/assets/cnn_v0.24.tflite` |
| Licencia | GPL v3 |
| Descargado | Marzo 2026 |

### Shapes de los tensores

| Tensor | Shape | Tipo | Descripción |
|--------|-------|------|-------------|
| Input (índice 0) | `(1, 750, 1)` | FLOAT32 | 750 muestras de magnitud vectorial en milli-g del acelerómetro a 25Hz = 30 segundos de señal (shape canónico del `.tflite`; el mensaje Wear `/osd/accel_data` puede llevar `N` floats por envío — ver **DEC-039** en `DECISIONS.md`) |
| Output (índice 0) | `(1, 2)` | FLOAT32 | `[prob_normal, prob_seizure]` — valores entre 0 y 1, suman 1.0 (Softmax) |

### Cómo interpretar el output

```
output[0][0] = probabilidad de movimiento normal
output[0][1] = probabilidad de convulsión tónico-clónica

Ejemplo:
  [0.03, 0.97]  → 97% probabilidad de convulsión → disparar ALARM
  [0.95, 0.05]  → 95% probabilidad de normal     → estado OK
  [0.55, 0.45]  → ambiguo → estado WARNING (requiere N ventanas consecutivas)
```

### Preprocesamiento esperado (implementar en Fase 2.2)

```
Señal cruda del acelerómetro (x, y, z a 25Hz):
  ┌────────────────────────────────────┐
  │ t₀: (0.12, 9.81, 0.03)            │
  │ t₁: (0.15, 9.79, 0.05)            │
  │ ...                                │
  │ t₇₄₉: (0.10, 9.82, 0.02)         │
  └────────────────────────────────────┘
              │
              ▼
  Magnitud vectorial: √(x² + y² + z²) × (1000 / 9.81)  → milli-g
  → [1000.2, 999.8, ..., 1000.1]   (750 floats)
              │
              ▼
  Tensor ByteBuffer shape (1, 750, 1)
              │
              ▼
  tflite.Interpreter.run(input, output)
```

### Performance documentada (OpenSeizureDetector)

| Métrica | Valor |
|---------|-------|
| Sensibilidad (recall TC) | ~97% |
| Tasa de falsas alarmas | ~7% |
| Latencia de inferencia | ~15-30ms (CPU Wear OS) |
| Dataset de evaluación | Open Seizure Database (OSDB) |

### Notas técnicas

- El archivo NO debe ser comprimido en el APK.
  Configurado en `wear/build.gradle.kts`: `aaptOptions { noCompress += "tflite" }` (ver DEC-006)
- El modelo se carga con `MappedByteBuffer` via `AssetManager` mediante `TFLiteModelLoader.kt`.
- El umbral 0.5 es el valor por defecto del proyecto original. Puede calibrarse con datos OSDB (ver DEC-014).
- Para acceso a los datos de entrenamiento: osdb@openseizuredetector.org.uk
