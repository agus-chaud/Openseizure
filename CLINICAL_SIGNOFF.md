# CLINICAL SIGNOFF — Constantes clínicas pendientes de firma humana

> Este archivo es la **cola de decisiones clínicas** de SeizureGuard. El agente NUNCA decide
> estos valores por sí mismo: los extrae, documenta el tradeoff en lenguaje vida/muerte, y los
> deja acá para que un humano (idealmente con criterio clínico / literatura de OpenSeizureDetector)
> los **firme**. Ninguna fase puede pasar a **Field-Done** mientras tenga constantes sin firmar.

Custodiado por la skill `safety-reviewer`. Ver `architecture/seizureguard-finisher-agent` en engram.

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
