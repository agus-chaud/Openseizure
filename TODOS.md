# SeizureGuard — TODOs

Items identificados durante reviews pero fuera del scope de la fase actual.

> ⚠️ **TODO-001 y TODO-002 quedaron OBSOLETOS** con el cambio de arquitectura del 2026-06-05:
> la inferencia (modelo, `Interpreter`, ExecuTorch) la hace la app **OpenSeizureDetector V5.0** en
> el teléfono — este repo NO tiene runtime de ML ni archivo `.tflite`/`.pte`. Se mantienen abajo
> como historia. No hay nada que hacer en ellos.
>
> **Trabajo abierto real (auditoría, junio 2026):** Críticos/Highs del módulo `:wear`. Cerrados:
> T1 (tests de contrato), T2 (CI), T4 (servicio zombie, C1), T5 (idempotencia, H1). **Pendiente
> el último Critical: T3** (el modo secuencial manda datos sintéticos a OSD por defecto en debug).
> El detalle del plan vive en engram (`audit/wear-2026-06`).

---

## TODO-001: androidTest para verificar aaptOptions noCompress en APK real  *(OBSOLETO)*

**What:** Test instrumented en `wear/src/androidTest/` que carga `cnn_v024.tflite` desde el APK empaquetado (no desde test assets).

**Why:** Los Robolectric tests cargan el modelo desde `src/test/assets/` (ruta directa en disco), nunca desde el APK comprimido. Si `aaptOptions { noCompress += "tflite" }` está mal configurado, el modelo queda comprimido y `FileChannel.map()` falla en producción, pero los tests pasan. Es el único way de detectar este bug.

**Pros:** Detecta bugs de packaging que Robolectric no puede ver. Un test, 10 líneas de código.

**Cons:** Requiere ADB conectado (watch o emulador Wear OS). Solo se puede correr en Fase 0.4+.

**Context:** El `aaptOptions` actual parece correcto en `wear/build.gradle.kts`. Este test es la red de seguridad para cuando se cambie la config de build o se agreguen variantes (debug/release).

**Where to start:** `wear/src/androidTest/java/com/seizureguard/wear/ml/TFLiteModelLoaderInstrumentedTest.kt`

**Depends on:** Fase 0.4 (ADB Wireless configurado con el watch)

---

## TODO-002: Definir ownership del Interpreter en TFLiteInferenceEngine  *(OBSOLETO)*

**What:** Documentar (o implementar) quién crea, cachea y cierra el `tflite.Interpreter` en `TFLiteInferenceEngine`.

**Why:** `TFLiteModelLoader.load()` retorna `MappedByteBuffer`. El `Interpreter` se crea en Fase 2.1. Sin una decisión explícita sobre su lifecycle, el `SeizureMonitorService` va a holdear estado de manera ad-hoc y habrá presión de diseño cuando el Service se destruya y recree.

**Pros:** Un ADR (Architecture Decision Record) de 5 líneas evita refactor en mitad de Fase 2.x.

**Cons:** Muy poco costo ahora, costo alto si se deja para después.

**Context:** El `Interpreter` NO es thread-safe. Necesita vivir en un scope controlado (probablemente `serviceScope` en `SeizureMonitorService`). Cuando el Service se destruye, el Interpreter debe ser cerrado y nulleado.

**Where to start:** Agregar docstring a `TFLiteModelLoader.kt` con la ownership contract antes de empezar Fase 2.1.

**Depends on:** Fase 1.1 (SeizureMonitorService creado)
