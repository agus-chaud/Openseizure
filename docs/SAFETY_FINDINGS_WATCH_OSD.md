# Registro de revisiones y hallazgos de seguridad — `watch-osd-message-delivery`

Este documento sirve para dos cosas: **saber qué revisiones se hicieron y cuáles faltan**, y
**comprobar más adelante si cada hallazgo quedó arreglado**. Se actualiza cada vez que se cierra
un hallazgo o se hace una revisión nueva. Contexto de la arquitectura: DEC-050, DEC-051 en
`DECISIONS.md`. Detalle completo del veredicto en engram
`sdd/watch-osd-message-delivery/safety-review-pre-batch7` (obs #1388).

**Última actualización:** 2026-09-19 (después del primer `safety-reviewer`, antes del Batch 7).

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
| R6 | `safety-reviewer` **de re-chequeo** | — | Verificar que F1–F4 y las condiciones de la sección 4 estén cumplidas | — | **PENDIENTE** (antes de escribir Batch 7) |
| R7 | RDD (`gentle-ai review`) sobre el **diff real** del Batch 7 | — | Diff congelado de `:wear` (watchdog, alarma, flavors) | — | **PENDIENTE** (antes de abrir el PR del Batch 7) |
| R8 | RDD / revisión sobre el Batch 5c | — | Corrección de F1 en `:phone` | — | **PENDIENTE** (opcional, recomendado: es ruta de alarma) |
| R9 | `sdd-verify` (validar implementación contra spec/tareas) | — | Todo el cambio | — | **PENDIENTE** (al terminar Batch 9) |
| R10 | Firma humana de constantes de detección | — | Sección 3 de este documento | — | **PENDIENTE** (la sección "Firmadas" de `CLINICAL_SIGNOFF.md` está vacía) |
| R11 | Revisión de documentación de cuidadores | — | `CAREGIVER_GUIDE.md`, `HARDWARE_RUNBOOK.md`, `CLINICAL_SIGNOFF.md`, `DISCLAIMER.md` | — | **PENDIENTE** (ver sección 5) |
| R12 | Pruebas en hardware real (DV-1..DV-7) | — | Reloj + teléfono + OSD | — | **PENDIENTE** (sin acceso al reloj; GATE-0 asumido como PASS, DEC-052) |

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
| **F2** | CRÍTICO (verificado) | OSD emite estados 3–7 (FALL, FAULT, MANUAL, MUTE, NETFAULT) y el reloj hace `else -> vibrateAlarm()` para todo ≥2. Batería baja del teléfono o "mute" en OSD vibran como convulsión cada 10 s. | `AlarmStateManager.kt` (`handleAlarmState`); `AlarmState.java` de OSD líneas 9–16 | **Batch 7, T7.5**: política de mapeo aprobada por vos (propuesta: 2, 3 y 5 → alarma; 4 y 7 → falla del sistema/DEGRADED; 6 → sin vibración) + enmienda de spec WCT-8 | POLÍTICA DEFINIDA (DEC-057); implementación pendiente en Batch 7 (T7.5): 2, 3, 5 alarma; 4, 7 y desconocidos falla silenciosa; 6 sin vibración | Test por cada estado 0..7 en `AlarmStateManager`; prueba en reloj con OSD en FAULT/MUTE |
| **F3** | ALTO | El aviso DEGRADED del reloj es una sola vibración de 2 pulsos. Una persona dormida puede no despertar. | `SeizureMonitorService.kt` ~683–686 (`vibrateDegraded()` solo en la transición) | **Batch 7, T7.6**: repetir la vibración mientras siga DEGRADED, y criterio en T7.4 | **REEMPLAZADO por DEC-057:** DEGRADED pasa a ser solo visual, sin vibración. T7.6 hay que reescribirla. Riesgo aceptado (ver sección 8) | Ya no aplica la vibración; verificar que DEGRADED se vea en pantalla y no vibre |
| **F4** | ALTO | La falla del puente solo llega al cuidador si las notificaciones funcionan (`POST_NOTIFICATIONS` denegado, canal bloqueado o No Molestar → invisible). `BootReceiver`/`postStartFailure` publican una sola vez. | `BridgeNotifications.kt` (`notifySafely` traga todo); `BootReceiver.kt` | Implementación: chequear `areNotificationsEnabled()` en cada tick y que `SetupActivity` no dé "listo" si falla; garantizar que la falla se vea en el reloj (F1 + F3) | **REEMPLAZADO por DEC-057:** las fallas son silenciosas a propósito, así que ya no se busca que lleguen a una persona. Queda vigente el registro de fallas y el resumen matutino (Batch 5d) | Test de que el registro y el resumen existen aunque las notificaciones estén bloqueadas |
| **F5** | MEDIO | Si el listener de `MessageClient` muere con el proceso vivo, solo se loguea; sin reintento ni fault. | `OsdBridgeService.kt:~160` (`addOnFailureListener` solo loguea) | Reintento de `addListener` con fault visible | ABIERTO | DV-7: matar el listener y medir el tiempo a la falla visible |
| **F6** | MEDIO | `sample_freq` acepta 1..200 (un valor ≠25 desescala el análisis de OSD); acelerómetro plano/congelado se acepta; handshake con batería 100 oculta batería baja real del reloj. | `WatchMessageParser.kt` (~38); `DEFAULT_HANDSHAKE_BATTERY` | Fijar `sample_freq == 25`; descartar/marcar chunks planos; revisar el default de batería | ABIERTO | Tests del parser; decisión tuya sobre batería por defecto |
| **F7** | MEDIO | El flavor `osdDirect` probablemente no funciona (el AppKey depende también del certificado); riesgo de instalar el flavor equivocado; cambia nombres de tareas de test. | Plan T7.1 | Sacar `osdDirect` de `release` (o solo debug con banner); documentar que release = `companion` | ABIERTO | Revisión del diff de T7.1 (RDD R7) |
| **F8** | BAJO / hardware | `connectedDevice` desde `BOOT_COMPLETED`, Doze y sueño de 8 h; force-stop deja el puente muerto sin aviso; exención de batería denegada sin fault. | `BootReceiver.kt`, `OsdBridgeService.kt` | Recordatorio de exención de batería; resto es hardware | ABIERTO | DV-2, DV-6 |

### Hallazgos de proceso (no son bugs de código)

| ID | Hallazgo | Estado |
|---|---|---|
| P1 | `tasks.md` T7.3 decía "no re-raise" sobre la aprobación de constantes; contradice la regla del skill (la firma humana no se reemplaza). | Se corrige en el Batch 5c (edición de `tasks.md`) |
| P2 | La cuenta de "falla visible ≤ 60 s" no tiene margen y `design.md` usa 30 s de stale en el camino de entrada donde la constante es 40 s. | ABIERTO → **T7.7** (corregir aritmética o firmar un techo de ~65–70 s) |
| P3 | El CI solo corre tests de `:wear` y no corre en PRs apilados. | ABIERTO (agregar `:phone:testDebugUnitTest` al workflow; decisión pendiente) |

---

## 3. Constantes de detección que exigen firma humana

Ninguna está firmada todavía. La aprobación previa (engram #1196) **no reemplaza** esta firma.
Cuando firmes, anotalo en `CLINICAL_SIGNOFF.md` y actualizá la columna "Estado".

| Constante | Valor propuesto | Dónde vive | Recomendación del revisor | Estado |
|---|---|---|---|---|
| `techo_latencia_clinica` | PENDIENTE | ya en cola de firma | Firmar primero: de él dependen el stride y el techo de 8 s del relay | Sin firmar |
| Techo "falla visible" (ventana ciega máxima) | 60 s | spec / `design.md` | Escalar: es un techo de latencia. Aceptar ~65–70 s firmado, o bajar `STALE` a 30 s | Sin firmar |
| `WATCHDOG_INTERVAL_MS` | 30 s → 10 s | `SeizureMonitorService.kt` (Batch 7) | Razonable | Sin firmar |
| `DELIVERY_STALE_MS` | 60 s → 40 s | ídem | Firmar | Sin firmar |
| `ALARM_STATE_STALE_MS` | 40 s (nuevo) | ídem | Firmar solo junto con el arreglo de F1 | Sin firmar |
| Histéresis | 2 ticks | ídem | Firmar (multiplica el techo) | Sin firmar |
| Warm-up | 60 s | ídem | Firmar (retrasa hasta ~80 s el primer DEGRADED) | Sin firmar |
| Keep-alive del relay | 10 s (efectivo 10–15 s) | `AlarmStateRelay.kt` | Firmar (40 s tolera 3 pérdidas) | Sin firmar |
| `NO_WATCH_DATA_MS` / `POST_OK_STALE_MS` | 30 s / 20 s | `BridgeHealth.kt` (ya mergeado) | Firmar | Sin firmar |
| `OSD_DATA_FRESH_MS` | 15 s (propuesto en 5c) | `:phone` (Batch 5c) | Firmar | Sin firmar |
| Rango de `sample_freq` aceptado | 1..200 | `WatchMessageParser.kt` | Endurecer a `== 25` (F6) | Sin arreglar |

---

## 4. Condiciones para pasar de BLOCK a PASS (checklist)

- [x] **1.** F1 arreglado y con tests de estado congelado (Batch 5c, PRs #22/#23 mergeados). Falta DV-4 en hardware.
- [~] **2.** Política para los estados 3–7: **definida** (DEC-057). Falta enmendar el spec WCT-8 e implementarla (T7.5).
- [~] **3.** ~~DEGRADED persistente~~ reemplazado por DEGRADED solo visual (DEC-057). Falta reescribir T7.6.
- [ ] **4.** Constantes de la sección 3 firmadas; aritmética corregida con holgura (P2, T7.7).
- [ ] **5.** `CAREGIVER_GUIDE.md` y `HARDWARE_RUNBOOK.md` reescritos (pueden ir en Batch 9, pero antes del merge del Batch 7).
- [ ] **6.** `osdDirect` fuera de `release` (F7).
- [ ] **7.** Segundo `safety-reviewer` (R6) sobre el resultado de 1–6 con veredicto PASS.

**Riesgo residual aun cumpliendo todo:** sensor congelado sin variación; si puente y reloj fallan a
la vez solo queda la vibración del reloj; depende de que la persona duerma con el reloj puesto y
cargado.

---

## 5. Documentación de cuidadores desactualizada (R11)

| Documento | Qué está mal | Estado |
|---|---|---|
| `CAREGIVER_GUIDE.md` (líneas ~39–46, 95–97) | Pide "la app del teléfono abierta y dice que recibe datos": el puente no tiene pantalla de estado. No describe la arquitectura de dos apps ni el DEGRADED (2 pulsos). "Deja de proteger si…" omite OSD cerrado/fuente incorrecta, puente detenido, reinicio del teléfono, notificaciones bloqueadas. No dice qué hacer ante una notificación de falla. | ABIERTO |
| `HARDWARE_RUNBOOK.md` §4 (líneas ~137–212) | Describe habilitar la fuente Android Wear, el camino roto por DEC-050. | ABIERTO |
| `CLINICAL_SIGNOFF.md` | Falta registrar las constantes de la sección 3 y la política de F2. | ABIERTO |
| `DISCLAIMER.md` | No verificado (no se encontraron menciones a OSD ni al teléfono). | SIN VERIFICAR |

---

## 6. Pruebas en hardware pendientes (DV)

| ID | Qué confirma | Relacionado con | Estado |
|---|---|---|---|
| DV-1 | `startForeground(connectedDevice)` con `BLUETOOTH_CONNECT` denegado: falla ruidosa, sin crash | F4, Batch 5a | Pendiente |
| DV-2 | `connectedDevice` permitido desde `BOOT_COMPLETED`; camino de degradación | F8, Batch 6 | Pendiente |
| DV-3 | `mDataFrequencyCheckEnabled` en OSD (tolerancia de jitter) | Batch 4/5 | Pendiente |
| DV-4 | Estado congelado: OSD en fuente "Phone" o `processData` roto → DEGRADED en el reloj dentro del techo firmado | **F1** | Pendiente (caso concreto ahora definido) |
| DV-5 | Costo de batería del teléfono con el refresco de 10 s | Batch 7/8 | Puede esperar |
| DV-6 | Una noche completa de 8 h | Todo | Pendiente |
| **DV-7 (nuevo)** | Inyección de fallas: matar listener, matar OSD, force-stop del puente, No Molestar, notificaciones denegadas; medir tiempo a la falla visible | F4, F5, F8 | Pendiente |

---

## 7. Cómo usar este documento en la próxima revisión

1. Para cada fila de la sección 2, mirar la columna "Cómo verificar" y anotar el resultado.
2. Cambiar el estado (`ARREGLADO` → `VERIFICADO` solo con test verde o prueba en el reloj).
3. Marcar el checklist de la sección 4; cuando esté completo, correr R6.
4. Registrar cualquier revisión nueva como fila en la sección 1, aunque no encuentre nada.

---

## 8. Riesgos aceptados por decisión del usuario (DEC-057, 2026-09-19)

No son hallazgos abiertos: son riesgos que se aceptaron sabiéndolos. Se listan para que no se
pierdan y para revisarlos en el segundo `safety-reviewer` (R6).

| ID | Riesgo aceptado | Mitigación prevista | Quién lo aceptó |
|---|---|---|---|
| A1 | Un sistema caído se ve igual que una noche tranquila: ninguna falla avisa a nadie. | Indicador pasivo, registro de períodos de falla y resumen silencioso a la mañana (Batch 5d) | Usuario. Se le advirtió y se recomendó la opción B (avisar solo si la falla dura > N min) |
| A2 | OSD fuerza FAULT por encima de ALARM (`SdServer.java:1274-1281`): con una falla activa una convulsión no genera alarma. | Proceso: dejar el teléfono de OSD cargando de noche; documentarlo | Usuario |
| A3 | MUTE (botón del reloj o de la app) tapa las alarmas y cancela el SMS mientras esté activo. No se verificó si OSD limita su duración. | Documentar; evaluar un recordatorio | Usuario |
| A4 | OSD suena por sus propias fallas (`AudibleFaultWarning`, activa por defecto): si no se apaga, la política silenciosa no se cumple. | Apagarla en OSD; que figure en `SetupActivity` y `CAREGIVER_GUIDE.md` | Pendiente de hacer |

### Alarma en el celular del cuidador (celular distinto del de OSD)

- La alarma de OSD suena por `USAGE_ALARM` y atraviesa el silencio del timbre (depende del volumen
  de alarma y de la excepción de alarmas de No Molestar): `SdServer.java:951-1000`.
- El SMS a otro celular no se controla desde el código.
- Vía candidata: OSD en el celular del cuidador con fuente "Network" (`SdDataSourceNetwork.java:32,309`).
  **Sin verificar de punta a punta:** misma red local, corte del enlace (NETFAULT), y que el
  servidor web de OSD queda expuesto sin autenticación en la LAN.
- Alternativa sin código: No Molestar con el contacto del paciente como prioritario.
- Estado: **PENDIENTE de decidir y probar** (DV nuevo).
