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
> **TODO vivo:** **TODO-003** (hueco de gobernanza en `CLINICAL_SIGNOFF.md`, agosto 2026) y la
> sección **DV-1 a DV-9** al final del archivo — las verificaciones en hardware de
> `watch-osd-message-delivery`, movidas desde `openspec/` el 2026-10-21. Los TODO-001 y 002
> son historia.

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

---

## DV-1 a DV-9: verificaciones en hardware pendientes  *(watch-osd-message-delivery)*

**What:** Las verificaciones manuales en dispositivo que faltan para considerar validada la feature
`watch-osd-message-delivery`. El código está completo y mergeado en `main` (PRs #42-#46, Batch 9);
lo único abierto son estas pruebas, que necesitan el reloj y el teléfono.

**Why:** Hasta 2026-10 esta lista vivía solo en
`openspec/changes/watch-osd-message-delivery/tasks.md` y en engram
(`sdd/watch-osd-message-delivery/batch9-status`, obs #1471). Al retirarse SDD en gentle-ai 4.0.0 no
quedan herramientas que lean `openspec/`. Se copia acá para que la checklist no dependa de ese
directorio.

**Context:** PROP-SC y el gate E2E del design dicen que la feature **no se considera validada**
hasta que DV-1 a DV-9 pasen, en particular DV-6 y DV-8. No bloquean el merge de PRs individuales.
**DV-3 está hecha** (2026-10-07: `mDataFrequencyCheckEnabled` on by default; cadencia del reloj
fijada por DEC-068). DV-5 puede esperar, según `docs/SAFETY_FINDINGS_WATCH_OSD.md`.

### Pendientes

- [ ] **GATE-0.1** Correr `docs/EXPERIMENTO_WEARSD_OSD_BETA.md` de punta a punta en hardware real
      (reloj + teléfono). *Asumido PASS en DEC-052; no se volvió a correr de forma independiente.*
- [ ] **DV-1** Confirmar el comportamiento de `startForeground(connectedDevice)` cuando
      `BLUETOOTH_CONNECT` está denegado: debe fallar de forma ruidosa, nunca crashear.
      (Batch 5, T5.1/T5.2)
- [ ] **DV-2** Confirmar que `connectedDevice` está en la lista de tipos FGS permitidos en
      BOOT_COMPLETED para la versión de Android objetivo; si no, verificar el camino de degradación
      a notificación de T6.3. (Batch 6)
- [ ] **DV-4** Estado congelado / fuente equivocada: poner OSD en otra fuente de datos y, por
      separado, cerrar OSD. El reloj debe mostrar DEGRADADO dentro del techo firmado (link 60 s;
      OSD congelada ~75 s, calculado y no medido), con notificación silenciosa en el teléfono y sin
      sonido ni vibración. (Batch 5c, hallazgo de seguridad F1)
- [ ] **DV-5** Medir el costo de batería del teléfono del keep-alive de 10 s atado al reloj durante
      8 h; relajar a 15 s (`ALARM_STATE_STALE_MS` 25 s) si es material. *Puede esperar.* (Batch 7/8)
- [ ] **DV-6** Una corrida completa de 8 h de noche (gate E2E del design) antes de confiar en la
      feature en uso real. **Bloqueante para considerarla validada.**
- [ ] **DV-7** Inyección de fallas, de a una: (a) matar el listener de mensajes del companion,
      (b) matar OSD, (c) forzar el cierre del companion, (d) No Molestar, (e) notificaciones
      denegadas. Cada una debe terminar en falla visible (DEGRADADO en el reloj, notificación
      silenciosa en el teléfono) **sin sonido ni vibración**; (c) no produce aviso ni resumen.
      Registrar tiempo hasta la falla visible y si algo sonó. Clave en (a): el retry/self-heal del
      listener (Batch 5e, PR #28) nunca se probó en hardware.
      Pasos: `HARDWARE_RUNBOOK.md` sección 4.7. (DEC-057)
- [ ] **DV-8** Latencia clínica (techo 40 s, `techo_latencia_clinica` en `CLINICAL_SIGNOFF.md`):
      imitar una convulsión (sacudida fuerte y rítmica, 1-3 por segundo) y medir, desde el inicio de
      la sacudida, (a) vibración fuerte del reloj, (b) sonido de alarma de OSD en el teléfono del
      paciente, (c) SMS al cuidador. Mínimo 5 corridas; (a) y (b) deben estar en 40 s o menos en
      **todas**, si no el valor se revisa y se vuelve a firmar. Sub-medición opcional: la brecha
      entre la alarma del teléfono y la vibración del reloj contra el techo de relay de 8 s.
      Pasos: `docs/GUIA_PRUEBAS_RELOJ_REAL.md`. **Bloqueante para considerarla validada.**
- [ ] **DV-9** Qué ve el cuidador: (a) "MONITOREO DEGRADADO" en el reloj unos 80 s después de
      arrancar con el puente del teléfono detenido; (b) "SILENCIADO, no avisa convulsiones" con MUTE
      de OSD activo y sin vibración; (c) "SeizureGuard: update needed" con versiones reloj/teléfono
      desalineadas (opcional si no hay build desalineado disponible); (d) reinicio del teléfono con
      el puente (igual que DV-2); (e) el resumen matutino silencioso a las ~8:00.
      (Batches 5d, 7, 8, DEC-057)

### Follow-ups de review pendientes

Origen: engram obs #1471.

- [ ] **R11-F1** Aviso de "update needed" que queda viejo.
- [ ] **R9-F2** Batería en -1.
- [ ] **R8-F5** y **R8-F6**

**Where to start:** `HARDWARE_RUNBOOK.md` y `docs/GUIA_PRUEBAS_RELOJ_REAL.md`.

**Depends on:** Hardware (Galaxy Watch 8 + teléfono Samsung A52).

> **Ojo antes de arrancar las DV.** `FIELD_TEST_NOTES.md` documenta que el pipeline reloj-OSD
> estaba bloqueado por **DEC-050**: el Wear Data Layer solo entrega mensajes entre apps que
> comparten `applicationId` **y** certificado de firma, y SeizureGuard (`com.seizureguard.wear`,
> debug key) y OSD (`uk.org.openseizuredetector`, release key de Graham) no comparten ninguno de
> los dos. Las DV que dependen de la entrega de mensajes no van a pasar hasta que ese fix esté
> aplicado. Verificar su estado primero.
