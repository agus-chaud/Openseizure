# Registro de revisiones y hallazgos de seguridad — `watch-osd-message-delivery`

Este documento sirve para dos cosas: **saber qué revisiones se hicieron y cuáles faltan**, y
**comprobar más adelante si cada hallazgo quedó arreglado**. Se actualiza cada vez que se cierra
un hallazgo o se hace una revisión nueva. Contexto de la arquitectura: DEC-050, DEC-051 en
`DECISIONS.md`. Detalle completo del veredicto en engram
`sdd/watch-osd-message-delivery/safety-review-pre-batch7` (obs #1388).

**Última actualización:** 2026-09-19 (después del primer `safety-reviewer`, antes del Batch 7).

> **Actualización 2026-10-01:** este registro es histórico; las entradas pasadas no se reescriben. Estado
> actual en `main`: los Batches 7 (#29 a #32), 7d (#33, #34) y 8 (#35 a #41) **están mergeados**, y el CI
> corre los tests y el lint de `:wear` y `:phone` (#36). Donde más abajo diga "sin mergear",
> "todavía no" o "antes del Batch 7", leelo como el estado de esa fecha. Sigue **pendiente** la
> verificación en hardware real (DV-1..DV-7, R12).

**Leyenda de estado:** `ABIERTO` (sin arreglar) · `EN CURSO` (hay PR abierto) · `ARREGLADO`
(mergeado, falta confirmar) · `VERIFICADO` (confirmado con test o con el reloj) · `ACEPTADO`
(riesgo aceptado a propósito, con firma).

---

## 1. Registro de revisiones

| # | Revisión | Cuándo | Alcance | Resultado | Estado |
|---|---|---|---|---|---|
| R1 | CI de GitHub (tests + lint de `:wear`) | Cada PR a `main` | Solo módulo `:wear`; **no corre tests de `:phone`** ni PRs apilados | Verde en PRs #12–#21 | Hecha, con hueco de cobertura |
| R2 | Tests JVM locales de `:phone` (Robolectric) | Cada batch 3a–6 | Lógica de `:phone` (77 tests al cerrar Batch 6) | 77/77 | Hecha |
| R3 | Revisión manual del diff (asistente) | PRs #12 y #16 | Diff completo | Sin bloqueantes; 2 observaciones resueltas en 5a | Hecha (informal) |
| R4 | Contraste del contrato HTTP contra el código real de OSD (`beta`) | Batches 3a, 5a, 5b | `SdWebServer`, `SdDataSource`, `SdData` | Coincide con el diseño | Hecha |
| R5 | **`safety-reviewer` pre-Batch 7** | 2026-09-19 | Plan del Batch 7 + ruta de alarma ya mergeada (`:phone`) | **BLOCK** (8 hallazgos, ver sección 2) | Hecha |
| R6 | `safety-reviewer` **de re-chequeo** | 2026-09-28 | Verificó el código real mergeado (`origin/main`) contra lo que afirma este registro, para F1-F8 y las guías | **PARTIAL PASS.** F1/F4/F5/F6 confirmados arreglados en código, exacto a lo documentado. F2/F3/F7/F8 confirmados sin implementar (correcto, es lo esperado). Confirmó que Batch 7 no filtró ningún cambio a `:wear`. Encontró 3 hallazgos de desincronización documental (ver abajo), ninguno de código: H-1 (la copia local de `tasks.md` estaba congelada desde antes del Batch 1 y no tenía T7.5-T7.7 — corregido, sincronizada con `origin/main`), H-2 (`CAREGIVER_GUIDE.md` afirmaba silencio total en el reloj, pero el reloj real todavía vibra 2 pulsos en DEGRADED hasta el Batch 7 — corregido), H-3 (`CLINICAL_SIGNOFF.md` y `HARDWARE_RUNBOOK.md` §4.8 decían "pendiente" sobre el Batch 5e ya mergeado — corregido) | **PARCIAL → RESUELTO el mismo día** (ver sección 9) |
| R7 | `safety-reviewer` sobre el **diff real** del Batch 7 (+ re-chequeo R7b de los arreglos) | 2026-09-30 | Ramas `07a`, `07b-1`, `07b-2`, `07c` código real | R7: **PARTIAL PASS** (F2, F3, F7 cerrados; 2 hallazgos ALTOS: H7-1 pantalla tranquilizadora con enlace caído, H7-2 script de instalación con ruta vieja de APK). R7b tras los arreglos: **PASS condicional** (ver sección 10) | **HECHA** — condiciones: mergear `07b-2` y `07c` seguidos, docs actualizados |
| R8 | RDD / revisión sobre el Batch 5c | — | Corrección de F1 en `:phone` | — | **PENDIENTE** (opcional, recomendado: es ruta de alarma) |
| R9 | `sdd-verify` (validar implementación contra spec/tareas) | — | Todo el cambio | — | **PENDIENTE** (al terminar Batch 9) |
| R10 | Firma humana de constantes de detección | 2026-09-19 | Sección 3 de este documento | **16 de 16 firmadas**: 15 tal cual se propusieron (2026-09-19) y `techo_latencia_clinica` = 40 s (2026-09-30, DEC-062) | **HECHA** |
| R11 | Revisión de documentación de cuidadores | 2026-09-19 | `CAREGIVER_GUIDE.md`, `HARDWARE_RUNBOOK.md` §4 reescritos; `CLINICAL_SIGNOFF.md` con constantes firmadas | Reescritas por un asistente y revisadas por otro (textos citados verificados contra la app). Texto médico aprobado por el usuario el 2026-09-30 | **HECHA** |
| R12 | Pruebas en hardware real (DV-1..DV-7) | — | Reloj + teléfono + OSD | — | **PENDIENTE** (sin acceso al reloj; GATE-0 asumido como PASS, DEC-052) |
| R13 | `safety-reviewer` de la **documentación** (Batch 9a) | 2026-10-01 | Docs llevados a `main`: README, guías, runbook, registros | **PARTIAL PASS → hallazgos corregidos** (lista de pasos faltantes en la guía del cuidador, datos de privacidad, textos desactualizados) | **HECHA** |

**Nota honesta sobre lo que no se hizo:** los Batches 1–6 se mergearon con CI + tests locales +
revisión informal. No hubo `safety-reviewer` por batch ni RDD sobre esos diffs. La ruta de alarma
completa del teléfono (relay, servicio, notificaciones, arranque) se revisó **por primera vez de
forma adversarial en R5**, una vez que ya estaba mergeada. Por eso F1–F6 aparecen en código ya en
`main`.

---

## 2. Hallazgos de R5 (`safety-reviewer`, 2026-09-19)

Veredicto: **BLOCK** para arrancar el Batch 7 tal como estaba planeado.

| ID | Severidad | Hallazgo (en simple) | Evidencia | Arreglo previsto | Estado | Cómo verificar que quedó arreglado |
|---|---|---|---|---|---|---|
| **F1** | CRÍTICO | El celular reenvía el `alarmState` de OSD sin mirar si el puente está sano ni si OSD sigue analizando. Si OSD queda en otra fuente de datos o se congela, el reloj sigue recibiendo `0` cada 10 s y nunca avisa. | `AlarmStateRelay.kt` (relay depende solo del cuerpo de `GET /data`); `OsdBridgeService.kt` | **Batch 5c** (`:phone`): estados ≥1 se relayan siempre; el `0` y el refresco solo si `BridgeFault==NONE` y el timestamp de OSD avanza (`OSD_DATA_FRESH_MS`). Nuevo fault `OSD_DATA_STALE`. | **ARREGLADO en código (falta verificar en hardware):** PRs [#23](https://github.com/agus-chaud/Openseizure/pull/23) (relay + frescura) y [#22](https://github.com/agus-chaud/Openseizure/pull/22) (fault `OSD_DATA_STALE`) mergeados a `main` con CI verde. 93 tests `:phone` verdes. Frescura = `dataTimeStr` de OSD (`SdData.java:449-470`). **Sin verificar de punta a punta:** con fuente equivocada, `GET /data` devuelve otra fuente que puede avanzar su propio timestamp; ahí solo cubre el fault `OSD_WRONG_DATASOURCE` (latched). | Tests con "estado congelado" (mismo cuerpo repetido → se calla) + DV-4 en hardware: poner OSD en fuente "Phone" y confirmar DEGRADED en el reloj dentro del techo firmado |
| **F2** | CRÍTICO (verificado) | OSD emite estados 3–7 (FALL, FAULT, MANUAL, MUTE, NETFAULT) y el reloj hace `else -> vibrateAlarm()` para todo ≥2. Batería baja del teléfono o "mute" en OSD vibran como convulsión cada 10 s. | `AlarmStateManager.kt` (`handleAlarmState`); `AlarmState.java` de OSD líneas 9–16 | **Batch 7, T7.5**: política de mapeo aprobada por vos (propuesta: 2, 3 y 5 → alarma; 4 y 7 → falla del sistema/DEGRADED; 6 → sin vibración) + enmienda de spec WCT-8 | **ARREGLADO en código (Batch 7, T7.5, commit `9c77b4b`; Actualización 2026-10-01: mergeado en main, #31):** 2, 3, 5 alarma; 4, 7 y cualquier otro valor falla silenciosa; 6 sin vibración. Tests 0..8, 99, 255, -1 y extremos de `Int`. Verificado por R7. Falta prueba en reloj | Test por cada estado 0..7 en `AlarmStateManager`; prueba en reloj con OSD en FAULT/MUTE |
| **F3** | ALTO | El aviso DEGRADED del reloj es una sola vibración de 2 pulsos. Una persona dormida puede no despertar. | `SeizureMonitorService.kt` ~683–686 (`vibrateDegraded()` solo en la transición) | **Batch 7, T7.6**: repetir la vibración mientras siga DEGRADED, y criterio en T7.4 | **REEMPLAZADO por DEC-057 y ARREGLADO en código (T7.6, commit `6c5f80b`; Actualización 2026-10-01: mergeado en main, #31 y #32):** DEGRADED sin vibración; la pantalla del reloj muestra "⚠ MONITOREO DEGRADADO" (H7-1, commit `5fc1d8d`). Riesgo aceptado (ver sección 8) | Ya no aplica la vibración; verificar que DEGRADED se vea en pantalla y no vibre |
| **F4** | ALTO | La falla del puente solo llega al cuidador si las notificaciones funcionan (`POST_NOTIFICATIONS` denegado, canal bloqueado o No Molestar → invisible). `BootReceiver`/`postStartFailure` publican una sola vez. | `BridgeNotifications.kt` (`notifySafely` traga todo); `BootReceiver.kt` | Implementación: chequear `areNotificationsEnabled()` en cada tick y que `SetupActivity` no dé "listo" si falla; garantizar que la falla se vea en el reloj (F1 + F3) | **REEMPLAZADO por DEC-057:** las fallas son silenciosas a propósito, así que ya no se busca que lleguen a una persona. Queda vigente el registro de fallas y el resumen matutino (Batch 5d) | Test de que el registro y el resumen existen aunque las notificaciones estén bloqueadas |
| **F5** | MEDIO | Si el listener de `MessageClient` muere con el proceso vivo, solo se loguea; sin reintento ni fault. | `OsdBridgeService.kt:~160` (`addOnFailureListener` solo loguea) | Reintento de `addListener` con fault visible | **ARREGLADO en código, PR [#28](https://github.com/agus-chaud/Openseizure/pull/28) mergeado (CI verde).** Reintento con backoff 5 s a 60 s y re-registro si pasan 60 s sin mensajes válidos del reloj (máximo una vez por minuto). Sin fault nuevo: lo cubre el `NO_WATCH_DATA` silencioso. Sin verificar si Play Services descarta listeners tras una actualización ni si el reemplazo pierde mensajes en vuelo | DV-7: matar el listener y medir el tiempo a la falla visible y a la recuperación |
| **F6** | MEDIO | `sample_freq` acepta 1..200 (un valor ≠25 desescala el análisis de OSD); acelerómetro plano/congelado se acepta; handshake con batería 100 oculta batería baja real del reloj. | `WatchMessageParser.kt` (~38); `DEFAULT_HANDSHAKE_BATTERY` | Fijar `sample_freq == 25`; descartar/marcar chunks planos; revisar el default de batería | **ARREGLADO en código, PR [#27](https://github.com/agus-chaud/Openseizure/pull/27) mergeado (CI verde).** `sample_freq` exactamente 25; chunk con las 125 muestras idénticas rechazado (regla nueva, sin epsilon, **no está en la tabla de constantes firmadas**; riesgo sin verificar: un sensor muy cuantizado podría dar 125 valores iguales). Batería por defecto 100 firmada | Tests del parser (124 en verde); DV con el reloj real para confirmar que un reloj quieto no genera chunks idénticos |
| **F7** | MEDIO | El flavor `osdDirect` probablemente no funciona (el AppKey depende también del certificado); riesgo de instalar el flavor equivocado; cambia nombres de tareas de test. | Plan T7.1 | Sacar `osdDirect` de `release` (o solo debug con banner); documentar que release = `companion` | **ARREGLADO en código (T7.1, commit `a729ae2`; Actualización 2026-10-01: mergeado en main, #29):** la variante `osdDirectRelease` está deshabilitada (`beforeVariants`). Seguimiento (banner en `osdDirectDebug`): Actualización 2026-10-01: ya existe el cartel "VERSIÓN DE PRUEBA" (#33/#34) | Revisión del diff de T7.1 (RDD R7) |
| **F8** | BAJO / hardware | `connectedDevice` desde `BOOT_COMPLETED`, Doze y sueño de 8 h; force-stop deja el puente muerto sin aviso; exención de batería denegada sin fault. | `BootReceiver.kt`, `OsdBridgeService.kt` | Recordatorio de exención de batería; resto es hardware | ABIERTO | DV-2, DV-6 |

### Hallazgos de proceso (no son bugs de código)

| ID | Hallazgo | Estado |
|---|---|---|
| P1 | `tasks.md` T7.3 decía "no re-raise" sobre la aprobación de constantes; contradice la regla del skill (la firma humana no se reemplaza). | Se corrige en el Batch 5c (edición de `tasks.md`) |
| P2 | La cuenta de "falla visible ≤ 60 s" no tiene margen y `design.md` usa 30 s de stale en el camino de entrada donde la constante es 40 s. | **CERRADO (T7.7, commit `aa350ea`):** se aceptan los techos firmados (60 s enlace, ≈75 s OSD congelado), medidos desde el último evento bueno. Tests sobre todas las fases del tick para 60 s y 30 s; el de 75 s es solo aritmético (seguimiento BAJO) |
| P3 | El CI solo corre tests de `:wear` y no corre en PRs apilados. | ABIERTO (agregar `:phone:testDebugUnitTest` al workflow; decisión pendiente). Actualización 2026-10-01: **CERRADO**, el CI corre los tests y el lint de `:phone` y `:wear` en cada PR (#36) |

---

## 3. Constantes de detección que exigen firma humana

Ninguna está firmada todavía. La aprobación previa (engram #1196) **no reemplaza** esta firma.
Cuando firmes, anotalo en `CLINICAL_SIGNOFF.md` y actualizá la columna "Estado".

| Constante | Valor propuesto | Dónde vive | Recomendación del revisor | Estado |
|---|---|---|---|---|
| `techo_latencia_clinica` | 40 s | `CLINICAL_SIGNOFF.md` | Firmar primero: de él dependen el stride y el techo de 8 s del relay | **Firmada 2026-09-30** (5 s + 8 s caben; ≈ 27 s restantes para el modelo, sin medir) |
| Techo "falla visible" (ventana ciega máxima) | 60 s | spec / `design.md` | Escalar: es un techo de latencia. Aceptar ~65–70 s firmado, o bajar `STALE` a 30 s | **Firmada 2026-09-19** |
| `WATCHDOG_INTERVAL_MS` | 30 s → 10 s | `SeizureMonitorService.kt` (Batch 7) | Razonable | **Firmada 2026-09-19** |
| `DELIVERY_STALE_MS` | 60 s → 40 s | ídem | Firmar | **Firmada 2026-09-19** |
| `ALARM_STATE_STALE_MS` | 40 s (nuevo) | ídem | Firmar solo junto con el arreglo de F1 | **Firmada 2026-09-19** |
| Histéresis | 2 ticks | ídem | Firmar (multiplica el techo) | **Firmada 2026-09-19** |
| Warm-up | 60 s | ídem | Firmar (retrasa hasta ~80 s el primer DEGRADED) | **Firmada 2026-09-19** |
| Keep-alive del relay | 10 s (efectivo 10–15 s) | `AlarmStateRelay.kt` | Firmar (40 s tolera 3 pérdidas) | **Firmada 2026-09-19** |
| `NO_WATCH_DATA_MS` / `POST_OK_STALE_MS` | 30 s / 20 s | `BridgeHealth.kt` (ya mergeado) | Firmar | **Firmada 2026-09-19** |
| `OSD_DATA_FRESH_MS` | 15 s (propuesto en 5c) | `:phone` (Batch 5c) | Firmar | **Firmada 2026-09-19** |
| Rango de `sample_freq` aceptado | 1..200 | `WatchMessageParser.kt` | Endurecer a `== 25` (F6) | **Firmada (25) 2026-09-19; falta endurecer el código** |

---

## 4. Condiciones para pasar de BLOCK a PASS (checklist)

- [x] **1.** F1 arreglado y con tests de estado congelado (Batch 5c, PRs #22/#23 mergeados). Falta DV-4 en hardware.
- [x] **2.** Política para los estados 3–7: definida (DEC-057), spec enmendado e **implementada** (T7.5, verificada por R7). Falta prueba en reloj.
- [x] **3.** ~~DEGRADED persistente~~ reemplazado por DEGRADED solo visual (DEC-057), implementado (T7.6) y visible en pantalla (H7-1).
- [x] **4.** Constantes firmadas (16 de 16: 15 el 2026-09-19, `techo_latencia_clinica` = 40 s el 2026-09-30) con las cuentas de peor caso: 60 s enlace, ≈75 s OSD congelado (P2 aceptado con firma). T7.7 cerrada aceptando los techos firmados.
- [~] **5.** `CAREGIVER_GUIDE.md` y `HARDWARE_RUNBOOK.md` §4 **reescritos** (2026-09-19). Texto médico revisado y aprobado por el usuario (2026-09-30: "llamar a emergencias si dura más de 3 minutos" es correcto). Falta que se corrijan `docs/GUIA_CONECTAR_RELOJ_TELEFONO.md` y `docs/RUNBOOK_EXPORTACION_DATOS.md`, que aún mandan por el camino roto. Actualización 2026-10-01: ambas guías ya describen el flujo de dos apps (reloj + Companion + OSD con fuente "Garmin"); queda solo la prueba en hardware.
- [x] **6.** `osdDirect` fuera de `release` (F7): variante deshabilitada en T7.1.
- [x] **7.** Segundo `safety-reviewer` (R6, 2026-09-28): **PARCIAL PASS**, con 3 hallazgos documentales
  ya corregidos el mismo día (ver sección 9). Ningún hallazgo de código. No hace falta un tercer
  re-chequeo salvo que cambie algo más antes de Batch 7.

**Riesgo residual aun cumpliendo todo:** sensor congelado sin variación; si puente y reloj fallan a
la vez solo queda la vibración del reloj; depende de que la persona duerma con el reloj puesto y
cargado.

---

## 5. Documentación de cuidadores desactualizada (R11)

| Documento | Qué está mal | Estado |
|---|---|---|
| `CAREGIVER_GUIDE.md` (líneas ~39–46, 95–97) | Pide "la app del teléfono abierta y dice que recibe datos": el puente no tiene pantalla de estado. No describe la arquitectura de dos apps ni el DEGRADED (2 pulsos). "Deja de proteger si…" omite OSD cerrado/fuente incorrecta, puente detenido, reinicio del teléfono, notificaciones bloqueadas. No dice qué hacer ante una notificación de falla. | **REESCRITA 2026-09-19** (banner de no verificado, política silenciosa, checklist nocturno, resumen matutino). Texto médico aprobado por el usuario 2026-09-30 |
| `HARDWARE_RUNBOOK.md` §4 (líneas ~137–212) | Describe habilitar la fuente Android Wear, el camino roto por DEC-050. | **REESCRITA 2026-09-19** (flujo de dos apps, tabla DV-1..DV-7). La sección 5 solo recibió una nota |
| `CLINICAL_SIGNOFF.md` | Falta registrar las constantes de la sección 3 y la política de F2. | **PARCIAL:** 16 de 16 constantes firmadas (2026-09-30); falta registrar la política de F2 |
| `DISCLAIMER.md` | Faltaba mencionar las dos apps del teléfono. | **ACTUALIZADO** (menciona las tres piezas y que las fallas no suenan ni vibran) |

---

## 6. Pruebas en hardware pendientes (DV)

| ID | Qué confirma | Relacionado con | Estado |
|---|---|---|---|
| DV-1 | `startForeground(connectedDevice)` con `BLUETOOTH_CONNECT` denegado: falla ruidosa, sin crash | F4, Batch 5a | Pendiente |
| DV-2 | `connectedDevice` permitido desde `BOOT_COMPLETED`; camino de degradación | F8, Batch 6 | Pendiente |
| DV-3 | `mDataFrequencyCheckEnabled` en OSD (tolerancia de jitter) | Batch 4/5 | **Hecha 2026-10-07**: viene activado por defecto; el reloj superaba los 25 Hz (§16), arreglado por DEC-068 y verificado en el reloj real (204/204 paquetes a 5 s) |
| DV-4 | Estado congelado: OSD en fuente "Phone" o `processData` roto → DEGRADED en el reloj dentro del techo firmado | **F1** | Pendiente (caso concreto ahora definido) |
| DV-5 | Costo de batería del teléfono con el refresco de 10 s | Batch 7/8 | Puede esperar |
| DV-6 | Una noche completa de 8 h | Todo | Pendiente |
| **DV-7 (nuevo)** | Inyección de fallas: matar listener, matar OSD, force-stop del puente, No Molestar, notificaciones denegadas; medir tiempo a la falla visible | F4, F5, F8 | Pendiente |
| **DV-8** | Latencia clínica: simular una convulsión y medir (con cronómetro, mínimo 5 corridas) el tiempo hasta la vibración fuerte del reloj, la alarma de OSD y el SMS; debe ser ≤ 40 s (`techo_latencia_clinica`, `CLINICAL_SIGNOFF.md`). Incluye, como sub-medición, el techo de 8 s del relay | `techo_latencia_clinica` | Dividida en **DV-8a** (relay: hecha 2026-10-06, WARNING llegó al reloj en 1.7 s) y **DV-8b** (techo de 40 s: pendiente; no se verifica sacudiendo a mano) |
| **DV-9** | Lo que ve el cuidador: "⚠ MONITOREO DEGRADADO" a ~80 s, "SILENCIADO, no avisa convulsiones" con MUTE, "SeizureGuard: update needed" (opcional), reinicio del teléfono con el puente (ver DV-2) y resumen matutino a ~8:00 | A1, DEC-057, Batch 8 | Pendiente |

Pasos simples para hacer todas las DV: `docs/GUIA_PRUEBAS_RELOJ_REAL.md`.

---

## 7. Cómo usar este documento en la próxima revisión

1. Para cada fila de la sección 2, mirar la columna "Cómo verificar" y anotar el resultado.
2. Cambiar el estado (`ARREGLADO` → `VERIFICADO` solo con test verde o prueba en el reloj).
3. Marcar el checklist de la sección 4; cuando esté completo, correr R6.
4. Registrar cualquier revisión nueva como fila en la sección 1, aunque no encuentre nada.

---

## 9. Segundo `safety-reviewer` (R6, 2026-09-28) — verificación contra código real

A diferencia de R5 (que revisó un plan), R6 leyó el código ya mergeado en `origin/main` y lo comparó
línea por línea contra lo que este registro afirma. Metodología y hallazgo completos en engram
`sdd/watch-osd-message-delivery/safety-review-r6`.

**Resultado principal: ningún hallazgo de F1-F8 estaba mal documentado.** F1 (`AlarmStateRelay.kt` +
`OsdDataFreshness.kt`), F4 (`FaultLog.kt` + `ServiceLiveness.kt` + `MorningSummary*.kt`), F5
(`BridgeHealth.kt`/`OsdBridgeService.kt`, reintento y re-registro del listener) y F6
(`WatchMessageParser.kt`, `sample_freq==25` y chunk congelado) están exactamente como se documentaron.
F2, F3, F7 y F8 siguen correctamente sin implementar, y se confirmó con `git diff --stat` que Batch 7
no dejó ningún cambio filtrado en `wear/` (las constantes y el mapeo de estados viejos siguen intactos
— es lo esperado, no una regresión).

**Tres hallazgos de desincronización DOCUMENTAL, ya corregidos el mismo día:**
- **H-1:** la copia local (sin trackear) de `openspec/changes/watch-osd-message-delivery/tasks.md` en
  la copia de trabajo había quedado congelada desde antes del Batch 1 — le faltaban T7.5,
  T7.6 y T7.7 (la enmienda de DEC-057). El archivo tracked en `origin/main` sí los tenía. Riesgo real:
  cualquier agente que trabajara "por path absoluto en el árbol principal" (instrucción usada varias
  veces en este SDD) vería el plan viejo. **Corregido:** se sobrescribió la copia local con el
  contenido de `origin/main`.
- **H-2:** `CAREGIVER_GUIDE.md` afirmaba sin matices que una falla "no suena ni vibra nada, ni en el
  teléfono ni en el reloj". Es cierto para el teléfono, pero el código actual del reloj
  (`SeizureMonitorService.kt`) todavía llama `vibrateDegraded()` (2 pulsos) al entrar en DEGRADED,
  porque el Batch 7 no se hizo. Es un documento de seguridad de vida que se asume vigente ya, no "a
  futuro". **Corregido:** se agregó una excepción explícita con la vibración real de hoy.
- **H-3:** `CLINICAL_SIGNOFF.md` y `HARDWARE_RUNBOOK.md` §4.8 seguían diciendo "pendiente" sobre
  `sample_freq`, el chunk congelado y el reintento del listener, que ya están mergeados desde el
  Batch 5e. No es un riesgo de seguridad (subestima protecciones ya existentes), pero podía hacer que
  se repitieran pruebas ya resueltas. **Corregido.**

**Condiciones que quedan para el Batch 7, sin cambios respecto al checklist de la sección 4:** firmar
`techo_latencia_clinica`, revisión humana del texto médico de `CAREGIVER_GUIDE.md`, y que `:phone`
tenga tiempo de funcionar sin sobresaltos antes de tocar el reloj (opcional, a tu criterio).

---

## 8. Riesgos aceptados por decisión del usuario (DEC-057, 2026-09-19)

No son hallazgos abiertos: son riesgos que se aceptaron sabiéndolos. Se listan para que no se
pierdan y para revisarlos en el segundo `safety-reviewer` (R6).

| ID | Riesgo aceptado | Mitigación prevista | Quién lo aceptó |
|---|---|---|---|
| A1 | Un sistema caído se ve igual que una noche tranquila: ninguna falla avisa a nadie. | Indicador pasivo, registro de períodos de falla y resumen silencioso a la mañana. **Implementado en Batch 5d (#24–#26), sin probar en dispositivo.** No es una red confiable: tras un cierre forzado de la app no llega ningún resumen | Usuario. Se le advirtió y se recomendó la opción B (avisar solo si la falla dura > N min) |
| A2 | OSD fuerza FAULT por encima de ALARM (`SdServer.java:1274-1281`): con una falla activa una convulsión no genera alarma. | Proceso: dejar el teléfono de OSD cargando de noche; documentarlo | Usuario |
| A3 | MUTE (botón del reloj o de la app) tapa las alarmas y cancela el SMS mientras esté activo. No se verificó si OSD limita su duración. | Documentar; evaluar un recordatorio | Usuario |
| A4 | OSD suena por sus propias fallas (`AudibleFaultWarning`, activa por defecto): si no se apaga, la política silenciosa no se cumple. | Apagarla en OSD; que figure en `SetupActivity` y `CAREGIVER_GUIDE.md` | `SetupActivity` ya lo indica (etiqueta "Enable Audible System FaultWarnings", `strings.xml:220` de OSD; falta confirmarla en tu versión instalada). **Falta `CAREGIVER_GUIDE.md` y que vos la apagues en OSD** |

### Consecuencias del Batch 5d que conviene tener presentes

- Los avisos de **DV-1** (Bluetooth denegado) y **DV-2** (falla al reanudar tras reiniciar) ahora
  son **silenciosos**: nadie se entera por notificación de que el puente no arrancó.
- Sin probar en dispositivo: Doze con `setAndAllowWhileIdle`, si One UI deja asomar o sonar una
  notificación de importancia baja (si pasa, subir el silencio a nivel mínimo) y la entrega del
  receptor tras un cierre forzado (no llega; el arranque solo re-arma tras reiniciar).
- Un Stop del usuario no genera resumen y no se distingue de olvidarse de iniciar el puente.
- Un servicio que muere y reinicia en menos de 120 s no se registra como caída (falso negativo).
- Si el usuario ya había personalizado el canal viejo, esa configuración no se conserva.
- Valores nuevos a incluir en la firma de constantes: `SERVICE_DOWN_THRESHOLD_MS` 120 s,
  `ALIVE_WRITE_INTERVAL_MS` 60 s, resumen a las 8:00 sobre una ventana de 12 h, retención de 14 días.

### Alarma en el celular del cuidador (celular distinto del de OSD)

- La alarma de OSD suena por `USAGE_ALARM` y atraviesa el silencio del timbre (depende del volumen
  de alarma y de la excepción de alarmas de No Molestar): `SdServer.java:951-1000`.
- El SMS a otro celular no se controla desde el código.
- Vía candidata: OSD en el celular del cuidador con fuente "Network" (`SdDataSourceNetwork.java:32,309`).
  **Sin verificar de punta a punta:** misma red local, corte del enlace (NETFAULT), y que el
  servidor web de OSD queda expuesto sin autenticación en la LAN.
- Alternativa sin código: No Molestar con el contacto del paciente como prioritario.
- Estado: **PENDIENTE de decidir y probar** (DV nuevo).

---

## 10. `safety-reviewer` sobre el diff real del Batch 7 (R7 y R7b, 2026-09-30)

**Qué se revisó:** el código real del Batch 7 en 4 ramas apiladas, sin mergear: `07a` (flavors +
destino del reloj + script de instalación), `07b-1` (vigilancia del enlace y constantes firmadas),
`07b-2` (política de estados de OSD, DEGRADED sin vibración, techos), `07c` (pantalla del reloj).

**Confirmado:** los estados 2, 3 y 5 siempre vibran como alarma en todos los caminos (sin falsos
negativos introducidos). Ninguna falla vibra: 4, 7, 6 y cualquier valor desconocido quedan en
silencio. La ruta de vibración (`AlarmStateManager.kt`) no cambia en `07c`. Las 6 constantes del
reloj coinciden con las firmadas (DEC-059). Si nunca llega un mensaje del teléfono, el reloj pasa
a DEGRADED en ≈80 s como máximo.

| ID | Severidad | Hallazgo | Estado |
|---|---|---|---|
| H7-1 | ALTO | Con el enlace caído la pantalla seguía diciendo "Monitoreo activo" (o una "ALARMA" vieja): el estado recibido no vence y la pantalla no leía DEGRADED. | **ARREGLADO** (commit `5fc1d8d`, rama `07c`): "⚠ MONITOREO DEGRADADO" en ámbar; un estado vencido nunca se muestra como vigente; una alarma vigente nunca se oculta. Tests de todas las combinaciones |
| H7-2 | ALTO | El script de instalación apuntaba a la ruta vieja del APK: podía instalar un APK anterior que vibra ante fallas. | **ARREGLADO** (commit `4423358`, rama `07a`): compila el flavor `companion`, borra APKs viejos y falla si el APK falta o es anterior a la compilación. `HARDWARE_RUNBOOK.md` corregido |
| H7-3 | BAJO-MEDIO | Al reiniciar o detener el monitoreo, la pantalla conserva el último estado de la sesión anterior (una "ALARMA" vieja puede quedar en pantalla con el monitoreo apagado). No causa alarmas perdidas. | **ARREGLADO** (commit `86c9fb2`, rama `07c`): el estado mostrado vuelve a OK al iniciar y al detener, con test |
| H7-4 | BAJO | Carrera entre el tick y el listener: tras un corte, una ALARMA nueva puede verse como DEGRADED hasta 10 s. La vibración no se afecta. | **ARREGLADO** (7d-1, `aeb0da4`): frescura publicada como snapshot atómico y calculada al usarla |
| H7-5 | MEDIO | La vigilancia usa el reloj de pared: un salto hacia atrás del reloj la ciega por esa duración. | **ARREGLADO** (7d-1, `9749ccc`): toda la vigilancia usa `elapsedRealtime` |
| H7-6 | MEDIO | MUTE (6) se ve como "Monitoreo activo" en el reloj. | **ARREGLADO** (7d-2, `c92be33` + `cf400a8`): la pantalla dice "SILENCIADO, no avisa convulsiones" (celeste) |
| H7-7 | BAJO | Etiqueta de falla de OSD con poco contraste; test de 75 s solo aritmético; `getInt` puede desbordar un valor grande; `osdDirectDebug` sin banner. | **ARREGLADOS** (7d-2, `cc25593`): violeta claro ≈ 8.8:1, simulación real de 75 s con control negativo, números fuera de rango → falla silenciosa, cartel "VERSIÓN DE PRUEBA" |

**PRs abiertos (2026-09-30):** `07a` [#29](https://github.com/agus-chaud/Openseizure/pull/29), `07b-1` [#30](https://github.com/agus-chaud/Openseizure/pull/30), `07b-2` [#31](https://github.com/agus-chaud/Openseizure/pull/31), `07c` [#32](https://github.com/agus-chaud/Openseizure/pull/32). Sin mergear.

**Condición de merge:** `07b-2` saca la vibración de DEGRADED y `07c` agrega la etiqueta en
pantalla. Hay que mergearlos **seguidos, en la misma sesión**, sin instalar en el reloj un build de
`main` entre medio. `07a` y `07b-1` son seguros solos.

**Riesgo residual:** DEGRADED solo se ve con la app abierta en el reloj o en sus notificaciones; con
la carátula no hay aviso (A1). Nada se probó en hardware (R12).

---

## 11. `safety-reviewer` R8 sobre las mejoras H7-4 a H7-7 (2026-09-30)

**Qué se revisó:** el código real de dos ramas apiladas: `07d-1` (reloj interno monotónico y
frescura atómica) y `07d-2` (carteles, colores, lectura de números, test de 75 s).

**Veredicto:** `07d-1` **PASS** (se puede mergear solo). `07d-2` **PARTIAL PASS → resuelto**: la
condición era cambiar el texto de MUTE ("SILENCIADO — sin alarmas" se podía leer como "todo
tranquilo"); el usuario eligió **"SILENCIADO, no avisa convulsiones"** (commit `cf400a8`) y la guía
del cuidador se actualizó.

**Confirmado:** ninguna alarma real se descarta: el celular manda `alarm_state` como entero y `2`,
`"2"`, `2.0` y `" 2 "` siguen vibrando. `classify`/`handleAlarmState` sin cambios. Constantes y
techos firmados sin cambios.

| ID | Severidad | Hallazgo | Estado |
|---|---|---|---|
| R8-F1 | MEDIO | Texto de MUTE ambiguo. | **ARREGLADO** (`cf400a8`) |
| R8-F2 | BAJO | Rojo de ALARMA ≈ 3.2:1 sobre negro. | **ARREGLADO** (`cf400a8`): `0xFFFF4D6A` ≈ 6.5:1, contraste testeado para los 5 colores |
| R8-F3 | BAJO | Si falla el vibrador, la pantalla quedaba con el estado anterior como vigente. | **ARREGLADO** (`cf400a8`): la pantalla se actualiza antes de vibrar, con test |
| R8-F4 | BAJO | Un número inválido cuenta como señal de vida (muestra la falla violeta). | Aceptado |
| R8-F5 | BAJO | El test de 75 s tiene margen cero y copia a mano constantes de `:phone`. | Seguimiento |
| R8-F6 | INFO | En `:phone`: `2.5` de OSD se trunca a 2; un OSD congelado en 1 o 6 nunca vence en el reloj. | Seguimiento (preexistente, `:phone`) |

---

## 12. `safety-reviewer` R9 sobre el Batch 8a (2026-09-30)

**Qué se revisó:** rama `sdd/watch-osd-delivery-08a-wear-contract-version` (`955d7f9`, `63352e8`):
el reloj agrega `"contract_version":1` a `/osd/settings`; el celular lo lee (nulo si falta o es
inválido) y **no** lo reenvía a OSD. Todavía no compara versiones (Batch 8b).

**Veredicto: PASS.** Ningún mensaje de configuración que antes se aceptaba ahora se rechaza; lo que
el celular manda a OSD es idéntico byte a byte; reloj viejo + celular nuevo y reloj nuevo + celular
viejo siguen funcionando. Sin cambios en alarma, vibración, vigilancia ni constantes firmadas.

| ID | Severidad | Hallazgo | Estado |
|---|---|---|---|
| R9-F1 | BAJO | El flavor `osdDirectDebug` manda la clave nueva directo a OSD; se espera que OSD la ignore, sin confirmar en dispositivo. | Seguimiento (solo flavor de prueba) |
| R9-F2 | INFO | Preexistente: si el reloj no puede leer la batería manda -1 y el celular rechaza ese mensaje. | Revisar en 8b |
| R9-F3 | INFO | El test de "no se reenvía a OSD" llama al codec, no a `postSettings`; la garantía viene del código leído. | Seguimiento |

---

## 13. `safety-reviewer` R10 sobre el Batch 8b (2026-10-01)

**Qué se revisó:** `8b-1` (`190ee68`: comparación de versiones `MATCH`/`MISMATCH`/`MISSING`,
`COMPANION_CONTRACT_VERSION = 1`, período `VERSION_MISMATCH` independiente en el registro de fallas,
excluido del resumen matutino) y `8b-2` (`659957c`: el celular evalúa la versión en cada
`/osd/settings` real del reloj, después de encolar el envío a OSD).

**Veredicto:** `8b-1` **PASS** (sin efecto en ejecución, nadie lo llama todavía). `8b-2` **PASS**
condicionado a resolver R10-F3 en la 8c. Nada demora, descarta ni altera el envío a OSD ni el relay
de alarmas; la diferencia de versión no es un `BridgeFault`, así que nunca deja al reloj en DEGRADED.

**Decisiones registradas:** si el reloj no manda versión cuenta como incompatible (solo se anota);
una diferencia de versión **nunca corta** el envío a OSD (cortar garantizaría convulsiones sin
detectar). Ver DEC-065.

| ID | Severidad | Hallazgo | Estado |
|---|---|---|---|
| R10-F2 | BAJO | La anotación corre en el hilo principal y usa el mismo lock que el tick de salud. | Seguimiento opcional (8c) |
| R10-F3 | BAJO hoy / MEDIO para 8c | Al reiniciar el servicio del celular, la anotación de versión se cierra y no se reabre hasta que el reloj reinicie el monitoreo. | **ARREGLADO** (8c-1, `baa31fb`); queda el residual R11-F1 |
| R10-F4 | INFO | La poda del registro no distingue tipos; impacto solo tras ~300 ciclos en 14 días. | Sin acción |
| R10-F5 | INFO | La spec PCB-9 dice "rather than proceeding": dejar escrito que una diferencia se muestra y nunca bloquea. | DEC-065; spec en Batch 9 |
| R10-F6 | INFO | Texto suave para "sin versión" en la 8c; R9-F2 (batería -1) sigue abierto. | 8c |

---

## 14. `safety-reviewer` R11 sobre el Batch 8c (2026-10-01)

**Qué se revisó:** `8c-1` (`baa31fb`: la anotación `VERSION_MISMATCH` sobrevive a reinicios y solo
se cierra con un MATCH real), `8c-2` (`20a1d8c`: notificación silenciosa "SeizureGuard: update
needed", ID 4105, mismo canal silencioso que las fallas) y `8c-3` (`6bf1420`: nota en el resumen de
la mañana, sin contar como corte).

**Veredicto:** `8c-1` **PASS**; `8c-2` y `8c-3` **PARTIAL PASS** con una sola condición: documentar
el aviso y la nota en `CAREGIVER_GUIDE.md` (hecho en el mismo cambio que esta sección). La ruta de
la alarma no se tocó; ningún código nuevo puede tumbar el servicio (todo envuelto en `runCatching`,
después de encolar el envío a OSD).

| ID | Severidad | Hallazgo | Estado |
|---|---|---|---|
| R11-F1 | MEDIO | El aviso puede quedar viejo: si solo se actualiza el teléfono, "update needed" sigue hasta que el reloj reinicie el monitoreo (el reloj manda su versión solo al iniciar). Falso positivo persistente, no se puede deslizar. | Mitigado en la guía (paso 2: reiniciar el monitoreo del reloj). Seguimiento: guardar la última versión del reloj y re-evaluarla al iniciar el servicio |
| R11-F2 | BAJO | `restore()` corre en `onCreate` antes de `startForeground`; barato y envuelto. | Sin acción |
| R11-F3 | INFO | Preexistente: `postStartFailure` en `enterForeground` no está envuelto. | Endurecimiento opcional |
| R11-F4 | BAJO | Con la notificación del resumen cerrada, la nota puede quedar oculta. | Documentado en la guía (expandirla) |
| R11-F7 | INFO | R9-F2 (batería -1 rechazada) podría dejar el aviso viejo si el reloj arreglado sigue mandando -1. | Seguimiento (Batch 9) |

---

## 15. Primera prueba en hardware real y `safety-reviewer` R14 (2026-10-06)

**Qué pasó:** en la primera prueba con el equipo real (Galaxy A52 + Galaxy Watch 8, OSD 5.0.9) el
reloj le mandaba datos al Companion, pero **ningún dato llegaba a OSD**: OSD quedaba en FAULT, sin
ajustes del reloj y con los datos congelados.

| ID | Severidad | Hallazgo | Estado |
|---|---|---|---|
| R14-F1 | ALTO (histórico) | El puente teléfono→OSD nunca funcionó en hardware real antes de este arreglo: Android bloquea HTTP en claro por defecto (targetSdk ≥ 28) y `OsdHttpForwarder` lo escondía como UNREACHABLE. Los tests JVM/Robolectric no aplican esa política, por eso R1–R13 no lo vieron. | **ARREGLADO**: `network_security_config` permite HTTP en claro solo a `127.0.0.1` y `localhost`; el motivo de cada falla ahora se registra con `Log.w`. Verificado en el equipo real (OSD `/data` avanza, `alarmState` 0 "OK"). `NetworkSecurityConfigTest` fija manifest + config. **Lección:** toda ruta de red nueva se prueba en el teléfono real antes de darla por buena |
| R14-F3 | BAJO | Con OSD caído, el registro escribe ~2 líneas cada 5 s. | Seguimiento opcional: registrar solo cuando cambia el motivo |
| R14-F5 | BAJO | Tras reinstalar el Companion, OSD recibe batería de reloj 100 (valor por defecto firmado) hasta que el reloj reinicia el monitoreo. | Documentado en `docs/GUIA_PRUEBAS_RELOJ_REAL.md`; R9-F2 sigue abierto |

**Veredicto R14: PASS.** El cambio no habilita HTTP en claro hacia ningún destino fuera del propio
teléfono y no cambia alarmas, vibración, SMS ni estados.

---

## 16. Cadencia de datos del reloj por encima de 25 Hz (2026-10-07)

**Qué pasó:** en la prueba con el equipo real, OSD mostraba "Data arriving too quickly" y a veces
quedaba en FAULT. Medición (Galaxy Watch 8, 17 min): 208 chunks, 199 de 5.0 s y 9 de menos de 4 s
(mínimo 2.5 s). Cada episodio coincidió con `SecTiltDetectorImpl` de Samsung registrando el
acelerómetro a 50 Hz; Android entrega esa tasa a todos los listeners del sensor compartido.

| ID | Severidad | Hallazgo | Estado |
|---|---|---|---|
| R15-F1 | ALTO | Con el sensor a 50 Hz, los chunks (125 muestras) abarcan 2.5 s. Si el período supera los 30 s, OSD entra en FAULT y descarta los paquetes sin analizarlos (riesgo aceptado A2, pero causado por el reloj). Se vieron dos períodos de ~32 s el 2026-10-06. | **ARREGLADO** (DEC-068): `SampleRateDecimator` conserva solo las muestras de una grilla de 25 Hz por `SensorEvent.timestamp` |
| R15-F2 | ALTO | Durante los 50 Hz, OSD analizaba un espectro con la frecuencia duplicada (asume 25 Hz), sin que ningún aviso lo indicara. Podía enmascarar una convulsión en la banda 3-8 Hz. | **ARREGLADO** (DEC-068): la cadencia vuelve a ser 25 Hz reales |
| R15-F3 | BAJO | Decimar sin filtro anti-aliasing: a 50 Hz de entrada el contenido sobre 12.5 Hz puede plegarse. | Aceptado: la energía del movimiento de muñeca ahí es baja y la banda de OSD es 3-8 Hz |
| R15-F5 | BAJO | Si los timestamps del sensor dejaran de avanzar, el decimador descartaría todo mientras `lastSampleAtMs` sigue fresco (liveness por evento crudo): `SAMPLE_STALE_MS` no lo vería. | Cubierto: sin chunks no hay entregas y `DELIVERY_STALE_MS` (40 s, firmado) pasa el reloj a DEGRADADO |
| R15-F6 | BAJO | Grilla anclada: justo después de una muestra atrasada, dos muestras conservadas pueden quedar a menos de 30 ms. Afecta a lo sumo una muestra de un chunk en una transición de tasa. | Aceptado: efecto espectral despreciable; a cambio, la tasa a largo plazo es exactamente 25 Hz |
| R15-F4 | INFO | `lastSampleAtMs` (liveness, `SAMPLE_STALE_MS` 10 s) se sigue actualizando con cada evento crudo; la decimación no afecta la detección de sensor muerto. | Sin acción |

No cambia ninguna constante firmada (25 Hz, `TRANSPORT_CHUNK_SIZE` 125, `SAMPLE_STALE_MS`), ni
alarmas, vibración, SMS ni estados.

**Veredicto R15: PASS.** Clasificación: `alarm-path` (acondiciona la señal que llega a OSD). No toca ninguna constante de lógica de detección (umbral, N ventanas, ventana 750, techo de latencia) ni cambia alarmas, vibración, SMS ni estados, así que `CAREGIVER_GUIDE.md` sigue vigente. Riesgo residual: falta repetir la medición en el reloj real con este cambio instalado (esperado: todos los chunks a 5.0 s y ningún "Data arriving too quickly").
