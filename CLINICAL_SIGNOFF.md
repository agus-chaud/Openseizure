# CLINICAL SIGNOFF — Constantes clínicas pendientes de firma humana

> **⚠️ ARQUITECTURA (2026-06-05):** la inferencia y los umbrales corren en la **app OSD V5.0**, no
> en este repo. Por lo tanto estos umbrales (0.5/0.8) **se CONFIGURAN en la app OpenSeizureDetector**,
> no se hardcodean en nuestro código. La "firma" acá significa: el humano decide qué valor poner en
> la configuración de la app OSD, con criterio clínico — sigue siendo una decisión de vida/muerte.

---

## Cómo firmar

Para firmar una constante: completá `Valor firmado`, `Firmado por` y `Fecha`, y movela a la
sección **Firmadas**. Mientras esté en **Pendientes**, la fase asociada queda bloqueada.

---

## Pendientes

| Constante | Valor de partida (upstream) | Tradeoff (vida/muerte) | Fase | Estado |
|---|---|---|---|---|
| `umbral_WARNING` | 0.5 (plan maestro / OSD) | Prob ≥ 0.5 dispara WARNING. Bajar = más sensible PERO más falsas alarmas → fatiga del cuidador. Subir = menos falsas alarmas PERO se pierde el aviso temprano. | 3.3 / 4.3 | ⏳ PENDIENTE |
| `umbral_ALARM` | 0.8 (plan maestro) | Prob ≥ 0.8 dispara ALARM (sirena + SMS). Bajar = alarma más temprana PERO más falsas alarmas que despiertan al cuidador sin convulsión real. Subir = menos falsas PERO más riesgo de falso negativo = no se despierta a nadie. | 3.3 / 4.3 | ⏳ PENDIENTE |
| `N_ventanas_warning_alarm` | (a definir — el plan usa umbral por prob, no por N ventanas; confirmar si se usa conteo) | Más ventanas para confirmar = menos falsas alarmas PERO más latencia. Menos = alarma más rápida PERO más falsas. | 3.3 | ⏳ PENDIENTE |
| `techo_latencia_clinica` | **40 s** | Tiempo máximo aceptable desde el inicio de la convulsión hasta que suena la alarma. Define el rango legal del stride de inferencia. Más alto = menos batería PERO respuesta más lenta. | 3.2 | ✅ FIRMADA 2026-09-30 |

> Nota sobre el **stride de inferencia (5s)**: NO es una constante clínica en sí — es `peripheral`
> MIENTRAS respete el `techo_latencia_clinica` firmado. Default 5s heredado del patrón OSD/Graham
> (ventanas de 30s solapadas). Si se empuja más lento que el techo para ahorrar batería, pasa a
> ser decisión clínica y vuelve a esta cola.

---

### Constantes del puente reloj ↔ OSD (`watch-osd-message-delivery`, DEC-057)

