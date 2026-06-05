# CLINICAL SIGNOFF — Constantes clínicas pendientes de firma humana

> Este archivo es la **cola de decisiones clínicas** de SeizureGuard. El agente NUNCA decide
> estos valores por sí mismo: los extrae, documenta el tradeoff en lenguaje vida/muerte, y los
> deja acá para que un humano (idealmente con criterio clínico / literatura de OpenSeizureDetector)
> los **firme**. Ninguna fase puede pasar a **Field-Done** mientras tenga constantes sin firmar.

Custodiado por la skill `safety-reviewer`. Ver `architecture/seizureguard-finisher-agent` en engram.

---

## Cómo firmar

Para firmar una constante: completá `Valor firmado`, `Firmado por` y `Fecha`, y movela a la
sección **Firmadas**. Mientras esté en **Pendientes**, la fase asociada queda bloqueada.

---

## Pendientes

| Constante | Valor de partida (upstream) | Tradeoff (vida/muerte) | Fase | Estado |
|---|---|---|---|---|
| `umbral_prob_seizure` | 0.5 (OpenSeizureDetector) | Subir = menos falsas alarmas PERO más falsos negativos = más riesgo de no detectar una convulsión real. Bajar = más sensible PERO más falsas alarmas → fatiga del cuidador → riesgo de que ignore una real. | 3.4 / 4.3 | ⏳ PENDIENTE |
| `N_ventanas_warning_alarm` | (a definir) | Más ventanas para confirmar = menos falsas alarmas PERO más latencia hasta la alarma = el cuidador llega más tarde. Menos ventanas = alarma más rápida PERO más falsas alarmas. | 3.4 | ⏳ PENDIENTE |
| `techo_latencia_clinica` | (a definir) | Tiempo máximo aceptable desde el inicio de la convulsión hasta que suena la alarma. Define el rango legal del stride de inferencia. Más alto = menos batería PERO respuesta más lenta. | 3.2 | ⏳ PENDIENTE |

> Nota sobre el **stride de inferencia (5s)**: NO es una constante clínica en sí — es `peripheral`
> MIENTRAS respete el `techo_latencia_clinica` firmado. Default 5s heredado del patrón OSD/Graham
> (ventanas de 30s solapadas). Si se empuja más lento que el techo para ahorrar batería, pasa a
> ser decisión clínica y vuelve a esta cola.

---

## Firmadas

| Constante | Valor firmado | Firmado por | Fecha | Justificación |
|---|---|---|---|---|
| _(ninguna todavía)_ | | | | |
