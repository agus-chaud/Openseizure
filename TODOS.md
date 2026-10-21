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
>
> **TODO vivo:** el único ítem accionable de este archivo es **TODO-003** (hueco de gobernanza en
> `CLINICAL_SIGNOFF.md`, agosto 2026). Los TODO-001 y 002 son historia.

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

---

## TODO-003: `CLINICAL_SIGNOFF.md` no tiene categoría para datos de salud almacenados

**What:** Extender el proceso de firma clínica con una segunda categoría de escalamiento —
*decisiones de gobernanza de datos*— además de la que ya existe para constantes de lógica de
detección. Debe cubrir: qué datos del paciente se almacenan, dónde viven, quién puede leerlos,
cuánto se retienen, cómo se exportan y cómo se borran.

**Why:** Detectado durante la exploración SDD `context-logging` (agosto 2026). La tabla de
escalamiento actual solo contempla umbrales, conteos de ventana y techo de latencia. Una propuesta
para empezar a guardar sueño, estrés e historial de convulsiones del paciente **no dispararía
ninguna regla del documento tal como está escrito** — y eso no es que el proceso lo apruebe, es que
el proceso ni siquiera hace la pregunta. Un proceso que no puede decir "esperá" ante una decisión de
datos sensibles tiene un punto ciego, no una omisión.

Lo mismo aplica a `CAREGIVER_GUIDE.md`, que hoy le promete al cuidador un sistema que "detecta un
patrón de movimiento y nada más". Si alguna vez se almacena historial, esa promesa deja de ser
cierta y hay que actualizarla en el mismo movimiento.

**Pros:** El costo es escribir una sección. Cierra un punto ciego de gobernanza antes de que haya
presión de tiempo para ignorarlo.

**Cons:** Ninguno real. No bloquea nada hoy porque, por DEC-049, el producto no almacena datos de
salud. Es deuda preventiva, no correctiva.

**Context:** **No es urgente hoy y sí es bloqueante mañana.** DEC-049 decidió que el cruce
sueño/convulsiones se hace fuera de la app, sin almacenar nada — así que por ahora no hay dato que
gobernar. Pero si alguna vez se retoma el enfoque de app companion en el teléfono (el "enfoque B"
de la exploración), este hueco pasa a ser **bloqueante antes de escribir la primera línea**, no
después.

La regla que conviene adoptar, por analogía con la regla a prueba de fallos que ya usa el
`safety-reviewer` ("ante la duda entre periférico y lógica de detección, tratalo como lógica de
detección"): **ante la duda de si algo constituye una nueva clase de dato de salud almacenado,
tratalo como que sí y escalá a firma humana.**

**Where to start:** `CLINICAL_SIGNOFF.md`, agregando una segunda tabla de escalamiento junto a la de
constantes de detección. Los criterios y el razonamiento completo están en
`openspec/changes/context-logging/exploration.md`, sección Q4.

**Depends on:** Nada. Se puede escribir en cualquier momento. Pasa a ser prerequisito si se retoma
el enfoque companion.