Con la política de fallas silenciosas (DEC-057) estos tiempos ya no disparan una alerta: definen
**cuánto tarda el sistema en darse cuenta de que dejó de proteger** y en registrarlo. Más corto =
se detecta antes pero hay más registros falsos ante una pérdida breve. Más largo = menos falsos
registros pero más tiempo sin protección sin registrar. La aprobación del 2026-09-12 (engram #1196)
**no reemplaza esta firma**. El Batch 7 (`:wear`) no puede mergearse con alguna sin firmar.
Registro de hallazgos: `docs/SAFETY_FINDINGS_WATCH_OSD.md`.

> **Actualización 2026-10-01:** el Batch 7 (`:wear`) y el Batch 8 ya están mergeados en `main`, con las
> constantes firmadas de abajo en el código del reloj (`SeizureMonitorService.kt`: 10 s / 40 s / 40 s,
> warm-up 60 s, 2 chequeos seguidos). La columna "Hoy" de la tabla de cuentas de peor caso describe los
> valores **anteriores** al Batch 7. Los tiempos siguen **calculados, no medidos**: la verificación en
> hardware (DV-1..DV-7) no se hizo.

**Política de estados de OSD en el reloj (DEC-057, decidida por el usuario 2026-09-19,
implementada en Batch 7):** 2, 3 y 5 → vibración de alarma; 4, 7 y cualquier valor desconocido →
falla del sistema silenciosa (solo pantalla y registro); 6 (MUTE) → sin vibración; 0 y 1 sin
cambios. Ampliar el conjunto que vibra sube las falsas alarmas; achicarlo arriesga una convulsión
sin alarma.

**Cuentas de peor caso** (calculadas, no medidas en hardware; con jitter pueden pasar unos segundos):

| Camino | Fórmula | Hoy | Con los valores propuestos |
|---|---|---|---|
| Se pierde el enlace reloj→celular | `DELIVERY_STALE + 2 × INTERVALO` | 120 s | **60 s** |
| Se detiene el estado de alarma que llega al reloj | `ALARM_STATE_STALE + 2 × INTERVALO` | no existe | **60 s** |
| OSD se congela y el celular sigue consultando | 15 s de frescura + 60 s del camino anterior | no se detecta | **≈ 75 s** |
| Sensor del reloj muerto | `SAMPLE_STALE + 2 × INTERVALO` | 70 s | **30 s** |
| Primer DEGRADED tras arrancar el monitoreo | warm-up + 1 tick + histéresis | 120 s | **≈ 80 s** |

| Constante | Hoy → propuesto | Dónde | Qué controla / tradeoff | Recomendación | Estado |
|---|---|---|---|---|---|
| `WATCHDOG_INTERVAL_MS` | 30 s → **10 s** | reloj (Batch 7) | Cada cuánto revisa la salud. Más corto detecta antes; el costo de batería es despreciable. | Firmar 10 s | ✅ FIRMADA 2026-09-19 |
| `DELIVERY_STALE_MS` | 60 s → **40 s** | reloj (Batch 7) | Silencio tolerado del enlace al celular. Los datos van cada 5 s, así que 40 s tolera unos 7 envíos perdidos. | Firmar 40 s | ✅ FIRMADA 2026-09-19 |
| `ALARM_STATE_STALE_MS` | (nueva) **40 s** | reloj (Batch 7) | Silencio tolerado del estado de alarma. El celular refresca cada 10 a 15 s, así que tolera unos 2 a 3 refrescos perdidos. | Firmar 40 s, **solo junto con** el arreglo F1 (ya mergeado) | ✅ FIRMADA 2026-09-19 |
| `UNHEALTHY_CHECKS_FOR_DEGRADED` (histéresis) | 2 → **2** | reloj | Revisiones malas seguidas antes de marcar DEGRADED. Filtra cortes de un instante; suma un intervalo al techo. | Firmar 2 | ✅ FIRMADA 2026-09-19 |
| `WATCHDOG_WARMUP_MS` | 60 s → **60 s** | reloj | Período de gracia al arrancar, antes de juzgar. | Firmar 60 s | ✅ FIRMADA 2026-09-19 |
| `SAMPLE_STALE_MS` | 10 s → **10 s** | reloj | Sin muestras del acelerómetro por más de esto = sensor muerto. | Firmar 10 s | ✅ FIRMADA 2026-09-19 |
| Techo de "falla visible" | 60 s → **60 s (enlace) / ≈ 75 s (OSD congelado)** | resultado de lo anterior | Tiempo máximo hasta que el sistema registra que dejó de proteger. | Aceptar 60/75 s. Alternativa: bajar los `STALE` a 30 s (techos 50/65 s) con más registros falsos | ✅ FIRMADA 2026-09-19 |
| `ALARM_KEEP_ALIVE_MS` | (nueva) **10 s** (efectivo 10 a 15 s) | celular (mergeado) | Cada cuánto el celular repite el estado de alarma al reloj. | Firmar 10 s | ✅ FIRMADA 2026-09-19 |
| `OSD_DATA_FRESH_MS` | (nueva) **15 s** | celular (mergeado) | Cuánto puede quedar sin avanzar el timestamp de OSD antes de dejar de mandar `0`. El reloj manda cada 5 s: tolera 2 ciclos perdidos. | Firmar 15 s | ✅ FIRMADA 2026-09-19 |
| `NO_WATCH_DATA_MS` | (nueva) **30 s** | celular (mergeado) | Sin mensajes del reloj por más de esto = falla "sin datos del reloj". | Firmar 30 s | ✅ FIRMADA 2026-09-19 |
| `POST_OK_STALE_MS` y `MAX_POST_FAILURES` | (nuevas) **20 s** y **3** | celular (mergeado) | Cuándo se considera que OSD no responde (3 fallos seguidos o 20 s sin un POST exitoso). | Firmar 20 s y 3 | ✅ FIRMADA 2026-09-19 |
| `LATCH_CLEAR_OK_STREAK` | (nueva) **2** | celular (mergeado) | Respuestas OK seguidas para limpiar "fuente equivocada" o "OSD rechaza datos". Con 1 se limpia con un OK suelto. | Firmar 2 | ✅ FIRMADA 2026-09-19 |
| Techo del relay de alarma | (diseño) **8 s** | celular (mergeado) | Máximo desde que OSD calcula un estado hasta que llega al reloj. Típico: menos de 1 s. | Firmar 8 s | ✅ FIRMADA 2026-09-19 |
| Rango de `sample_freq` aceptado | 1 a 200 → **exactamente 25** | celular (requiere cambio de código) | Un valor distinto de 25 desescala el análisis de OSD sin avisar. | Firmar 25 y endurecer el parser (hallazgo F6) | ✅ FIRMADA 2026-09-19 |
| `DEFAULT_HANDSHAKE_BATTERY` | (nueva) **100** | celular (mergeado) | Batería que se le informa a OSD si pide ajustes antes de que el reloj mande los suyos. Puede ocultar una batería baja unos segundos. | Dejar 100 | ✅ FIRMADA 2026-09-19 |
| `techo_latencia_clinica` (de la tabla de arriba) | (a definir) → **40 s** | — | Tiempo máximo aceptable entre el inicio de la convulsión y la alarma. Lo define OSD (ventana de 30 s con paso de 5 s) más el relay. | Decisión clínica del usuario (se le presentaron 30 s y 60 s; eligió 40 s) | ✅ FIRMADA 2026-09-30 |

**No requieren firma** (solo afectan el registro y el resumen, no la detección ni la alarma; se
listan por transparencia): `SERVICE_DOWN_THRESHOLD_MS` 120 s, `ALIVE_WRITE_INTERVAL_MS` 60 s,
retención del registro 14 días, resumen a las 8:00 sobre una ventana de 12 h, timeouts HTTP de 4 s
y 3 s, período de consulta 5 s y pausa de 250 ms, `ACCEL_CHUNK_SAMPLES` 125 (lo fija el contrato).

---

## Firmadas

| Constante | Valor firmado | Firmado por | Fecha | Justificación |
|---|---|---|---|---|
| `WATCHDOG_INTERVAL_MS` | 10 s (antes 30 s) | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Reloj, Batch 7. Detalle en la tabla de arriba. |
| `DELIVERY_STALE_MS` | 40 s (antes 60 s) | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Reloj, Batch 7. Detalle en la tabla de arriba. |
| `ALARM_STATE_STALE_MS` | 40 s (nueva) | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Reloj, Batch 7. Condicionada al arreglo F1, ya mergeado (Batch 5c). Detalle en la tabla de arriba. |
| `UNHEALTHY_CHECKS_FOR_DEGRADED` (histéresis) | 2 ticks | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Reloj, sin cambio. Detalle en la tabla de arriba. |
| `WATCHDOG_WARMUP_MS` | 60 s | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Reloj, sin cambio. Detalle en la tabla de arriba. |
| `SAMPLE_STALE_MS` | 10 s | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Reloj, sin cambio. Detalle en la tabla de arriba. |
| Techo de "falla visible" | 60 s (enlace) y ≈ 75 s (OSD congelado) | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Resultado de los valores anteriores; cuentas calculadas, sin medir en hardware. Detalle en la tabla de arriba. |
| `ALARM_KEEP_ALIVE_MS` | 10 s (efectivo 10 a 15 s) | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Celular, ya mergeado. Detalle en la tabla de arriba. |
| `OSD_DATA_FRESH_MS` | 15 s | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Celular, ya mergeado (Batch 5c). Detalle en la tabla de arriba. |
| `NO_WATCH_DATA_MS` | 30 s | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Celular, ya mergeado. Detalle en la tabla de arriba. |
| `POST_OK_STALE_MS` y `MAX_POST_FAILURES` | 20 s y 3 | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Celular, ya mergeado. Detalle en la tabla de arriba. |
| `LATCH_CLEAR_OK_STREAK` | 2 | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Celular, ya mergeado. Detalle en la tabla de arriba. |
| Techo del relay de alarma | 8 s | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Diseño; típico menos de 1 s. Detalle en la tabla de arriba. |
| Rango de `sample_freq` aceptado | exactamente 25 | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. **Endurecido en código** (Batch 5e, PR #27, mergeado 2026-09-19): un valor distinto de 25 se descarta y no llega a OSD. Detalle en la tabla de arriba. |
| `DEFAULT_HANDSHAKE_BATTERY` | 100 | agus-chaud (usuario del proyecto) | 2026-09-19 | Firmada tal cual se propuso. Celular, ya mergeado. Detalle en la tabla de arriba. |
| `techo_latencia_clinica` | 40 s | agus-chaud (usuario del proyecto) | 2026-09-30 | Decisión clínica del usuario, sin valor propuesto previo. Presupuesto: paso de análisis de OSD ≤ 5 s + relay ≤ 8 s = hasta 13 s, lo que deja ≈ 27 s para que el modelo reconozca la convulsión dentro de su ventana de 30 s. Ese tiempo de reconocimiento **no está medido**: se valida en las pruebas con hardware (DV). Si no se cumple, se revisa y se vuelve a firmar. El stride de 5 s queda dentro del techo. |
